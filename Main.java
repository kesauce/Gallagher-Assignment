import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class Main {

    // Use a DateTimeFormatter to format the timestamp in the data
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Doors with "in + out" readers (per site_description.md); every other door is entry-only with a push-bar exit, so leaving does not require a badge.
    private static final Set<String> IN_OUT_DOORS = Set.of("D01", "D02", "D03", "D17");

    // How close a badge event must be to a door event, after correcting for that door's fixed
    // clock offset, to count as explaining it. Device clocks are not centrally synchronised, but
    // each device's own offset is stable, so the remaining gap after correction is genuine
    // latency/sensor delay rather than clock skew, hence a tight window.
    private static final long MATCH_WINDOW_SECONDS = 5;

    // Create a record/class of each DoorEvent. direction is "in"/"out" when known (inherited from
    // a matched badge, or presumed "out" for an unmatched open at a push-bar entry-only door), or
    // null when a badge was required but none matched (i.e. an unexplained open at an in+out door).
    record DoorEvent(LocalDateTime timestamp, String doorId, String state, String direction, boolean eventValidated) {}

    // Create a record/class of each BadgeEvent
    record BadgeEvent(LocalDateTime timestamp, String doorId, String direction, String cardholderId, String result) {}

    // door_id -> door events at that door, sorted by timestamp
    private static Map<String, List<DoorEvent>> doorEventsByDoor = new HashMap<>();

    // door_id -> badge events at that door, sorted by timestamp
    private static Map<String, List<BadgeEvent>> badgeEventsByDoor = new HashMap<>();

    // door_id -> fixed clock offset in seconds to add to that door's event timestamps so they
    // align with its badge reader's clock. Computed once as the median gap between each "opened"
    // event and its nearest granted badge event, over the whole data period.
    private static Map<String, Long> doorClockOffsetSeconds = new HashMap<>();

    public static void main(String[] args) throws IOException {
        // Read the files
        loadDoorEvents(Path.of("door_events.csv"));
        loadBadgeEvents(Path.of("badge_events.csv"));
        validateDoorEvents();

        System.out.println("Doors with door events: " + doorEventsByDoor.size());
        System.out.println("Doors with badge events: " + badgeEventsByDoor.size());

        long unvalidatedCount = doorEventsByDoor.values().stream()
                .flatMap(List::stream)
                .filter(event -> !event.eventValidated())
                .count();
        System.out.println("Unvalidated door events: " + unvalidatedCount);

        printUnvalidatedEventTable();
    }

    /**
     * Prints a table of unvalidated door event counts with door on the y axis and date on the x
     * axis.
     */
    private static void printUnvalidatedEventTable() {
        Map<String, Map<LocalDate, Long>> unvalidatedCountsByDoorAndDate = new HashMap<>();
        TreeSet<LocalDate> allDates = new TreeSet<>();

        for (Map.Entry<String, List<DoorEvent>> entry : doorEventsByDoor.entrySet()) {
            String doorId = entry.getKey();
            for (DoorEvent event : entry.getValue()) {
                LocalDate date = event.timestamp().toLocalDate();
                allDates.add(date);

                if (!event.eventValidated()) {
                    unvalidatedCountsByDoorAndDate
                            .computeIfAbsent(doorId, k -> new HashMap<>())
                            .merge(date, 1L, Long::sum);
                }
            }
        }

        TreeSet<String> allDoors = new TreeSet<>(doorEventsByDoor.keySet());
        DateTimeFormatter columnFormat = DateTimeFormatter.ofPattern("MM-dd");

        StringBuilder header = new StringBuilder(String.format("%-6s", "Door"));
        for (LocalDate date : allDates) {
            header.append(String.format("%7s", date.format(columnFormat)));
        }
        System.out.println();
        System.out.println(header);

        for (String doorId : allDoors) {
            Map<LocalDate, Long> countsByDate = unvalidatedCountsByDoorAndDate.getOrDefault(doorId, Map.of());

            StringBuilder row = new StringBuilder(String.format("%-6s", doorId));
            for (LocalDate date : allDates) {
                long count = countsByDate.getOrDefault(date, 0L);
                row.append(String.format("%7d", count));
            }
            System.out.println(row);
        }
    }

    /**
     * Loads all the door events in the given file to the hashmap
     * 
     * @param csvPath
     * @throws IOException
     */
    private static void loadDoorEvents(Path csvPath) throws IOException {
        List<String> lines = Files.readAllLines(csvPath);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split(",", -1);
            LocalDateTime timestamp = LocalDateTime.parse(fields[0], TIMESTAMP_FORMAT);
            String doorId = fields[1];
            String state = fields[2];

            // direction and eventValidated are computed later, once badge events are loaded too.
            DoorEvent event = new DoorEvent(timestamp, doorId, state, null, false);
            doorEventsByDoor.computeIfAbsent(doorId, k -> new ArrayList<>()).add(event);
        }

        for (List<DoorEvent> events : doorEventsByDoor.values()) {
            events.sort(Comparator.comparing(DoorEvent::timestamp));
        }
    }

    /**
     * Loads all the badge events from the path to the hashmap
     * 
     * @param csvPath
     * @throws IOException
     */
    private static void loadBadgeEvents(Path csvPath) throws IOException {
        List<String> lines = Files.readAllLines(csvPath);
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split(",", -1);
            LocalDateTime timestamp = LocalDateTime.parse(fields[0], TIMESTAMP_FORMAT);
            String doorId = fields[1];
            String direction = fields[2];
            String cardholderId = fields[3];
            String result = fields[4];

            BadgeEvent event = new BadgeEvent(timestamp, doorId, direction, cardholderId, result);
            badgeEventsByDoor.computeIfAbsent(doorId, k -> new ArrayList<>()).add(event);
        }

        for (List<BadgeEvent> events : badgeEventsByDoor.values()) {
            events.sort(Comparator.comparing(BadgeEvent::timestamp));
        }
    }

    /**
     * Works out, for every "opened" door event, which badge (if any) explains it, and from that
     * derives a direction and a validated flag:
     * - If a badge matches, the door event inherits that badge's direction and is validated.
     * - If no badge matches at an entry-only door, the open is presumed to be a legitimate
     *   push-bar exit ("out") and is validated, since those doors never log a badge for leaving.
     * - If no badge matches at an in+out door, direction is left unknown and the event is left
     *   unvalidated: a badge is required in both directions there, so an unexplained open is a
     *   genuine anomaly (e.g. tailgating or a forced/propped door), not an expected exit.
     * "closed" events never require a badge and are always considered validated.
     */
    private static void validateDoorEvents() {
        computeClockOffsets();

        for (Map.Entry<String, List<DoorEvent>> entry : doorEventsByDoor.entrySet()) {
            String doorId = entry.getKey();
            List<DoorEvent> events = entry.getValue();
            List<BadgeEvent> badgesAtDoor = badgeEventsByDoor.getOrDefault(doorId, List.of());
            long offsetSeconds = doorClockOffsetSeconds.getOrDefault(doorId, 0L);
            boolean isInOutDoor = IN_OUT_DOORS.contains(doorId);

            // Tracks which badge events at this door have already been used to validate a door event, so the same badge cannot validate more than one door event.
            boolean[] badgeUsed = new boolean[badgesAtDoor.size()];

            for (int i = 0; i < events.size(); i++) {
                DoorEvent event = events.get(i);

                if (!event.state().equals("opened")) {
                    events.set(i, new DoorEvent(event.timestamp(), event.doorId(), event.state(), null, true));
                    continue;
                }

                // Correct for this door's fixed clock offset before comparing against the badge reader's clock.
                LocalDateTime adjustedTimestamp = event.timestamp().plusSeconds(offsetSeconds);
                BadgeEvent matchedBadge = claimMatchingBadgeEvent(badgesAtDoor, badgeUsed, adjustedTimestamp);

                String direction;
                boolean eventValidated;
                if (matchedBadge != null) {
                    direction = matchedBadge.direction();
                    eventValidated = true;
                } else if (!isInOutDoor) {
                    direction = "out";
                    eventValidated = true;
                } else {
                    direction = null;
                    eventValidated = false;
                }

                events.set(i, new DoorEvent(event.timestamp(), event.doorId(), event.state(), direction, eventValidated));
            }
        }
    }

    /**
     * Computes each door's fixed clock offset (in seconds) and stores it in
     * doorClockOffsetSeconds. The offset is the median gap between each "opened" door event and
     * its nearest granted badge event at that door, over the whole data period. Adding this
     * offset to a door event's timestamp aligns it with its badge reader's clock.
     */
    private static void computeClockOffsets() {
        for (Map.Entry<String, List<DoorEvent>> entry : doorEventsByDoor.entrySet()) {
            String doorId = entry.getKey();

            List<BadgeEvent> grantedBadges = badgeEventsByDoor.getOrDefault(doorId, List.of()).stream()
                    .filter(badge -> badge.result().equals("granted"))
                    .toList();

            List<Long> gapsSeconds = new ArrayList<>();
            if (!grantedBadges.isEmpty()) {
                for (DoorEvent event : entry.getValue()) {
                    if (!event.state().equals("opened")) {
                        continue;
                    }
                    BadgeEvent nearest = findNearestBadge(grantedBadges, event.timestamp());
                    gapsSeconds.add(Duration.between(event.timestamp(), nearest.timestamp()).getSeconds());
                }
            }

            doorClockOffsetSeconds.put(doorId, gapsSeconds.isEmpty() ? 0L : median(gapsSeconds));
        }
    }

    /**
     * Finds the badge event in the given (timestamp-sorted) list whose timestamp is closest to
     * the given time, with no window restriction.
     */
    private static BadgeEvent findNearestBadge(List<BadgeEvent> badges, LocalDateTime time) {
        int lo = 0, hi = badges.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (badges.get(mid).timestamp().isBefore(time)) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }

        BadgeEvent best = badges.get(lo);
        if (lo > 0) {
            BadgeEvent prev = badges.get(lo - 1);
            long prevDiff = Math.abs(Duration.between(time, prev.timestamp()).getSeconds());
            long bestDiff = Math.abs(Duration.between(time, best.timestamp()).getSeconds());
            if (prevDiff < bestDiff) {
                best = prev;
            }
        }
        return best;
    }

    /**
     * Returns the median of the given values (average of the two middle values if the count is even).
     */
    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return (n % 2 == 1) ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    /**
     * Finds the closest not-yet-used granted badge event within MATCH_WINDOW_SECONDS of the given
     * door event timestamp, marks it as used, and returns it (or null if no match was found).
     * Once a badge event has been claimed here it cannot be used to validate any other door
     * event.
     */
    private static BadgeEvent claimMatchingBadgeEvent(List<BadgeEvent> badgesAtDoor, boolean[] badgeUsed,
            LocalDateTime doorEventTime) {
        int bestIndex = -1;
        long bestDiffSeconds = Long.MAX_VALUE;

        for (int i = 0; i < badgesAtDoor.size(); i++) {
            if (badgeUsed[i]) {
                continue;
            }
            BadgeEvent badge = badgesAtDoor.get(i);
            if (!badge.result().equals("granted")) {
                continue;
            }

            long diffSeconds = Math.abs(Duration.between(doorEventTime, badge.timestamp()).getSeconds());
            if (diffSeconds <= MATCH_WINDOW_SECONDS && diffSeconds < bestDiffSeconds) {
                bestDiffSeconds = diffSeconds;
                bestIndex = i;
            }
        }

        if (bestIndex == -1) {
            return null;
        }
        badgeUsed[bestIndex] = true;
        return badgesAtDoor.get(bestIndex);
    }
}
