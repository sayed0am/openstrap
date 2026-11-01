# Widgets: spec

Status: draft, 2026-10-24. Branch `widgets`. The mockups are the canvas
[OpenStrap widgets, final set](https://claude.ai/artifact/HfG4KPf8jM5nRHpBapK8zu); the explorations before it are in
[OpenStrap widgets](https://claude.ai/artifact/RqHGxrzzdh53FmydVGuVsM). Everything here follows the v2 design system
([../v2/spec.md](../v2/spec.md) section 2) unless it says otherwise.

## 1. Goal and limits

Home screen widgets that answer the questions people open the app for, without opening it: how did I sleep, am I
recovered, how far am I on steps, and start my workout.

- **New feature, after 2.0.** The v2 brief ruled out new features for 2.0. Widgets ship in a later release.
- **No new data.** Widgets read what the app already stores and computes. No algorithm or strap protocol changes.
- **No live numbers.** Nothing on a widget updates faster than the refresh rules in 7. Live heart rate stays in the
  workout notification.
- **Same look as the app.** Black, glow and edge light, dots for numbers, the v2 copy rules.

## 2. The set

Five widgets. Energy and Strap were explored and dropped (2.3).

| Widget | Answers | Why it earns a place |
|---|---|---|
| Moment | What matters right now | Follows the day like Today's hero. On launchers without stacks it is the stack. |
| Sleep | How did I sleep | The first thing people check in the morning, and the richest Large |
| Steps | How far am I today | Glanceable all day, the goal bar gives a reason to look again |
| Recovery | Should I train | The same answer in the same spot every day, which Moment can't promise |
| Start a workout | Start my usual | The one action widget: home screen to recording in one tap |

### 2.1 Every widget has the same three sizes

Stacks (Samsung One UI, iOS Smart Stack, and any launcher that adds them) give every widget in a stack the stack's
size. So each widget has a layout for each of the same three sizes, and they resize the same way.

| Size | Cells | Picked from | Drawn at in the mockups | Holds |
|---|---|---|---|---|
| Small | 2 × 2 | Any cell (the smallest) | 173 × 176 dp | A number and a word, or the one action |
| Wide | 4 × 2 | 280 × 160 dp | 358 × 176 dp | The Small plus one more thing: figures, a chart or a row of choices |
| Large | 4 × 3 | 280 × 260 dp | 358 × 272 dp | Everything the widget has |

- No 4 × 1. One row of text loses too much on Sleep and Start, and stacks of strips are rare.
- "Picked from" is the smallest cell each layout needs (`WidgetSizes`): Glance shows the largest that fits. Launcher
  cells run smaller than the mockups (found on the phone, 2026-10-25: One UI's 2 × 2 is 167 to 201 dp wide, its 4
  columns 353 dp), so every layout stretches to its cell and must fit at its minimum.
- Corners 28 dp, padding 20 dp on every side, as every card (spec 2.4). The system's widget corner radius is not used
  where the launcher allows it (6.3).

### 2.2 Tiers and light

Each widget keeps one look at every size, so a stack that rotates never changes a widget's tier. No two widgets share
a tier and edge side, so two placed side by side never repeat (the rule from v2: never two consecutive cards with the
same design). The launcher decides placement, so this is the only way the rule can hold.

| Widget | Tier | Light | Mood |
|---|---|---|---|
| Moment | Glow | The moment's light (spec 4.2) | The moment's mood |
| Sleep | Edge | Top, like a night sky | Night, always (as the Sleep tab) |
| Steps | Edge | Bottom, a value | The moment's mood |
| Recovery | Edge | Left, about earlier (this morning) | The moment's mood |
| Start a workout | Edge | Right, toward Start | The moment's mood |

Moods are spec 2.1: Day 100%, Evening 80%, Night 65% for glows and 80% for edge light. Hairlines stay at full
strength at night.

### 2.3 Dropped

- **Energy.** Moment leads with it at midday, in the workout window and in the evening. On its own it is a niche.
- **Strap.** Battery and sync matter only when something is wrong. Every widget carries the strap status button
  instead (5).
- **4 × 1 strips** of any widget (2.1).

## 3. Each widget

One dotted number per widget at every size (spec 2.3). Dot sizes: 3 dp on a Small, 3.6 dp on a Wide or Large, 4.5 dp
for Moment's number on a Wide or Large. Example values are the mockups'.

### 3.1 Moment

Uses `MomentResolver` exactly as Today does. The hero's colour, light angle and strength are spec 4.2's table.

| Size | Content |
|---|---|
| Small | The moment's name ("Midday") as every widget's title. Its number and word: "62", "Energy left" |
| Wide | The name, the hero sentence (Display 300 20, two lines at most), the number and word, one figure as Secondary on the right ("Drained 60, recharged 38") |
| Large | The Wide plus the moment's chart, solid, with times as Captions under it |

What each moment shows:

| Moment | Number and word | Sentence (Wide, Large) | Figure | Large chart |
|---|---|---|---|---|
| Waking up | Recovery, status word | Today's waking sentence | "Energy at waking 84" | The night's stage strip |
| Midday | Energy, "Energy left" | Today's midday sentence | "Drained 60, recharged 38" | Energy's line since waking |
| Workout window | Energy, "left" | "Ready for effort. You usually run around now." | None. Start button (44 dp, white) instead | Energy's line since waking |
| During a workout | None. The sport, "Recording since 17:42" | None | None | None |
| After a workout | Minutes since, "since your run" | Today's after sentence | Load today | The heart rate drop since |
| Evening | Energy, "left" | Today's evening sentence | "Bedtime in 1 h 10" | The day's heart rate |
| Night | Minutes to bedtime, "min to bedtime" | Today's night sentence | "Bedtime 22:40" | The day's heart rate |

- **Sentences** come from the same copy functions Today uses (`TodayCopy` and friends), never a second copy path.
- **During a workout** the widget doesn't try to be live. Tapping it opens the live screen.
- **The duplicate rule.** Moment never leads with a metric that has its own widget placed. It falls back to the
  moment's next lead:

  | Moment | Leads with | When that widget is placed |
  |---|---|---|
  | Waking up | Recovery | Last night: sleep score and word, "Slept 7 h 40", Sleep colour lit from above. With a Sleep widget placed too: Energy, as at midday |
  | Midday | Energy | No Energy widget exists, no change |
  | Workout window | Start | No change, the window's point is Start |
  | Evening, Night | A sentence | No change |

  "Placed" means `AppWidgetManager.getAppWidgetIds` returns at least one id for that provider. It can't tell which
  home screen page, so anywhere counts. A pure function `momentLead(moment, placed: Set<WidgetKind>)` decides it,
  next to `MomentResolver`.
- Decided in step 5 (2026-10-25):
  - A lead with nothing to show gives way to the moment's next one: no sleep last night leads with Energy from
    waking with Today's no-sleep sentence (as Today's hero, spec 4.3); waking with no Recovery value (still learning,
    no heart data) leads with last night, then Energy; during a workout with nothing recording, or after one with
    none to tell, it's midday's. Before the first night Energy waits: the name, then "After your first night".
  - Last night's sentence is the Sleep tab's ("7 h 40, your longest this week."), which already says how long you
    slept, so its figure is "Woke 3 times" (as the Sleep widget's Large), not "Slept 7 h 40".
  - The window's sentence is `windowTitle` and `window` without the Energy part ("Ready for effort. You usually run
    around now."), the number being Energy. Its Start (44 dp, white play) sits right of the number on the Wide and
    Large and beside it on the Small, and opens Ready with Today's usual sport (started by you over 28 days).
  - Evening and night have the evening hero's sentence (Today's night shows the same). Past your usual bedtime the
    night shows Energy left with "Bedtime 22:40"; with no usual bedtime known it shows no bedtime at all, never the
    22:00 fallback the resolver uses.
  - Counts move on by themselves ("38 min since your run", "30 min to bedtime", "Bedtime in 1 h 10"), so while one
    shows, the moment alarm also fires at the next five minute mark on the clock (and at bedtime), whichever comes
    first with the moment's end. That's at most 24 refreshes after a workout and about 40 in an evening and night.
    These count alarms never wake the phone (`RTC`, not `RTC_WAKEUP`): a count is only worth drawing while the screen
    is on, so a tick that falls while it's off waits until it comes on. Only the moment's end wakes it (decided
    2026-10-25).
  - During a workout, nothing live as the spec says, but the Wide and Large keep Start's Open pill in the top row,
    the sport's tile under it (44 to 72 dp) and its name in the hero's light type (20 to 28 sp), so a tall cell has
    no hole. The Small has the name, the tile, the sport and "Since 17:42" with the red dot.
  - Charts: Energy's line runs from waking to your usual bedtime (or now, when later), quarter hours, the times
    under it waking and bedtime. The heart rate drop runs from the workout's end to the last reading, the day's heart
    rate from its first reading; both on an axis fitted to the readings and resting. The stage strip is the Sleep
    tab's nap strip (shared geometry, `stageStripSegments`), bedtime to waking under it. All solid white lines ending
    in their dot, as the Steps widget's.
  - Nothing wraps but the sentence, which takes two lines at most (spec's own rule): 20 sp, then 18, then the dots
    at 3.6, then a shorter wording (one of its sentences, last night's without the week's comparison), then left out.
    Other texts have options and sizes: "min since your run", "min since", "min"; "Drained 60, recharged 38", then
    "Drained 60"; 13 sp then 11 for the name and figure, 15 then 13 for the word.
  - Spare height grows the parts first: the Small's dots from the mockup's 3.6 to 7 dp, the Wide's and Large's
    sentence to 22 and 24 sp, the line chart from 44 to 72 dp, the stage strip from 8 to 20 dp, the dots from 4.5 to
    7 dp, never at the cost of the number row's word, figure or Start. The rest is shared evenly between the blocks,
    half a gap above and below; the least gap is 4 dp so the minimum Wide keeps its sentence at 1.08.
  - The tap opens Today at its root on today: `EXTRA_MOMENT` with `"now"` (`MainActivity.MOMENT_NOW`), which
    release builds act on too. Any other value is still the debug build's forced moment.

### 3.2 Sleep

Last night's main sleep. Night mood always.

| Size | Content |
|---|---|
| Small | "Last night". Score in dots with its word ("82", "Good"). "7 h 40 asleep". The stage bar, 6 dp |
| Wide | Title row with bedtime to wake as its note ("22:58–06:56"). Score with "Sleep score, good", "7 h 40 asleep" on the right. Stage bar 8 dp with its legend ("Deep 1 h 32", "REM 1 h 41", "Light 4 h 27", "Awake 18 min", 8 dp marks) |
| Large | Title row with bedtime to wake. The Sleep tab's sentence ("7 h 40, your longest this week."). Score with "Sleep score, good" and "Woke 3 times" on the right. Then the time in each stage as rows, as the Sleep score detail lists them: the name, a bar for its share of the night in the stage's colour on a white 10% track, the time on the right ("Deep 1 h 32"). No hypnogram (the user's call, 2026-10-25). The rows replace the stage bar and legend; when they don't fit, the sentence goes first, then the rows give way to the stage bar |

- Same copy and numbers as the Sleep tab hero (spec 5.2): `SleepCopy`, the stage totals, the longest or shortest
  rule.
- Before the first night: the title, then "After your first night" in Secondary, the icon at 30% (spec 2.5 waiting
  cards). Before the night is synced in the morning: yesterday's night with "Last synced night" as the note.

### 3.3 Steps

| Size | Content |
|---|---|
| Small | "Steps". Steps today in dots, "today". The goal bar, 4 dp. "84% of your goal" |
| Wide | Title row with "Goal 10,000". Steps with "today", "1,568 to go" on the right. Today's steps so far as one solid line from waking to now, ending in the Steps card's end dot. Its top is the goal, so the line fills its height as the day reaches the goal. No goal bar: the line is the progress |
| Large | The Wide plus This week: "This week" and "Goal met 2 of 6 days" on one line (a large font drops "This week", the count never wraps), then a bar per day Monday to Sunday across the width. A met day is Steps lime, a missed day white at 30%, today white, days to come a 4 dp stub at 12% |

- Steps format with the thousands comma (`Metric.format`). Goal from settings.
- Past the goal: "Goal met" replaces "to go", the line reaches the top.
- When a large font leaves no room for the line, the goal bar shows instead.

### 3.4 Recovery

| Size | Content |
|---|---|
| Small | "Recovery". Score in dots, status word ("78", "Recovered") |
| Wide | Title row with "This morning". Score and word. Three figures in a row, Caption over Body: Resting HR "52 bpm", HRV "64 ms", Sleep "82" |
| Large | The Wide plus what the score means, as Today's Recovery card says it (`TodayCopy.recoveryMeaning`: "Recovered enough for your usual training.") and the last seven days as bars beside the score, today in Recovery's ink, the others white at 30% |

- Score, word and sentence from the Recovery tab (`RecoveryEngine`, its copy). Figures use `Metric.format`.
- Before the morning's score: yesterday's with "Yesterday" as the note. Before the first score ever: "After your
  first night", as Sleep.

### 3.5 Start a workout

| Size | Content |
|---|---|
| Small | The usual sport's tile (44 dp, 16 dp corners, Control), its name in Body 500 ("Outdoor run"), "Your usual" in Secondary, the white 44 dp play button |
| Wide | A row: tile, name, "Your usual, GPS on" with the 12 dp location mark, play button. Under it the next four sports by use as 38 dp tiles with Captions under them, then All sports |
| Large | The Wide plus a hairline and two figures, Caption over Body: This week "2 workouts, 1 h 35", Last run "Tuesday, 5.2 km" (the usual sport's last) |

- The usual sport and the next four come from `topSports` (ui/glow/GlowWorkoutMode.kt), with
  `Workouts.DEFAULT_SPORTS` before there are enough workouts. GPS on or off is the per sport setting from v2.
- While recording (decided 2026-10-25, replacing a row that left the card empty): the workout's active time is the
  hero, a live timer that ticks on its own (a RemoteViews `Chronometer`, h:mm:ss or mm:ss, light system sans in white,
  up to 40 sp on the Small, 48 on the Wide, 56 on the Large, stepping down to fit, sized for h:mm:ss from the start).
  Its base is the active time at load back from now, pauses left out as the live screen counts them. Paused, it
  stands still and the line reads "Paused". Open replaces play, and it and the whole widget open the live screen.
  - Small: the sport's tile and Open in the top row, then the timer, the sport's name (short name if needed), then
    "Since 11:58" when there's room.
  - Wide: the sport's tile, its name over a red dot and "Recording", and Open, then the timer with "Since 11:58"
    beside it, else under it, else left out.
  - Large: the Wide's, then the hairline and This week and the usual's last, secondary to the timer.
  - Start refreshes on a pause and a resume as well as on the start and end.
- No dotted number: this widget has none.
- Decided in step 4 (2026-10-25):
  - `Workouts.DEFAULT_SPORTS` is three sports and the Wide needs five, so `Workouts.EVERYDAY` pads the list after it.
  - A sport with two sides shows the side Ready will open on (your runs, last done on the treadmill, show
    "Treadmill"), each sport once.
  - Nothing on this widget wraps (the user's call, 2026-10-25): every text is one line. Captions are the picker's
    short names ("Walk" for an indoor walk), the full name only where the short one is already said ("Treadmill"
    beside your outdoor runs). The row is always the sports in their order of use, so every size shows the same row
    or its start: a caption that won't fit goes to 10 sp, the tiles get smaller, then the row has fewer columns. Only
    when not even two fit in order does a sport give its place to the next. A short Wide shrinks the tiles to 32 dp before losing a column, and always keeps the row.
  - The tile row spans the content edge to edge: the first tile under the usual's tile, All sports under play, even
    room between. Every caption is centred under its own tile, the first and last too, and may run past its tile
    into the gap and up to half the side padding, never into a neighbour's caption. At a large font in a short
    Wide the captions are the last thing to go, the tiles stay.
  - A name too long for its line becomes the short name, and the location mark, then ", GPS on", give way in the
    usual row.
  - Spare height grows the parts toward the mockup's sizes first (usual tile 48, play up to 52, sport tiles up to
    44, the Small's tile up to 56), then is shared evenly above, between and below the blocks, so a tall cell has no
    hole. On the Large the gap above and below is half of those between, as the padding already frames it. Each
    caption is drawn in a box as wide as its neighbours leave, never its estimated width. What fits is decided by its
    letters' widths plus 15%, so a tight one gives its place rather than being cut.
  - The Large's figures sit side by side when both values fit half the width, else a row each (Caption left, value
    right), else one under the other.
  - The GPS line reads "Your usual, GPS on" with the mark, "Your usual, GPS off" when location is off, and "Your
    usual" for a sport without GPS.
  - On a Small too narrow for the name beside play, play moves up beside the tile.
  - "Last run" is the usual sport's tile ("Last strength", "Last HIIT"), runs and treadmill runs together. Its value
    is the day and the distance, else the sets, else how long ("Yesterday, 35 min"), and "None yet" before any.
  - This week counts Monday to now as Your week does, without the strap's "Activity" records ("No workouts yet").

## 4. Taps

Every tap opens `MainActivity` with an existing extra. The only new ones are the sync action, opening the sport
picker and opening the Strap page (`EXTRA_OPEN_STRAP`, step 6).

| Where | Opens | Extra |
|---|---|---|
| Moment, anywhere | Today | `EXTRA_MOMENT` |
| Moment's Start (workout window) | Ready with the usual sport | `EXTRA_READY_SPORT` |
| Moment or Start while recording | The live screen | `EXTRA_SHOW_WORKOUT` |
| Sleep | The Sleep score detail for that night | `EXTRA_OPEN_METRIC`, `EXTRA_METRIC_DAY` |
| Steps | The Steps detail | `EXTRA_OPEN_METRIC` |
| Recovery | The Recovery detail | `EXTRA_OPEN_METRIC` |
| Start's play or a sport tile | Ready with that sport | `EXTRA_READY_SPORT` |
| Start's All sports | Choose a sport | New: an extra that opens Ready with the picker open (`readyPicking`). `EXTRA_SHOW_WORKOUT` opens Ready with the last sport, not the picker |
| The strap status button | Syncs in place, or the Strap page (5) | A Glance `actionRunCallback`, no activity, or `EXTRA_OPEN_STRAP` |

No carets on widgets: the whole widget is the target. Touch targets stay 44 dp or more.

## 5. The strap status line

Any widget, any size, shows the strap's status in its top row when one of these holds, first match wins:

| State | Words | Icon | Tap |
|---|---|---|---|
| Strap not paired | "Pair your strap" | The strap (Watch), white | Opens the Strap page (`EXTRA_OPEN_STRAP`) |
| Battery 15% or less, not charging | "Strap at 12%" | A low battery, amber | Opens the Strap page |
| Last sync more than 3 hours ago | "Synced 09:10", "Synced yesterday", "Synced Tuesday" within a week, then "Synced 13 Oct" | The sync arrows, amber | Syncs now, the app stays shut |
| That sync running | "Syncing" | An indeterminate spinner | None: a tap is the widget's own |

- Option C (decided 2026-10-25, replacing step 6's pill, which read long and thin in the title's place):
  - The title always stays ("Recovery", "Steps", "Last night", the moment's name). A round 32 dp button sits at
    the right end of the title row, its right edge on the content's: a circle in the Control surface Start's tiles
    wear, the state's 16 dp icon in it.
  - The tap reaches 44 dp, 6 dp past the circle each way. It's a transparent target laid over the card where the
    circle is, so the row counts only the circle's 32 dp.
  - Wide and Large: the state's words sit just left of the button, Secondary (13 sp) in amber (`MetricColors.warn`,
    amber being for the strap and sync only), 8 dp from it. They replace the row's note ("This morning", "Goal
    8,000", "22:58–06:56") while the status shows.
  - Small: the button alone. TalkBack reads the whole line on every size.
- One line, never wrapped. The words try shorter wordings in order, all at 13 sp, and if not even the shortest fits
  the button stands alone:
  - "Pair your strap", "Pair strap"
  - "Strap at 12%", "12%"
  - "Synced Wednesday", "Synced Wed", "Wednesday", "Wed"
  - "Synced yesterday", "Yesterday"
  - "Synced 09:10", "09:10"
  - "Synced 13 Oct", "13 Oct"
- Beside the button the title takes the first of its sizes that fits (17, 15, 13 sp, and Moment's name 13, 11), and
  may step down one size more only to make room for the words.
- Every layout counts the title row at the larger of the title's line and 32 dp while the status shows.
- Start a workout has no title row:
  - Wide and Large: while the status shows they get one, "Start a workout" with the button and words at the
    right, a block above the usual row in the spare height's share. Where the layout won't fit under it (the
    smallest cells at the largest font) the status drops.
  - Small (revised on the phone, 2026-10-25): laid out as the other Smalls. The top row has "Start a workout" at
    the left, or "Start" when that won't fit beside the button even at 13 sp (a 2 × 2), and the button at the top
    right. Then the usual's tile with play right beside it, then the name over "Your usual". The tile and play grow
    from 44 dp on a tall cell. Short of room "Your usual" goes, then the title (the button stays), then the tile and
    play shrink to 40 and 36 dp. The tile and play never go. Without a status the Small is as 3.5 has it.
- Last sync time is `SettingsStore.lastSync`. Battery and charging are `StrapManager`'s connection state, kept in
  settings so a widget can read them with the strap away. When a sync ends the widgets refresh (7).
- Decided in step 6 (2026-10-25), still holding:
  - `EXTRA_STRAP_STATE` is the debug strap preview, so the status opens the Strap page with a new
    `EXTRA_OPEN_STRAP`.
  - While a workout records, Start and Moment show no status: the recording is the news, and the live screen and
    its notification show the strap.
  - A stale sync needs no event to show: `WidgetUpdater` sets an alarm at the last sync plus 3 hours while a widget
    is placed.
  - Debug gallery: `--es strap notpaired|low|stale|yesterday|week|syncing|none|auto`.

## 6. Rendering

### 6.1 Glance

- Jetpack Glance (`androidx.glance:glance-appwidget`), one `GlanceAppWidget` and receiver per widget.
- `SizeMode.Responsive(setOf(Small, Wide, Large))` with the three layout sizes in 2.1. The system picks the largest
  that fits, so any cell size gets one of the three.
- Provider XML: `targetCellWidth="2" targetCellHeight="2"`, `minWidth`, `minHeight`, `minResizeWidth` and
  `minResizeHeight` 110 dp (two cells by the old formula, 70 × 2 − 30, which One UI still uses and which ignores
  `targetCell*`: anything larger places as 3 × 3), `maxResizeWidth` 480 and `maxResizeHeight` 400 dp,
  `resizeMode="horizontal|vertical"`, the same on all five.
- `widgetCategory="home_screen"`. Lock screen and keyguard are out of scope.

### 6.2 What Glance can't draw

RemoteViews has no blur, gradients beyond simple ones, shaders or custom canvases. So:

- **Light:** each widget's background (glow or edge light, at its mood) is a bitmap drawn with the same light code
  the app uses. Extract the pure parts of `GlowStyle` and `EdgeLight` (angle, level, strength, colours) into a
  function both Compose and an Android `Canvas` call, so a widget and its card can't drift apart.
- **Dots:** dotted numbers are bitmaps drawn by the same dot geometry as `DotMatrixText` (ui/glow/GlowParts.kt),
  shared the same way. Each bitmap carries a content description with the number and word.
- **Charts:** the Energy and Steps lines, the hypnogram, stage bars, week bars: bitmaps from the same code as their
  cards, sized to the layout.
- **Text:** Glance `Text` in the system sans, so it scales with the system font size and reads to TalkBack. Funnel
  is not used on widgets (decided 2026-10-24, 9.1). Sizes and weights still follow the v2 roles: card title 17
  Medium, hero sentence 20 Light, Body 15, Secondary 13 at 72%, Caption 11 Medium at 50%.
- Glance keeps at most ten children in a Row or Column and drops the rest with only a log line. Group
  children (a Column per block) and use padding rather than Spacers in repeated rows.
- Bitmaps are drawn at the widget's actual size and density from `LocalSize`, kept under the RemoteViews bitmap
  memory limit (screen width × height × 4 × 1.5 bytes for all of a widget's bitmaps together).

### 6.3 Corners

On Android 12 and later, use `system_app_widget_background_radius` only when it is smaller than 28 dp, so the widget
never looks rounder than the launcher's other widgets. Below Android 12 the background bitmap is clipped to 28 dp.

### 6.4 Previews

A `previewLayout` per widget for Android 12 and later, and generated previews (`setWidgetPreview`) on Android 15 and
later from the real layout with sample values, so the picker shows the real look. `description` per widget, one line,
in the copy rules.

Decided in step 6 (2026-10-25): the generated previews are Glance 1.2's `providePreview` with `WidgetSamples` (the
mockups' values through the widgets' own factories), sent by `WidgetUpdater.publishPreviews` once per app version
(the system rate-limits it, so a limited call is tried again on the next start). `previewLayout` is a Small in plain
views (the card and its light as a drawable, the number as light text, Steps' bar a `ProgressBar`, Sleep's stages
weighted views). `previewImage`, for launchers before Android 12, is a PNG of each Small rendered off the device
(dots included, Helvetica Neue standing in for Roboto).

### 6.5 Spare height (decided in step 6)

**Changed 2026-10-31, after the unfolded Fold.** Every widget's dotted number takes one size in a cell, the same for
every widget (`WidgetHero`): the largest dot whose height is within 36% of the content's height on a Small, 28% on a
Wide, 22% on a Large. A widget goes smaller only when the number and what sits beside it don't fit the width, or the
rest doesn't fit the height. Wide and Large are picked from 280 dp wide, so the unfolded Fold's 3 columns (288 dp)
get them rather than a stretched Small. Every widget reads its cell from the launcher at layout and lays out again on
each resize: One UI places a widget before telling it its size, and the session kept the empty size it started with.
The rest of this section was the rule before.

Every layout shares its spare height as Moment's and Start's do: half a gap above and below, a gap between blocks,
and on a tall cell the parts grow first so no gap passes 32 dp on One UI's cells. Recovery's Small has three blocks
(top row, score, word) and its dots grow from 3 to 7 dp; its Wide's and Large's dots grow from 3.6 to 6 and the
Large's sentence to 22 and 24 sp; the Large's week shows only while it fits beside the score. Steps' Small dots grow
from 3 to 7 dp and its line from 52 to 72 dp. Sleep's Small dots grow from 3 to 7 dp and its Wide's from 3.6 to 6. With the strap status showing (5) the title row counts
at the larger of its line and the button's 32 dp, and every size still fits at the minimums and One UI's cells at
font 1.0, 1.08 and 1.3, no gap over 32 dp on One UI.

## 7. Refresh

Glance updates are cheap when the app asks for them. The app asks when something on a widget changed, never on a
timer it doesn't need.

| Trigger | Updates |
|---|---|
| A history sync ends (`HistorySync`) | All five |
| A workout starts or ends (`WorkoutRecorder`, strap auto-detect) | Moment, Start |
| The moment changes | Moment. An exact alarm at the next boundary: add `MomentResolver.nextChange(...)` returning the time the current moment ends |
| Midnight | All five (new day, new goal bar) |
| Steps (`LiveSteps`) | Steps, at most every 15 minutes while the strap is connected. Moment shows no steps figure |
| A widget is added or removed | Moment (the duplicate rule) |
| Strap state changes (paired, battery crosses 15%, charging) | All five, for the status line |
| The last sync turns 3 hours old | All five, for the status line (an alarm, step 6) |
| Mood or settings change (goal, sport GPS) | The widgets that show them |

- `updatePeriodMillis="0"`. No periodic updates from the system.
- Exact alarms need `SCHEDULE_EXACT_ALARM`, which Android 14 and later deny by default, and `USE_EXACT_ALARM` is
  not meant for this. So moment boundaries use `setExactAndAllowWhileIdle` when `canScheduleExactAlarms()` is true
  and `setWindow` with a 5 minute window when not. A moment a few minutes late on a widget is fine. The app declares `SCHEDULE_EXACT_ALARM` for this (decided 2026-10-25), which adds an "Alarms and
  reminders" entry to the app's system info page.
- One `WidgetUpdater` in `device/` owns all of this. Everything else calls `WidgetUpdater.changed(kinds)`.

## 8. Copy

All of v2 section 7: no `·`, `;` or `—`, a real minus sign, `Metric.format` for every number, en dashes only in time
ranges. Widget picker names: "Moment", "Sleep", "Steps", "Recovery", "Start a workout". Descriptions:

- Moment: "What matters now, changing through your day"
- Sleep: "Last night's score, time asleep and stages"
- Steps: "Steps today and your goal"
- Recovery: "This morning's recovery and what's behind it"
- Start a workout: "Start your usual sport in one tap"

The punctuation test (v2 8.2) also scans `widget/` and the widget XML.

## 9. Open questions

1. **Fonts. Decided 2026-10-24: system sans.** It keeps font scaling and TalkBack, which bitmapped Funnel would
   lose. Dotted numbers are bitmaps and unaffected. Revisit only if it looks wrong on the phone in step 7.
2. **Moment's chart at Large in the workout window. Decided 2026-10-25 (step 5): Energy's line**, as 3.1's table
   has it. Revisit on the phone.
3. **Pixel Launcher stacks**: whether it has them on the user's phone changes nothing in this spec, but the stack
   check in 10 needs a launcher that has them (Samsung One UI, or a test launcher).
4. **Two Recovery widgets or two Moments**: allowed, both show the same. No rule needed unless the phone shows a
   problem.

## 10. Build

Each step is one or a few commits on `widgets`, with tests, installed over adb and checked on the phone with
screenshots, never during a running workout.

1. **Shared drawing:** extract the light, dot and chart drawing into functions Compose and `Canvas` both call. Today
   and the tabs must look the same before and after (screenshots).
2. **Plumbing:** Glance dependency, `WidgetUpdater`, `MomentResolver.nextChange`, strap state in settings, the sync
   callback. No widgets yet.
3. **Recovery widget**, all three sizes, first because it is the simplest. Proves sizes, light, dots and taps.
4. **Steps**, **Sleep**, **Start a workout**, in that order.
5. **Moment**, every moment at every size, and the duplicate rule.
6. **Status line**, previews, descriptions.
7. **Phone pass:** each widget at each size, each mood, in a stack where the launcher has them, side by side, with
   the system font at its largest, TalkBack on, strap away, no data yet.

### 10.1 Tests

- `momentLead`: every row of 3.1's duplicate table.
- `MomentResolver.nextChange`: every boundary in v2 4.1, overlaps, past midnight.
- Status line: each state, the order they win in, the 15% and 3 hour edges.
- Sizes: every provider declares the same three sizes and the same resize limits. Fails naming the provider.
- One dotted number: every widget layout at every size has at most one dot bitmap.
- Copy: the punctuation test over widget strings.
- Snapshot of each layout at each size with sample data, so changes to shared drawing show up.
