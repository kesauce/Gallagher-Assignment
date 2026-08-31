# Gallagher Assignment — Tailgating Detection

## Running

From the project root:

```sh
javac Main.java
java Main door_events.csv badge_events.csv
```

The program takes two arguments — the door events CSV path, then the badge events CSV path — and prints:

- A count of doors seen in each file
- A total count of unvalidated (unexplained) door events
- A table of unvalidated event counts by door and date

## Running against the test data

The synthetic test set (`test_door_events.csv` / `test_badge_events.csv`) can be passed the same way, no copying required:

```sh
java Main test_door_events.csv test_badge_events.csv
```

The test set seeds one deliberate tailgating incident per door (17 doors); the program is expected to flag exactly 4 of them (D01, D02, D03, D17 — the only doors with in+out readers).
