# Alexis Manosca Build Log

## 1. What I built - and what I deliberately didn't, and why

## 2. Assumptions I made where the brief was ambigious

### Matching badge events to door events

- **Each door's clock has a fixed offset that I correct for before matching.** My first attempt used a symmetric 15 second window on the raw timestamps, on the assumption that the gap was human latency — the time between someone scanning their badge and pushing the door. Measuring the data disproved that: the median badge-to-open gap is negative on several doors (D13 −36s, D10 −34s, D15 −24s), and a person cannot open a door before they scan. The gap is therefore clock skew, not behaviour, which matches the site note that device clocks are set manually and not centrally synchronised. I measure each door's offset once as the median badge-to-open gap over the whole period and subtract it before matching. This is safe because the offsets are stable: 11 of the 16 doors hold the same offset across all 14 days (D02 +20s, D09 +27s, D12 +31s, D13 −36s, D10 −34s, D17 +46s, D08 −2s, D16 −1s). The uncorrected window was the single biggest source of error — it left roughly 16,000 of the 25,062 granted badges matching no door event at all.

- **After correcting for offset, a door event must fall within a tight window of a badge event to be valid.** With the per-door offset removed the remaining gap is genuine latency plus sensor delay, so a much tighter window (±5s) is appropriate. Anything outside it is treated as unmatched.

- **Badge events can only validate one door event.** Once a badge event validates the closest door event, it cannot be used to validate another door event, even if that door event also falls within the window. This is what makes a tailgated entry visible: the second person through has no badge left to claim. I match greedily — nearest unclaimed badge first — rather than solving for a globally optimal assignment, which I assumed is close enough for this data.

- **A matched door event inherits the direction of its badge; an unmatched open at an entry-only door is presumed to be an exit.** Door events record only `opened`/`closed` and carry no direction of their own. So where a door event matches a badge I take the badge's stated direction as true, and where it doesn't I fall back to the door's configuration: doors D04–D16 have push-bar exits, so leaving them legitimately produces an open with no badge. I note that this presumption is exactly what makes tailgating hard to see, since a tailgated entry and a push-bar exit produce identical records.

### Scope and data quality

- **D07 is excluded from door-based analysis.** It appears in the site description and has badge events, but `door_events.csv` contains no events for it at all — 16 doors have door events against 17 with badge events. I assume the position sensor was faulty or never fitted, so there is nothing to validate against.

- **The sensor log drops events, so I ignore `closed` events entirely.** I considered using open/close pairs to find doors held open long enough for a second person to follow through. The data does not support it: there are roughly 2,483 opens with no intervening close, but also roughly 1,611 closes with no preceding open, spread in similar proportion across every door. That symmetry is the signature of random event loss rather than doors genuinely standing open, so I treat unpaired events as logging artifacts and do not draw conclusions from open duration.

- **Only entries can be tailgated, so I ignore exits at entry-only doors.** Doors D04–D16 are entry-only with push-bar exits, so an unbadged exit is expected behaviour and carries no information. Restricting detection to inbound passage removes a large volume of legitimate events from consideration. At D01, D02, D03 and D17 both directions are badged, so an unbadged open there is anomalous either way.

- **A denied badge does not unlock the door.** There are 177 denied badge events. A door opening shortly after a denied badge, with no granted badge nearby, means someone was refused and got in regardless. I treat this as a separate and stronger finding than ordinary tailgating.

### Assumptions about the site and its people

- **The building is empty overnight.** This lets me reset occupancy daily and analyse each day independently rather than carrying state across the full two weeks.

- **One card belongs to one person, and badges are not shared or lent.** Without this, no reasoning that tracks an individual's movement holds.

- **Doors are the only way between spaces.** No windows, service hatches or unmonitored corridors. This is required for any argument based on entries and exits balancing out.

- **Known operational events are legitimate and are flagged rather than reported.** The site description gives four: deliveries at the Loading Dock on Tuesday and Thursday mornings around 10:00, the fire evacuation drill on 18 August 2026, the cleaning crew working evenings and weekends, and guards patrolling day and night. I also exclude these windows when measuring each door's clock offset, since they distort it — D03's estimated offset on 18 August is −360s against its usual −10s, purely an artifact of the drill.

- **Tailgating exists when exits exceed entries.** Over a long enough window a space does not accumulate people, so everyone who goes in must come out again. At an entry-only door, entries are badged (or tailgated) while exits are unbadged, which gives `unbadged opens = tailgated entries + exits` and `badged entries + tailgated entries = exits`. Combining these, `U = B + 2T`, so the number of tailgated entries is estimated by `T = (U − B) / 2` — the excess of unbadged over badged opens, halved. The value of this is that it needs no per-event certainty at all, so it survives the direction ambiguity that defeats event-by-event classification. It holds only where the door is the sole route into a space and over a window long enough for occupancy to reset, which the overnight-empty assumption above provides.
