# Adjust or delete sleep and naps: spec

Status: draft, 2026-10-30. No branch yet. Mockups: the
[sleep and nap edits canvas](https://claude.ai/artifact/GyxZkFcptwfTVj95XDF5Bn). Expands section 1 of
[../requests/spec.md](../requests/spec.md) and replaces it where they differ. Follows the v2 design system
([../v2/spec.md](../v2/spec.md) section 2) and its copy rules.

## 0. Goal

Let people fix a sleep or nap the strap got wrong: move its start or end, or remove it (a "nap" while sitting still
at a desk, a "sleep" while the strap lay on the bedside table). **Main sleep and naps share one logic, one table, one
sheet and the same two buttons.**

## 1. Today [SleepNow, NapsNow, NapTimesNow]

- Naps have **Adjust times** (`NapsDetail` and `NapTimesSheet` in `SleepDetail.kt`). The user's times are in
  `nap_edit` (`NapEdit`: strapStart, start, end), applied by `NapTimes.apply`, which ignores anything that isn't a
  nap.
- Main sleep has no edit. Nothing can be deleted.
- Edits are applied in `DayViewModel`, `RecoveryEngine` (twice), `HealthConnectExporter` and the change feed in
  `HealthRepository`. Other readers of sleep sessions don't apply them: the widgets (`SleepWidgetModel`,
  `MomentWidget`, `WidgetMoment`), `HealthspanEngine`, `SleepHistory`, `CsvTables` and `WorkoutsViewModel`. With
  naps only, that gap was small. With main sleep and deletes it would show different totals on different screens.
- A sync deletes and rewrites a day's sessions (`HistorySync.kt`, `deleteSleepSessionsForDay`), which is why edits
  live in their own table keyed by the strap's start.

## 2. Data: one edit record

Replace `nap_edit` with `sleep_edit`:

| Column | Meaning |
|---|---|
| `strapStart` (key) | The strap's start for the session |
| `nap` | Which kind it was, so a match never crosses kinds |
| `start`, `end` | The user's times, null when not moved |
| `deleted` | True when removed |
| `editedAt` | When, for the Deleted sleep list and the 7 day clean up |

- The migration (Room 24 to 25, or whichever is next) copies every `nap_edit` row with `nap = true`,
  `deleted = false`, `editedAt` = migration time, then drops `nap_edit`.
- A row with no times and `deleted = false` is removed, so "Use the strap's times" leaves no trace (as
  `NapTimes.edit` does today).
- **Matching a session to its edit**: the exact `strapStart` first. If none, the session of the same kind whose
  start is within 15 minutes, so a resync that moves the strap's start a little keeps the edit. Unit tested.
- Raw rows (sessions and stages) are never changed by an edit, so Undo and Restore are exact. A delete removes them
  only after 7 days (4.4).

## 3. Logic: `SleepEdits`

`NapTimes` becomes `SleepEdits`, one pure object for both kinds:

- `apply(sessions, edits)`: drops deleted sessions, moves edited ones. The only way any code reads sleep for display
  or maths.
- `clip(stages, start, end)`: as today, stages outside the user's times go.
- `problem(strap, start, end, now)`: why times can't be saved, by kind:

| Rule | Nap | Main sleep |
|---|---|---|
| Woke after fell asleep | yes | yes |
| Shortest | 5 min | 1 h |
| Longest | 4 h | 16 h |
| Furthest either end may move from the strap's | 3 h | 3 h |
| Not after now | yes | yes |
| Doesn't overlap another sleep or nap as shown | yes | yes |

Messages, one sentence each: "Waking up has to come after falling asleep.", "A nap needs at least 5 minutes.",
"Sleep needs at least an hour.", "A nap can be 4 hours at most.", "Sleep can be 16 hours at most.", "Keep it within 3
hours of what the strap saw.", "That's later than now.", "That runs into your nap at 14:05."

A repository wrapper (`HealthRepository.sleepSessions`) returns sessions already through `apply`. Every reader in
section 1 moves to it, and `dao.sleepSessions*` is called only from there and the sync. A lint style test greps for
direct DAO sleep reads outside those two places.

## 4. Screens

### 4.1 Actions [SleepEdit, NapsEdit]

- In the Sleep score detail, under Fell asleep, Woke up and Asleep: **Adjust times** (pencil) and **Delete** (trash),
  side by side, both `PageButton` with an icon. Delete has the warn text colour.
- In the Naps detail, the same pair under each nap.
- Not on the Sleep tab hero or Today. Editing is a detail page action.

### 4.2 Adjust times [TimesSheet, SleepEdited]

`NapTimesSheet` becomes `SleepTimesSheet`, one sheet for both kinds:

- Title "Sleep times" or "Nap times". Under it: "The strap saw 23:20 to 07:21." and one line on how to read the chart.
- The heart rate from 2 h before to 2 h after the strap's session, with the chosen span shaded and its two edges
  drawn, scrubbable as today.
- Segments "Fell asleep 23:55" and "Woke up 07:21", and the alarm style time picker (`AlarmClockColors`) as today.
- One line with the result: "Asleep for 7 h 26." or "A nap of 42 min.", or the problem from section 3 in warn colour.
- Cancel and Save. "Use the strap's times" only when the times differ.
- After saving: the detail shows a note "Your times. The strap saw 23:20 to 07:21." with a pencil, the stage chart
  dims what's outside, and the score, totals and sentence are worked out again from the user's span.

### 4.3 Delete [DeleteSleep, SleepDeleted, NapDeleted]

- Delete opens a sheet once: "Delete last night's sleep?" or "Delete this nap?", the times, then "It won't count
  toward your totals, scores or Health Connect. Heart rate and the other readings stay." and "You can bring it back
  from You, Data, Deleted sleep for 7 days." Cancel and a red Delete with a trash icon.
- After it: a snackbar "Last night's sleep deleted" or "Nap at 10:40 deleted" with **Undo** for 8 s.
- Main sleep deleted: the detail shows "No score" and a card "No sleep recorded" that says which times were deleted
  and that recovery works without last night, with **Restore**. The day strip loses its dot.
- A nap deleted: it leaves the list, the day's nap total and sentence change.

### 4.4 Deleted sleep [DeletedList]

- You, Data, a row **Deleted sleep** (shown only when there is something in it).
- Each entry: night or nap, its day, its times, how long until it's gone ("6 days left"), and **Restore**.
- **Deleted for good after 7 days.** A daily job (the existing sync worker) removes the session and its stages for
  every `deleted` edit older than 7 days, and the entry leaves the list. The `sleep_edit` row stays as a marker with
  `start` and `end` cleared, so a later resync of that night from the strap is dropped again instead of coming back.
  Heart rate and the other samples in the window still stay.
- No manual "delete now" in this release.

### 4.5 A hint for false naps [NapsEdit]

When a nap's average heart rate is within 5 bpm of the hour before it and it has no deep sleep, the nap shows one
line: "Heart rate stayed at 72, as when you sit still. Might not be a nap." The buttons are the same. Nothing is
removed automatically.

## 5. What a delete or move changes

- Totals, stages, sleep score, sleep need and debt, regularity, naps total and the sleep goal for that day use the
  shown sessions only.
- Main sleep deleted: recovery for that morning does what it does on a night with no sleep. The morning hero and
  widgets show their no sleep state.
- Moved: the stages are clipped (`clip`). When both ends moved less than 15 minutes in all, the strap's own score
  stays. A bigger move keeps the strap's score as the anchor and moves it by what OpenStrap's own model says the
  change does: shown = strap score + (own score of the user's span less own score of the strap's span), 0 to 100. A
  shorter night never scores above the strap's, nor a longer one below it. The score's info sheet then says "The
  strap's score, adjusted for your times." (Changed 2026-10-30 after phone testing: replacing the strap's score
  outright made a night 35 minutes shorter score 90 against the strap's 83.)
- Health Connect: the exporter already deletes and rewrites sleep for its range, so the next export removes or
  moves it there too. The export runs right after an edit, not only on the next sync.
- Heart rate, SpO2, HRV, skin temperature and breathing samples in the window stay.
- CSV export includes `sleep_edit`. Backup and restore keep it.
- Badges and streaks already earned don't change (requests spec 7.2).

## 6. Copy

- Buttons: "Adjust times", "Delete", "Restore", "Undo", "Use the strap's times".
- Times read "23:20 to 07:21" in new text. The existing nap row title "14:05–14:47" changes to "14:05 to 14:47".
- No ·, ; or dashes in UI text.

## 7. Done when

- Adjust and Delete work the same on main sleep and naps, and survive a full resync, including one that shifts the
  strap's start by a few minutes.
- After a delete or a move, the Sleep tab, Today, Recovery, the widgets, Healthspan, Activity's calories and Health
  Connect all show the same totals.
- Undo and Restore bring back exactly what was there, until day 7. After it the session and stages are gone and a
  resync doesn't bring them back.
- Unit tests for `SleepEdits`: moved, deleted, restored, moved then deleted, kinds never cross, fuzzy match within
  15 min, overlap check, every `problem` message, migration from `nap_edit`, the 7 day clean up and its marker, the
  15 minute rule for keeping the strap's score.

## 8. Build order

1. `sleep_edit`, the migration and `SleepEdits` with tests (no visible change, naps keep working).
2. Route every reader through the repository wrapper.
3. Main sleep Adjust times with the shared sheet.
4. Delete, Undo, the no sleep state, then Deleted sleep in You, Data.
5. The false nap hint.

## 9. Decisions

- Deleted sleep is removed for good after 7 days (decided 2026-10-30).
- A move under 15 minutes keeps the strap's score (decided 2026-10-30).

## 10. Open decisions

- None. "I was awake" apart from "the strap was off": recommended no. Every delete leaves the night out of averages,
  since deletes are nearly always the strap misreading (on the table, sitting still), and a zero night would drag
  sleep debt, regularity and the weekly average down for a night that was partly slept. If it's needed later, it
  goes on the "No sleep recorded" card as an optional "I didn't sleep" tag, not in the delete sheet.
