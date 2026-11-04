# User requests, late 2026: spec

Status: draft, 2026-10-30. No branch yet. Follows the v2 design system ([../v2/spec.md](../v2/spec.md) section 2) and
its copy rules. Each feature needs mockups before code, as v2 and widgets did.

## 0. The requests

| # | Feature | Group | Size |
|---|---|---|---|
| 1 | Delete or adjust sleep and naps | Data | Small |
| 2 | Custom heart rate zones | Workouts | Small |
| 3 | Stop the call buzz from the strap | Strap | Unknown, hardware first |
| 4 | Workout sharing: card, photo, route, map | Gamification | Medium |
| 5 | Workout sharing: video and route replay | Gamification | Large |
| 6 | Daily streaks | Gamification | Medium |
| 7 | Badges and medals | Gamification | Medium |

Sharing came up twice and is one feature here, split into 4 and 5 because video is much more work. Build order is in
section 8.

## 1. Delete or adjust sleep and naps

Now specced in full in [../sleep-edit/spec.md](../sleep-edit/spec.md), with mockups. That spec wins where they differ.

### 1.1 Goal

Let people remove a sleep or nap that is wrong (a false nap while sitting still, a "sleep" while the strap lay on a
table) and fix its times. Main sleep and naps use **the same logic and the same controls**.

### 1.2 Today

- Naps already have "Adjust times" (`SleepDetail.kt`). The user's times live in `nap_edit` (`NapEdit`), keyed by the
  strap's start for the nap, because a sync deletes and rewrites the day's sessions (`HistorySync.kt`
  `deleteSleepSessionsForDay`).
- Main sleep has no edit and nothing can be deleted.

### 1.3 Design: one edit record for both

Replace `nap_edit` with `sleep_edit`, used for main sleep and naps alike:

| Column | Meaning |
|---|---|
| `strapStart` (key) | The strap's start for the session, stable across syncs |
| `start`, `end` | The user's times, null when not changed |
| `deleted` | True when the user removed it |
| `editedAt` | When, for the restore list |

- A migration copies every `nap_edit` row in with `deleted = false`.
- One function applies edits to sessions (today's nap logic, made general). Every reader goes through it: the Sleep
  tab, Today, widgets, sleep score and need, recovery, calories, Health Connect export, CSV export. Nothing reads
  `sleep_session` directly for display.
- A sync never undoes an edit, since the key is the strap's start.
- Deleting keeps the raw rows (sessions, stages). Only the edit hides them, so restore is exact.

### 1.4 What the user sees

- In the sleep detail, for main sleep and every nap: **Adjust times** and **Delete**, both `PageButton` with an icon
  (pencil, trash).
- Delete asks once: "Delete this nap?" / "Delete last night's sleep?" with "It won't count toward totals, scores or
  Health Connect." Then a snackbar with **Undo** for 8 s.
- You > Data gets a **Deleted sleep** row listing the last 30 days of deleted sessions, each with Restore.
- Adjust times on main sleep works like the nap one: start and end within the strap's own window plus 2 h either side.

### 1.5 Effects of a delete

- Totals, stages, sleep score, sleep debt and the sleep goal for that day leave the session out.
- Deleting the main sleep: the day shows the "no sleep recorded" state. Recovery for that morning falls back to what
  it does with no sleep today.
- Health Connect: the next export deletes and rewrites sleep for that range (`HealthConnectExporter` already does
  this), so it disappears there too.
- Heart rate, SpO2 and other samples in that window stay. Only the sleep session is gone.
- CSV export and backup include `sleep_edit`, so a restore keeps the user's edits.

### 1.6 Done when

- Deleting and adjusting work the same on main sleep and naps, and survive a full resync.
- Every screen and the widgets agree on totals after a delete.
- Unit tests for the apply function: edited, deleted, restored, edited then deleted, migration from `nap_edit`.

## 2. Custom heart rate zones

### 2.1 Today

Five zones from max HR (`Workouts.zoneOf`): 50 to 60, 60 to 70, 70 to 80, 80 to 90 and 90+ % of max, named Easy,
Aerobic, Tempo, Threshold and Max (`WorkoutStyle.kt`). Max HR comes from the profile or a default of 190.

### 2.2 Design

You > Profile gets a **Heart rate zones** page:

- **Mode:** "From max heart rate" (today's zones, the default) or "Custom".
- **Custom:** five rows, one per zone, each with its colour and name and a lower bpm bound. The top of each zone is the
  next zone's lower bound, Max has no top. Editing one bound moves only that boundary.
- Switching to Custom fills the bounds from the current max HR, so the user starts from what they had.
- Rules: bounds strictly rise, at least 3 bpm apart, between 60 and 230. Invalid input shows inline and doesn't save.
- **Reset** returns to the computed zones.
- Changing max HR in Custom mode doesn't move the bounds. The page says so.

### 2.3 Where zones apply

Live workout screen, workout notification, workout summary, zone bars in history, and the share card (4). One
`HrZones` value replaces the bare `maxHr` passed around today (`zoneOf(bpm, zones)`).

**Decision needed:** zone minutes for past workouts. If they're computed when shown, old workouts change to the new
zones. Recommendation: follow the current zones everywhere, which is simpler and what most apps do. The alternative
stores the zones used with each workout.

### 2.4 Done when

- A custom zone set shows the same zone for the same bpm on every screen.
- Tests for `zoneOf` with custom bounds, edges included, and for the validation rules.

## 3. Stop the call buzz from the strap

### 3.1 The problem

A ringing call starts the strap's find buzz (`FindDevice.BUZZ`), which runs until the call is answered or ends
(`PhoneAlerts.kt`). The user wants a double tap on the strap to stop it.

### 3.2 What we know

- The strap has no screen and no known tap or gesture event.
- Tested on fw 3.25: **tapping the strap did not stop a BUZZ** (`FindDevice.kt`).
- The strap does send `07` (FOUND) on the find endpoint when buzzing stops on the strap, and the app already listens
  for it (`StrapManager.watchFindDevice`). What makes the strap send it is not known.

So a double tap may not be possible. Step 1 is a hardware test, and the fallbacks ship either way.

### 3.3 Step 1: hardware test (adb, logging every endpoint)

During a BUZZ, try: one firm tap, double tap, triple tap, a wrist flick, covering the sensor, taking it off. For each,
record any packet on any endpoint, especially `07` on find. Also search the Zepp app for a strap gesture or "tap to
stop" setting on this model. Write results to `TODO.md` like the other strap tests.

- If any action makes the strap send an event: map it to "stop the call buzz", send `STOP`, and keep the buzz off
  until the next call.
- If nothing does: no double tap. Say so to the user who asked, and ship 3.4.

### 3.4 Fallbacks (ship regardless)

- **Buzz length** setting in Phone alerts: "Until answered" (today), "30 s", "15 s", "Three pulses". A limited buzz
  sends `STOP` when time is up.
- **Silencing the phone stops the strap.** When the user presses a volume key or power to silence the ringer, stop the
  buzz too. Needs testing: which signal Android gives for a silenced ring (audio mode, ringer volume, call state) on
  the Fold. Only ship if it is reliable.
- **"Stop strap buzz"** action on a short notification during the call, for when the phone is in a pocket and the
  user is not answering.

### 3.5 Done when

The hardware result is recorded, and at least the buzz length setting ships.

## 4. Workout sharing: card, photo, route, map

### 4.1 Goal

Share a workout as an image: a plain stats card, or the user's photo with stats and the route on top.

### 4.2 Entry points

- **Share** button on the workout summary right after a workout, and on any workout in history. A `PageButton` with
  the share icon.

### 4.3 The share editor

One screen with a live preview and these controls:

- **Background:** Card (the app's black and glow), Photo (camera or gallery), or later Video (5).
- **Stats:** up to four, picked from duration, distance, pace or speed, average HR, max HR, calories, elevation, and
  the zone bar. Defaults by sport: running shows distance, pace, duration, average HR.
- **Route:** Off, Line (the route alone, no map), or Map (route on map tiles). Hidden for workouts with no GPS.
- **Layout:** stats at the top or bottom, light or dark text over a photo.
- **Size:** Story 1080 × 1920 or Post 1080 × 1350.
- **Hide start and end:** on by default whenever a route shows. Trims the first and last 200 m so home isn't on the
  image.
- Buttons: **Share** (Android share sheet) and **Save** (to the gallery through MediaStore, no permission needed).

### 4.4 Photo

- Camera: `ActivityResultContracts.TakePicture` into a cache file through a FileProvider.
- Gallery: the system Photo Picker, no storage permission.
- Crop to the chosen size by dragging and pinching the photo inside the frame.
- Add a soft dark gradient behind the stats so they read on any photo.

### 4.5 Route and map

- Points come from `AppWorkoutRoutePoint`, already saved for phone GPS workouts.
- **Line:** drawn by the app on a clear layer, coloured by HR zone like the in-app map (`ZONE_COLORS`). No network.
- **Map:** MapLibre's `MapSnapshotter` with the OpenFreeMap dark style the app already uses (`WorkoutMap.kt`),
  route drawn on the snapshot. The map attribution ("© OpenStreetMap") is drawn on the image, as the tiles' terms
  require. Needs network. Offline it falls back to Line with a note.

### 4.6 Rendering

- The image is drawn with the same Compose code as the preview, captured to a bitmap off screen, so preview and
  output always match.
- Fonts and the brand mark: the app's wordmark small in a corner, can be turned off.
- Files go to the cache and are cleared after a day.

### 4.7 Attach media to a workout

- **Keep with workout** toggle (on by default when a photo is used). The photo is copied into app storage, since
  gallery links can disappear.
- New table `workout_media`: workout start, file name, type (photo or video), created at.
- Workout detail shows kept media as a strip. Tapping opens it full screen.
- Deleting a workout deletes its media. Media is left out of the backup by default because of size, and the backup
  page says so.

### 4.8 Done when

Card, photo, route line and map each export at both sizes, match the preview, and open correctly in at least one
messaging app and Instagram stories.

## 5. Workout sharing: video and route replay

### 5.1 Video with stats overlay

- Pick or record a clip (Photo Picker video, or `CaptureVideo`). Capped at 15 s, with a simple trim slider.
- Same editor as 4 with Video as the background. Stats and route are burned in.
- Encoding with Media3 Transformer and an overlay effect. Output 1080p H.264 MP4. A progress bar and Cancel, and the
  screen stays on while encoding.

### 5.2 Route replay

An animated clip where the route draws itself over the workout's time, with a moving dot and live stats (distance,
pace, HR) ticking beside it.

- Length 8 s at 30 fps, whatever the workout's length.
- Background: black, the map snapshot from 4.5 (fixed camera, no fly over in the first version), or the user's video.
- One code path: the replay is a time based overlay. On black or a map it is the whole clip, on a video it is drawn
  over the video in the same Transformer run.
- Stats come from the route timestamps and the workout's HR series, so they line up with where the dot is.
- Needs GPS. Indoor workouts can still replay the HR curve with ticking stats, no route.

### 5.3 Done when

A 15 s clip and a replay each encode in under 30 s on the Fold, play in the gallery, and share to Instagram stories.

## 6. Daily streaks

### 6.1 Base: the day record

Streaks and badges both need to know, per day, what happened and whether each goal was met. New table `day_result`:

| Column | Meaning |
|---|---|
| `day` | Local midnight |
| `steps`, `activeHours`, `calories`, `sleepMinutes`, `workouts`, `wornHours` | The day's values |
| `stepsGoal`, `activeGoal`, `caloriesGoal`, `sleepGoal` | The goals as they were that day |
| `final` | True once the day has ended and synced |

- Goals are stored per day, so changing a goal later doesn't rewrite past streaks.
- Today's row updates live. A past row is final once a sync covers the whole day.
- On first launch the table is filled from all history, with the current goals.

### 6.2 Streak kinds

| Streak | A day counts when |
|---|---|
| Steps | Steps goal met |
| Active | Active hours goal met |
| Sleep | Sleep goal met (uses section 1's edits) |
| Workout | At least one workout |
| Worn | Strap worn for at least 20 h |

### 6.3 Rules

- Today never breaks a streak. It only adds once met.
- A day with no data yet (not synced) doesn't break a streak. It waits. A day that is final and not met ends it.
- **Rest days:** Workout streaks allow one missed day in any 7 without breaking. **Decision needed:** keep this, or
  make the Workout streak weekly (a goal of N workouts per week) instead.
- Each streak keeps its current and best length.

### 6.4 What the user sees

- A **Streaks** card on Today when any streak is 3 days or more. Shows the longest active one and "Goal met" when
  today counts. The card's look must differ from its neighbours (no two cards in a row look alike).
- A Streaks page from You: every streak with current and best, and a calendar of met days.
- A quiet notification when a streak is about to break in the evening is **off** by default. Optional, decided later.

## 7. Badges and medals

### 7.1 The set (first version)

| Group | Badges |
|---|---|
| Firsts | First workout, first 10k steps day, first night with the sleep goal met |
| Milestones | 10, 50, 100, 500 workouts. 100, 500, 1000 km in total. 1 million steps in total |
| Streaks | 7, 30, 100, 365 days, for each streak kind in 6.2 |
| Personal bests | Most steps in a day, longest workout, longest run, fastest 5 km and 10 km (from the route) |

Personal bests are records, not one time badges: a new best replaces the old one and keeps its date.

### 7.2 Rules

- Computed from `day_result` and workouts, so they are earned for past history on first launch. The first launch
  shows them all at once on one screen, not one by one.
- Each earned badge stores: id, earned date, and the workout or day that earned it.
- Deleted workouts or sleep (section 1) don't take back a badge already earned. **Decision needed** if that should
  change.

### 7.3 What the user sees

- **Earned now:** shown on the workout summary or as a short card on Today, never as a pop up over other screens.
- A **Badges** page from You: earned badges in colour with dates, locked ones dim with what they need ("3,240 km to
  go").
- Tapping an earned badge opens it with **Share**, using the renderer from 4.
- Badge art: the v2 language (glow, dots, edge light), one shape per group. Needs mockups.

## 8. Build order

1. Section 1, sleep and naps. Small, and fixes data people see as wrong.
2. Section 2, HR zones. Small, self contained.
3. Section 3 step 1, the hardware test. Short, and decides the rest of 3.
4. Section 4, card and photo with route line, then map.
5. Section 6 base table, then streaks.
6. Section 7, badges.
7. Section 5, video, then replay.

## 9. Open decisions

- 2.3: past workouts follow new zones, or keep their own?
- 3.3: double tap only if the strap sends an event. Confirm the fallbacks are acceptable if it doesn't.
- 6.3: Workout streak with rest days, or weekly?
- 7.2: do deletes take badges back?
