# Workouts, first release: spec

Status: draft, 2026-10-30. No branch yet. Mockups: the
[simple canvas](https://claude.ai/artifact/Tt8TBJwvpzLX2kb1x1Jwpb). The fuller flows kept for later are on the
[advanced canvas](https://claude.ai/artifact/JqHesboNdm9TDvrQ3AJnHE). Follows the v2 design system
([../v2/spec.md](../v2/spec.md) section 2) and its copy rules. Sharing, badges and heart rate zones build on
[../requests/spec.md](../requests/spec.md), which this spec changes in two places (section 9).

## 0. What this release is

One flow for every workout: **Choose, Ready, Live, Summary, Share**, plus one weekly goal with bests and badges.

The aim is that most workouts start in two taps, and that each kind of workout shows what matters for it (pace for a
run, the set and the rest for strength, the timer for HIIT, calm for yoga), without the user ever choosing a "kind".

| In this release (simple canvas) | Kept for later (advanced canvas) |
|---|---|
| Choose: your usual, two recent, one saved, All sports | Choose with every sport on the first screen |
| Ready: live heart rate, one status line, goal chips, Start | The Structure row and the options list on Ready |
| Run: two views, Pace and Map | The Big numbers view and the always on glance |
| Strength: sets, rest, optional reps and weight | Supersets and circuits |
| HIIT: timer presets. Run: distance or time reps | Interval builder for any sport, warm up and cool down blocks |
| One weekly goal, personal bests, badges | Daily streaks, rest days, streak at risk nudge, several streaks |
| Share as card or photo, with route or map | Video and route replay (requests spec section 5) |

Nothing from the right column comes back without asking.

## 1. The model (internal only)

Every workout is a **sport** plus a **structure** plus a **setting**. These words and the family names never appear
in the app. The user picks a sport and, at most, a goal chip. The app works out the rest.

| Family | Sports (Zepp type ids where they exist) | Structure | Setting | Live screen |
|---|---|---|---|---|
| Distance | Run, walk, hike, cycling, outdoor sides of each | Open or goal, intervals for run | Outdoor GPS, or the indoor side | Pace and Map (2.3) |
| Indoor cardio | Treadmill, elliptical, rower, indoor bike, stairs | Open or goal | Indoor | Heart (2.3) |
| Strength | `Strength.SPORTS` (24, 50, 52, 222, 226) | Sets | In place | Set, Log, Rest (2.4) |
| Intervals | HIIT (49), run intervals | Rounds | In place, or outdoor GPS | Timer (2.5) |
| Mind and body | Yoga, pilates, stretching, breathing, tai chi | Open or length | In place | Calm (2.6) |
| Games and sports | Football, basketball, tennis, padel and the rest | Free play, halves, quarters | Phone in a bag | Game (2.7) |

A new `Workouts.family(type)` maps every sport id to one family. Sports not listed fall into Indoor cardio if they
have an indoor side, else Games and sports. Unit tests cover every id in `SPORT_NAMES`.

## 2. Screens

Board names in brackets are the canvas boards.

### 2.1 Choose [ChooseV1]

Replaces the sport picker as the first screen when you start a workout (`GlowSportPickerScreen` stays behind
All sports).

- Top: "Start a workout", and one status line for the strap ("Strap ready, 72 bpm", or what's wrong and a button).
- **Your usual**: one glow card for the sport you started most in the last 28 days, with what you did last time
  ("Last time 5.2 km, Tuesday") and a **Start** button inside it. Start goes straight to Live with last time's
  settings, skipping Ready. Tapping the card elsewhere opens Ready.
- **Recent**: the next two sports from `Workouts.recentSports`, as small cards with last time's settings in one line.
  Tapping opens Ready.
- **Saved**: the most recent saved interval workout (2.5), if any, as one row.
- **All sports**: a button at the bottom that opens today's picker with search.
- New user with no workouts: no usual card. Recent shows Run, Walk and Strength, and All sports.

The tile and the widget keep starting the sport they start today (no Choose step).

### 2.2 Ready [ReadyRunV1, ReadyIndoorV1, ReadyMindV1, ReadyStrength, HiitReady, ReadyGame]

Same frame for every family: close, the sport name as a pill (tap to change sport), a settings icon, the big live
heart rate from the strap, one status line, the family's one choice, and a Start button named for the sport
("Start run").

Everything is filled from last time for that sport. Ready never asks a question it can answer itself.

| Family | One status line | The choice on Ready | Behind the settings icon |
|---|---|---|---|
| Distance | GPS: "Finding GPS, ±18 m" then "GPS ready" | Outdoor or the indoor side, then goal chips: Just run, 5 km, 30 min, More | Keep the route, audio cues, auto pause, ask to end |
| Indoor cardio | What the strap will do ("buzzes at the halfway mark and at your goal") | Goal chips: Just go, 20 min, 30 min, More | Ask to end, zone target |
| Strength | "The strap buzzes when rest is over" | Sets and rest steppers, end rest sooner below, Log reps and weight | Ask to end |
| Intervals (HIIT) | "The strap buzzes at every switch" | Presets: Tabata, 40 / 20, Every minute, Free. Warm up first | Countdown length |
| Mind and body | "Quiet strap and a dim screen. One soft buzz at the end." | Length chips: Open, 15, 30, 45, 60 min | None in this release |
| Games and sports | "Phone in your bag? That's fine." | Free play, Halves, Quarters, with length and break steppers | Ask to end when I stop |

**More** opens a sheet with the goal kinds: distance, time, heart rate zone, active kcal, and for run, intervals
(2.5). The chips shown are the two most used goals for that sport, so they learn.

GPS: Start is never blocked by GPS. Starting before a fix shows "Finding GPS" on Live and the route starts at the
first fix under 20 m.

Fix on the canvas: ReadyGame still says "Structure" as a label. It becomes no label, just the three chips.

### 2.3 Live: distance and indoor cardio [Run, Map, Main, Paused]

Common to every live screen: minimize, the sport, strap battery, the total time large, and the controls row
**Lap or Skip, Pause, Hold to end** at the bottom in the same place. Pause is the biggest. Ending always needs a hold.

- **Run, view 1, Pace**: moving time, current pace large, avg pace, a split bar per kilometre with the one under
  way, heart rate with its zone, then distance, climb and active kcal.
- **Run, view 2, Map**: the map with the route coloured by heart rate zone, a heart rate pill on top, GPS accuracy,
  route only and follow buttons, and below it distance, moving time, pace and the last split.
- Two views only, swiped. The page dots read "View 1 of 2".
- **Indoor cardio**: one view. Heart rate is the hero with the zone arc and the time in each zone, the target zone if
  one is set, then distance from steps, avg bpm and active kcal.
- **Paused**: time stops, "Paused for", heart rate and how far it dropped, avg pace and avg bpm, End workout and
  Resume. Ending opens the summary with Undo for a few seconds.

### 2.4 Live: strength [StrengthSet, StrengthLog, StrengthRest]

Builds on `Strength.kt` (sets, `Rest`, `restOver`).

- **In a set**: set N of M, the exercise name, time in this set, heart rate and zone, the sets done below with reps,
  weight, time and peak. One big button: **Set done**.
- **Log** (only when "Log reps and weight" is on): a card after Set done with reps and kg steppers, filled from the
  last set of that exercise. Skip or Save. Rest is already running underneath.
- **Rest**: time left, heart rate and how far it dropped, a falling heart line with the "end sooner" level, what's
  next, +30 s, and **Start set N**. The strap buzzes when rest ends (time up, or under the level for the hold time).
- Exercise name: picked once per exercise from a short list or typed, remembered per user. "Next exercise" is on the
  log card. Without logging, the name is optional and sets are just numbered.

### 2.5 Live: intervals [HiitWork, HiitRest, RunIntervals]

New. HIIT today is an open workout.

- **Presets**: Tabata (20 s on, 10 off, 8 rounds), 40 / 20 (10 rounds), Every minute (12 rounds, rest is what's left
  of the minute, the user taps Done), Free (no timer, heart rate only, laps mark rounds).
- **Work**: round N of M, what's next, the seconds left in Doto large on a work coloured card, heart rate and zone.
  Skip moves to rest.
- **Rest**: a different card (rest colour), the seconds left, heart rate and the drop since work ended, peak per round
  so far, +10 s, Skip rest.
- **Run intervals**: reps of a distance (400 m, 800 m, 1 km) or a time, with rest by time. Shows metres to go, rep
  pace against the goal pace, and past reps. Needs GPS, or the treadmill side counts time reps only.
- **Saved**: a run interval set up in More can be saved with a name ("Tuesday intervals"). Saved workouts appear on
  Choose (2.1) and in More. Up to 10.
- The strap buzzes at every switch: one buzz for rest, two for work, three for the end. Countdown beeps from the
  phone in the last 3 s if sound is on.

### 2.6 Live: mind and body [LiveMind]

Dark by design: time against the length, a soft heart line that shows it settling, Pause and Hold to end. The screen
dims to near black after 10 s and lights on a tap. No zone colours, no kcal on screen. One soft buzz at the end of
the length, and the workout keeps recording until you end it.

### 2.7 Live: games and sports [LiveGame]

Made for the phone in a bag. Period and time in it, heart rate and zone, an effort bar (1 to 5 from time in zones so
far), active kcal, peak bpm and hard efforts (spells of 10 s or more in zone 5). A **Half time** button starts the
break. The strap buzzes at the end of each period and break, so nothing needs the phone.

### 2.8 Summary [SummaryRun, SummaryIndoor, SummaryStrength, SummaryHiit, SummaryMind, SummaryGame]

Same top for every family: sport, when, a hero card with the one number that matters, three figures, then the
family's own section, then **Earned** (new bests, badges, the weekly goal line), then Share and Done.

| Family | Hero number | Own section |
|---|---|---|
| Distance | km | Route coloured by zone, climb, splits with the fastest marked |
| Indoor cardio | avg bpm | Time in zones, heart rate line, "vs last time" at the same effort |
| Strength | kg lifted (sum of reps × kg, only logged sets) | Sets per exercise with peaks, rest recovery against last week |
| Intervals | rounds done | Peak per round, drop in each rest, hardest round |
| Mind and body | minutes | Heart settled from X to Y, this week's days |
| Games and sports | minutes played | Each period, time in zones, a one line read ("You faded a little after 70 min") |

Strength without logging: the hero becomes sets, and kg lifted is not shown.

The weekly goal line is always last in Earned: "Weekly goal, 3 of 4", or "Weekly goal met, 4 of 4" on the workout
that meets it.

### 2.9 Share [ShareRun, ShareIndoor, ShareStrength, ShareHiit, ShareMind, ShareGame]

As the requests spec section 4: background Card or Photo (Video is shown disabled with "Coming later", or hidden,
decision in section 11), route Off, Line or Map for GPS workouts, size Story or Post, hide start and end, Save and
Share. Each family's card uses its hero number and its own small chart (splits, zones, rounds, the settling line).

### 2.10 While it runs, around the app [NowWhileLive, HomeLiveRun, HomeLiveRest, CapsuleStates]

When the live screen is minimized, the workout shows in two places: the Today hero (`DuringHero` in
`GlowTodayScreen.kt`) and the floating bar above the tab bar (`WorkoutCapsule` in `GlowWorkoutMode.kt`).

**Today, as built.** Both say the same thing, sport, time and bpm, one under the other. Both look the same for every
family, so a runner can't see distance or the goal, and someone resting between sets can't see the countdown. The
hero's one button (`WorkoutShortcut`) only opens the live screen.

**One source for both.** A new pure `LiveGlance` is worked out from the recording, the phase (2.4, 2.5) and the
goal. It holds what both places show, so they never disagree:

| Field | Meaning |
|---|---|
| `figure` | The one number that matters now: km, rest left, work seconds, minutes |
| `line1`, `line2` | Two short lines (see the table below) |
| `progress` | 0 to 1 and its kind: Goal (fills), Drain (empties, for rest), or none |
| `tint` | Zone colour, work red, rest green, yoga purple, paused grey, asking amber |
| `action` | The next thing you'd do: Pause, Set done, Start set N, Resume, Keep going |
| `second` | An optional second action: +30 s on rest, End when asking |

**The Today hero** (HomeLiveRun, HomeLiveRest)

- The glow card in `tint`, lit from below. Top line: sport and total time.
- `figure` in Doto, the only Doto number on the card.
- The progress bar with one line under it: "Goal 5 km, about 4 min to go", or for rest "118 bpm, down 28. Ends
  sooner once you stay under 110."
- Run: pace, bpm with its zone, active kcal as three figures. Strength rest: a "Next" box with the next set.
- Buttons: `second` or Pause as a dark round button, `action` as the white one ("Open run" while running, "Start set
  4" on rest). Tapping the card elsewhere opens the live screen.
- Below it, for a run, the last split as a small card in a different look (the no repeated look rule).
- Workouts without a goal show no bar. Mind and body shows minutes of the length and the settling line instead.

**The floating bar** (CapsuleStates)

Same size and place as today (64 dp, above the tab bar). It shows on every tab, and on Today only once the hero has
scrolled out of view, so the two are never on screen together.

| Moment | Line 1 | Line 2 | Right side |
|---|---|---|---|
| Run or distance, with a goal | "4.32 km, 5:42 /km" | "24:51, 0.68 km to go" | bpm in dots, Pause. Thin goal bar on the bottom edge |
| Indoor cardio | "Treadmill, 24:51" | zone name | bpm in dots, Pause |
| Strength, in a set | "Set 3 of 5, 0:42" | exercise and bpm | **Set done** |
| Strength, resting | "0:58 rest left" | bpm and the next set | **Set N**. The bar's fill empties in green |
| HIIT, work | seconds left in Doto, "Work, round 3 of 8" | what's next and bpm | Pause. The whole bar is work red |
| HIIT, rest | the same in rest green | | Skip rest |
| Mind and body | "Yoga, 23:40 of 45 min" | none | Pause, smaller. No glow, 56 dp, the settling line |
| Games | "1st half, 32:10" | zone and effort | Half time |
| Paused | "Paused for 1:12" | what's done so far | **Resume** |
| Stopped moving (`endPrompt`) | "Still going?" | "No movement for 3 min" | End and **Keep going** |

End from the bar opens the same confirm as the live screen. Where the bar shows Pause, it stays one tap, as today.
Where it shows another action, Pause is on the live screen and the Today hero.

The ongoing notification (`WorkoutNotification.kt`) can take the same `LiveGlance` lines later. Not in this release.

## 3. Rewards [GoalMoment, BadgeUnlocked, WeeklyRecap, AchievementsV1]

Replaces the daily streaks of requests spec section 6 for this release.

### 3.1 Weekly goal

- **Goal**: workouts per week, 1 to 14, default 3. Set in Goals (`Goals.workoutsPerWeek`) and from the pencil on
  the Goals and bests page.
- **What counts**: a workout of at least 10 minutes (`WorkoutSuggestions.MIN_MINUTES`) that you started, or an auto
  detected one you kept. One a day counts at most twice.
- **Week**: Monday to Sunday, or the locale's first day.
- **Weeks in a row**: consecutive weeks with the goal met. Missing days never matter, only the week.
- Changing the goal mid week applies to that week. Past weeks keep the goal they had.

### 3.2 Personal bests

Records with a date and the workout that set them. A new one replaces the old.

- Run (GPS only, never distance from steps): fastest 1 km, 5 km, 10 km and half marathon as the fastest stretch of
  that distance inside any run, longest run.
- Any workout: longest workout.
- Strength (logged sets only): heaviest set per exercise.
- Day: most steps.

### 3.3 Badges

The set from requests spec 7.1, with the streak row replaced by weekly goal badges: first goal met, then 4, 12, 26
and 52 weeks in a row. Plus one "first" badge per family (first Tabata, first strength session with logging and so
on). About 24 in all. Earned for past history on first launch, all shown on one screen once.

### 3.4 Celebrations

- The full screen **goal moment** shows once a week, after the summary of the workout that meets the goal.
- A **best or badge** shows on the summary under Earned. A full screen badge card shows only for a personal best,
  never for milestones.
- **Weekly recap** on Monday morning as one notification that opens the recap page. Off with one switch.
- No other notifications, no nudges in this release.
- **Celebrations settings**: weekly goal moment on or off, weekly recap on or off.

### 3.5 Goals and bests page

From You: this week against the goal with weeks in a row and the best run of weeks, personal bests, badges earned
(5 of 24) with the next locked one showing what it needs, and All badges.

## 4. Data

Room version 24 today. One migration for all of this.

| Change | Where | Why |
|---|---|---|
| `structure` text, null for open | `AppWorkout` | Intervals and game periods ("t:20/10x8", "r:400m/90x6", "p:2x2700/900") |
| `goal` widens `target` with distance ("d:5000") | `WorkoutTarget` | The 5 km chip |
| `exercise`, `reps`, `weightKg` nullable | `AppWorkoutSet` | Strength log |
| `rounds` table: workoutStart, index, workStart, workEnd, restEnd, peakBpm | new | Intervals and run reps |
| `saved_workout` table: id, name, sport, structure, goal, createdAt | new | Saved intervals |
| `exercise` table: name, lastReps, lastKg, usedAt | new | Exercise list and filling the log card |
| `personal_best` table: kind, value, date, workoutStart | new | Bests |
| `badge` table: id, earnedAt, workoutStart or day | new | Badges (requests spec 7.2) |
| `week_result` table: weekStart, goal, count, met | new | Weekly goal and weeks in a row |
| `Goals.workoutsPerWeek` | settings | The goal |
| Last settings per sport | settings, one JSON per sport | Ready filled from last time |

`week_result` and bests are recomputed for a week when a workout in it is added, trimmed or deleted. Badges already
earned stay (requests spec 7.2 decision applies).

Health Connect: unchanged, workouts still write as exercise sessions. Laps and rounds go as segments where Health
Connect has a matching type.

## 5. Strap

All buzzes go through one `WorkoutBuzz` with named patterns so the families stay consistent:

| Event | Pattern |
|---|---|
| Rest over, work starts | Two short |
| Work over, rest starts | One short |
| Halfway, period over | One long |
| Goal reached, workout length over | Three short |
| Mind and body end | One soft (shortest the strap does) |

Needs a hardware check that the patterns can be told apart on the wrist, with the phone in a bag. Same adb session
as the call buzz test (requests spec 3.3).

## 6. Code shape

- `GlowWorkoutMode.kt` is 1,771 lines. Split before adding: `ChooseScreen.kt`, `ReadyScreen.kt`, and one file per
  live family under `ui/glow/live/`, sharing `LiveFrame` (top bar, time, controls row).
- `WorkoutSession.kt` gains a small state machine for structured workouts (`Phase.Work`, `Phase.Rest`,
  `Phase.Period`, `Phase.Break`) driven by time, heart rate (`Strength.restOver`) and taps. Pure Kotlin, unit tested,
  so the same rules run with the screen off.
- Summary sections become one composable per family behind the shared summary frame in `GlowWorkoutSummary.kt`.
- Rewards are worked out in `data/Rewards.kt`, pure, from workouts and days, so they can be tested and rebuilt.

## 7. Copy

- The family, structure and setting words are internal. The UI says the sport, the goal and what will happen.
- Status lines say what the strap or phone will do, in one sentence.
- Numbers on a card use one Doto figure only (v2 rule). Two cards in a row never share a look.
- No ·, ; or dashes in UI text. Ranges read "130 to 148".

## 8. Done when

- Starting your usual workout from Choose takes two taps and uses last time's settings.
- Each family's live screen works with the screen off, with the right buzzes, checked on the strap.
- Strength rest ends on time or on heart rate, the log card fills from last time, and kg lifted adds up.
- Tabata runs 8 rounds of 20 and 10 with the strap buzzing each switch, and the summary shows per round peaks.
- A 6 × 400 m run counts reps from GPS and saves as "Tuesday intervals".
- The weekly goal counts right across a week boundary, a delete and a trim, and the goal moment shows once.
- Bests and badges are earned for past history on first launch.
- Share works for every family as card or photo.
- With the live screen minimized, the Today hero and the floating bar show the same `LiveGlance`, are never on
  screen together, and the bar's button is the next action for each moment in 2.10.

## 9. Changes to the requests spec

- Section 6 (daily streaks) moves to Later. The weekly goal here replaces it. `day_result` is not built now.
- Section 7: the streak badges become weekly goal badges (3.3).

## 10. Build order

1. Split `GlowWorkoutMode.kt` and add `LiveFrame` (no visible change).
2. `Workouts.family`, last settings per sport, Choose and the new Ready.
3. Run Pace and Map views, Paused, and `LiveGlance` with the new Today hero and floating bar for distance and indoor
   cardio.
4. Strength log and exercises, with their bar and hero moments.
5. Interval state machine, HIIT, then run intervals and saved workouts, with their bar and hero moments.
6. Mind and body, games.
7. Summaries per family.
8. Weekly goal, bests, badges, celebrations, Goals and bests page.
9. Share for every family (requests spec section 4).

Each step ships on its own behind nothing: the old screen stays for families not yet done.

## 11. Open decisions

- Does Start on the usual card skip Ready entirely, or show Ready for one second with a cancel?
- Default weekly goal: 3 or 4?
- Do auto detected workouts count toward the goal without being kept?
- Video on the share screen before section 5 lands: hidden, or shown as "Coming later"?
- Exercise list: ship a built in list of common lifts, or only what the user types?
- Floating bar during a strength set: Set done (as drawn), or keep Pause there and put Set done on the hero only?
