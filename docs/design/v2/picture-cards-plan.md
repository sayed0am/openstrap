# Picture cards: build plan

Build step 12 of [spec.md](spec.md) (8.1), the decisions of 2026-11-04 (2.10). Written 2026-11-04.

**Status, 2026-11-04 evening:** all four waves built and merged on `design/picture-cards`. The open decisions went with
the recommended answers, and the gates were waived by the user. Checked on the emulator with the Fold's data, folded
and unfolded: Today (evening), Recovery, Sleep, Activity and the picture gallery.

The work runs in four waves. Wave 0 and wave 3 are done by the coordinating session alone. Waves 1 and 2 split into
subagents that run side by side, each in its own git worktree on its own branch, each owning a fixed set of files that
no other agent in the same wave touches. That is what keeps merges free of conflicts, as in the fix waves of
2026-11-01.

## Ground rules for every agent

- **Branches.** The base is `design/picture-cards` (cut from `develop`). Each agent works on
  `pc/w<wave>-<name>` in a worktree cut from the base after the previous wave is merged into it. The coordinator
  merges each wave back into `design/picture-cards` before the next wave starts. Nothing is pushed and no PR is opened
  without asking the user.
- **One Gradle build at a time.** Every build and test run goes through `.claude/tools/gradle-queue.sh`, the
  machine-wide lock, the coordinator's included. Six builds at once once used 11 GB and the app had to be killed.
- **Own files only.** An agent edits only the files its brief lists. A change it needs elsewhere goes in its report
  as a request, and the coordinator makes it between waves.
- **Strings.** New user-facing text goes in the strings file the brief names, never in a shared one. No `·`, `;` or
  `—` in any string. Copy follows spec section 7.
- **Tests.** Each agent adds unit tests for the pure logic it writes and runs the whole unit suite before it reports.
  No algorithm test changes.
- **Report.** Branch, commits, tests run with their result, anything left undone, and requests for files it didn't
  own.

## Wave 0: the contracts (coordinator, alone)

Everything later waves build on, so the parallel agents share one API and never edit the same core file.

1. **Rename** `EdgeCard` to `PictureCard` (file, composable, `LocalEdgeOverride`, `EdgeStatus` to `OffStatus`) across
   the 20 files that call it. Mechanical, done first and alone so no later branch carries it.
2. **`PictureCard`** (`ui/glow/PictureCard.kt`): card surface #0A0A0B, hairline at 8%, no light when usual (L0). New
   parameters: `rim: RimPoint?` (where the off part of the picture sits, as a fraction of the card) and `tag: NewsTag?`.
   The L1 soft glow, the edge override's colour and edge, and the progress bar go. `plain` stays for strips.
3. **The rim renderer** (`ui/glow/draw/RimSpec.kt`, new): a 2 dp rim in the ink at 100%, 35% halfway out, fading
   over about 60% of the card's width from its point, with a 13% wash of the ink inside, scaled by the mood (Day 100%,
   Evening 90%, Night 80%). `edgePaint` in `LightSpec.kt` stays only for the widgets (see open decision 1).
4. **Tags** (`ui/glow/NewsTag.kt` and `ui/dashboard/Tags.kt`, new):
   - The pill: fully round, white at 12%, no border, Caption type in white, 8 dp after the unit, dropping under the
     number on a Small when it doesn't fit.
   - Pure logic, unit tested: `goalTag(...)` for the ladder (Personal best, Best this month, Double goal,
     "+2,140 past goal", "3 days in a row", Goal met) with the Sleep cap and the Load or Recovery cap for calories and
     active hours, `offTag(metric, status)` for the off words, and `newsTag(goal, off)` where the goal wins.
   - Strings in a new `strings_glow_tags.xml`.
5. **`ListRow`** (`GlowList.kt`): the status line under the name becomes the off tag when off, no rim on a row.
6. **Looks by picture** (`ui/dashboard/CardLooks.kt`): `CardLook` swaps `edge: Edge?` for `picture: PictureKind?`,
   and `sameDesign` compares tier, picture and shape. `PictureKind` lists every kind in spec 2.7. `Tier.Edge` becomes
   `Tier.Picture`.
7. **Picture slot.** A `Picture` interface each picture implements: draw at Small or Wide in an ink, and say its
   `rimPoint` for a given off status. A stub per kind, so wave 1 fills them in without touching shared files.
8. **Gallery.** A "Pictures" section in the debug `ComponentGalleryActivity` that lists every `PictureKind` at Small
   and Wide, usual and off, with tags, from a registry each picture file adds itself to. Wave 1 agents check their
   work here on the emulator without touching the gallery file.

Gate: screenshots of the gallery stubs, a rimmed card and the tag pills on the emulator, for the user to approve
before wave 1.

## Wave 1: pieces (four agents in parallel)

| Agent | Branch | Owns | Does |
|---|---|---|---|
| **1A Line pictures** | `pc/w1-lines` | `ui/glow/pictures/LinePictures.kt` and its test | Line through your band (heart rate), day strip calm to high (stress), dial (skin temperature), bed and wake bars (regularity), the day with the nap on it. Each with its `rimPoint` |
| **1B Level pictures** | `pc/w1-levels` | `ui/glow/pictures/LevelPictures.kt` and its test | Week of dots through your band (Recovery Small, breathing disturbances), level fill over your usual band (Load), countdown ring (time to recover), blocks (active minutes), fill bar and split bar (Recharged, Drained), week rings (Your week), age scale (Habit Age). Each with its `rimPoint` |
| **1C Data** | `pc/w1-data` | `ui/dashboard/DayViewModel.kt`, `ui/root/*`, `ui/glow/TabParts.kt`, `data/*`, `presentation/Bands.kt`, a new `ui/dashboard/OffStatus.kt`, and their tests | Everything in the data audit's "Missing, wave 1C builds it" table. No algorithm or score changes, only normals and series passed through |
| **1D Layout** | `pc/w1-layout` | `ui/dashboard/TodayPlan.kt`, `TodayLooks.kt`, `TabPlans.kt` and their tests | Today's grid one size down per moment (4.2), a pair never after a pair, new cards (Stress, Skin temperature, Breathing disturbances, Naps as Smalls), Start a workout gone at night. `varyLooks` reworked by picture kind. The tab plans with the ranked sizes (5.1 to 5.3). Tests: no two neighbours share a picture on any page, folded and on two columns |

1A and 1B draw from fake data in the gallery, so they don't wait for 1C. 1D is pure Kotlin. Model: 1A and 1B need
design judgement, 1C and 1D are mostly mechanical and can run on a cheaper model.

## Wave 2: screens (five agents in parallel)

Each wires `PictureCard`, the pictures, the tags and wave 1C's data into its screens, applies the colour rule
(pictures in the ink, dotted numbers white, status in Secondary) and the rim's point.

| Agent | Branch | Owns | Does |
|---|---|---|---|
| **2A Today** | `pc/w2-today` | `GlowTodayScreen.kt`, `TodayCards.kt`, `strings_glow_today.xml` | Every moment per 4.2, the After a workout hero in Recovery teal (270, rim at 80%), Steps' goal tag, the Heart rate and Stress Smalls, Your week rings |
| **2B Recovery** | `pc/w2-recovery` | `GlowRecoveryScreen.kt`, `GlowHealthspan.kt`, `GlowHealthspanScreen.kt`, `strings_glow_recovery.xml`, `strings_glow_healthspan.xml` | 5.1: Skin temperature's dial Wide, Body signals without HRV and resting heart rate, Recharged and Drained plain, Habit Age's age scale with Older or Younger |
| **2C Sleep** | `pc/w2-sleep` | `GlowSleepScreen.kt`, `dashboard/SleepPanels.kt`, `strings_glow_sleep.xml` | 5.2: regularity's bed and wake bars rimmed at the off nights, the breathing disturbances and Naps Smalls |
| **2D Activity** | `pc/w2-activity` | `GlowActivityScreen.kt`, `GoalsAndBests.kt`, `ActivityDetail.kt`, `strings_glow_activity.xml`, `strings_glow_goals.xml` | 5.3: Active minutes and Heart points Smalls, Load's level fill rimmed past your usual, Heart rate's line, the goal ladder on Steps |
| **2E The rest** | `pc/w2-rest` | `SummarySections.kt`, `GlowWorkout*.kt`, `live/*`, `ChooseScreen.kt` | The other `PictureCard` users: unlit, numbers white, no tags. Check nothing there still relied on the edge light |

## Wave 3: together (coordinator, alone)

1. Merge wave 2, run the whole unit suite and the design token tests through the queue.
2. The spec 8.2 tests not already written by an agent: the rim on every off card and none on a usual card, list row
   or strip, and the picture neighbour test over every page.
3. Remove what 8.3 and 2.10 drop: `EdgeSpec.kt`'s edges, `SoftGlow`, `EdgeNeutral`, `Hue.Neutral` light.
4. Emulator check with the Fold's database copy: every Today moment, the three tabs, folded and unfolded, against
   `mockups/grid-canvas.html`. Before and after screenshots.
5. Gate: the screenshots to the user, then a PR into `develop` only on their go.

## Data audit

What each picture and tag needs, checked in code on 2026-11-04. A normal today comes from `rememberNormal`
(`ui/glow/TabParts.kt`) over `History.normal`: mean ± 1 SD of 30 days, at least 7. Only the `DailyMetric` metrics have
a daily history (heart rate, resting, steps, HRV, blood oxygen, stress, skin temperature, breathing).

**Already in the UI, wave 2 only wires it:**

| Picture | Source |
|---|---|
| Heart rate line through your band | `DayUiState.heartRate`, `TodayContext.restingRange` |
| Skin temperature dial | `sleepSkinTemperature`, `rememberNormal(SkinTemperature)` |
| Bed and wake bars | `sleepWeek(...)` in `SleepPanels.kt`, `SleepHistory.usualTimes` |
| Load level fill, its band | `RecoveryResult.load`, `LoadBands.usualRange` (±25%) |
| Time to recover ring | `latestRecoveryEstimate`, `TodayCopy.recoverLeft` |
| Active minutes blocks | `activity.weekEquivalent` against `MVPA_WEEKLY_TARGET` |
| Naps day | `DayUiState.naps`, `napStages` |
| Drained split | `ChargeDay.drainedExercise`, `drainedStress`, `drainedLiving` |
| Recovery week of dots | `RecoveryResult.readinessTrend` (7 days) |
| Habit Age scale | `Healthspan.Outcome` |
| Your week rings | Steps' `DailyHistory` and `LocalGoals.current.steps` |
| Last night stage bar | `sleepStages`, `sleepBreakdown` |

**Missing, wave 1C builds it from data the app already stores:**

| Need | How |
|---|---|
| One off status per metric | Today it's scattered: `History.status` (-1, 0, 1) for body signals, `LoadBands.of` inline at three sites (two drop Below), breathing in text only. One `offStatus(metric, value, normal)` returning `OffStatus` |
| Stress day strip | Hourly buckets from `DayUiState.stress` samples, against `rememberNormal(Stress)` |
| Breathing disturbances week | Per-night `Recovery.Night.odi` (or `osa_result` rows) passed to the UI. The usual is one median today, so the band is median ± the nights' spread |
| Recovery's usual | Nothing exists. Mean ± 1 SD of the score over 30 days, from the engine's `readinessOf` over its nights |
| Last night Short or Long | No sleep duration normal. `History.normal` over `nights[].asleepSeconds` |
| Recharged's start | Energy at bedtime from `charge.points` at `tonight.start` |
| Steps' goal tags | Personal best from `personal_best` `most_steps` (reaches only the rewards page today), best of the last 30 days from Steps' `DailyHistory`, the daily goal streak from the same history against the goal |

**Missing with no source, left out of this build** (open decision 2):

| Need | Why |
|---|---|
| Heart points by zone | Only the strap's daily and 7-day totals are stored. Zone time exists per workout only |
| VO2 max Up or Down | No history of the estimate, one weekly value |
| Calories and active hours: Personal best, Best this month, streak | No bests for them, and active hours per day aren't stored. They get Goal met and "+N past goal" only |
| Weekly workouts: Best this month | Weekly, so no 30-day best. Keeps the existing "N weeks in a row" |

## Open decisions

1. **Widgets** draw the edge light too (`WidgetParts.kt`). The spec says nothing about them. Recommended: leave them
   as they are in this build and redo them as their own step.
2. **Data with no source.** Recommended: ship without it. VO2 max gets no Up or Down tag, calories and active hours
   climb only to "+N past goal", and those rungs come back when a history exists.
3. **Heart points' picture.** The spec gives it the zone bar, but no zone split of heart points is stored.
   Recommended: draw its last 7 days as day bars in heart red until the split exists, which also keeps it apart from
   Active minutes' blocks beside it.
