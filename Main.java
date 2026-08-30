import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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

    // How close a badge event must be to a door event to count as explaining it (device clocks are not synchronised).
    private static final long MATCH_WINDOW_SECONDS = 15;

    // Create a record/class of each DoorEvent
    record DoorEvent(LocalDateTime timestamp, String doorId, String state, boolean badgeRequiredToEnter, boolean badgeRequiredToExit, boolean eventValidated) {}

    // Create a record/class of each BadgeEvent
    record BadgeEvent(LocalDateTime timestamp, String doorId, String direction, String cardholderId, String result) {}

    // door_id -> door events at that door, sorted by timestamp
    private static Map<String, List<DoorEvent>> doorEventsByDoor = new HashMap<>();

    // door_id -> badge events at that door, sorted by timestamp
    private static Map<String, List<BadgeEvent>> badgeEventsByDoor = new HashMap<>();

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
            boolean badgeRequiredToExit = IN_OUT_DOORS.contains(doorId);

            // eventValidated is computed later, once badge events are loaded too.
            DoorEvent event = new DoorEvent(timestamp, doorId, state, true, badgeRequiredToExit, false);
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
     * Works out, for every door event, whether a badge was needed and whether a matching badge event was found within MATCH_WINDOW_SECONDS of it.
     */
    private static void validateDoorEvents() {
        for (Map.Entry<String, List<DoorEvent>> entry : doorEventsByDoor.entrySet()) {
            String doorId = entry.getKey();
            List<DoorEvent> events = entry.getValue();
            List<BadgeEvent> badgesAtDoor = badgeEventsByDoor.getOrDefault(doorId, List.of());

            // Tracks which badge events at this door have already been used to validate a door event, so the same badge cannot validate more than one door event.
            boolean[] badgeUsed = new boolean[badgesAtDoor.size()];

            for (int i = 0; i < events.size(); i++) {
                DoorEvent event = events.get(i);

                // The door position sensor only reports opened/closed, not direction, so an "opened" event is treated as the moment someone passes through the door.
                boolean isEnterEvent = event.state().equals("opened");
                boolean badgeRequired = isEnterEvent && event.badgeRequiredToEnter();

                boolean eventValidated = !badgeRequired
                        || claimMatchingBadgeEvent(badgesAtDoor, badgeUsed, event.timestamp());

                events.set(i, new DoorEvent(event.timestamp(), event.doorId(), event.state(),
                        event.badgeRequiredToEnter(), event.badgeRequiredToExit(), eventValidated));
            }
        }
    }

    /**
     * Finds the closest not-yet-used granted badge event within MATCH_WINDOW_SECONDS of the given
     * door event timestamp, marks it as used, and returns whether a match was found. Once a badge
     * event has been claimed here it cannot be used to validate any other door event.
     */
    private static boolean claimMatchingBadgeEvent(List<BadgeEvent> badgesAtDoor, boolean[] badgeUsed,
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
            return false;
        }
        badgeUsed[bestIndex] = true;
        return true;
    }
}
