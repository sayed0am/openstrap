# Design v2: spec

Step 5 of the [brief](brief.md). This is what step 6 builds: rules and components that a Compose implementation can
follow without guessing. Where this spec and an older doc disagree, this spec wins. Section 2 is the design system,
shown with live examples on the [design system page](design-system.html). The [audit](audit.md) checks the build
against it.

The pattern board, critique, inventory, research, step 10 review and the screen mockups that led here were removed on
2026-10-24 once this spec and the design system page replaced them. They are in git history before that date.

Non-goals, unchanged from the brief: no new features, no changes to how any score is calculated, no strap protocol
changes, existing deep links keep working.

---

## 1. Principles

1. **Meaning first.** Every card leads with one sentence or one number that answers its question. Method, formula and
   caveats sit one tap away (info sheet).
2. **The screen follows the day.** Today has one hero slot whose content changes by moment. Other cards keep a stable
   set and change order only when the moment changes.
3. **Light means something.** Glow, the rim and tags each carry a meaning, and colour says which metric it is.
   Nothing glows, moves or is lit only to decorate.
4. **One home per thing, one name per thing.** No metric appears twice on one screen. No name means two things.
5. **Nothing moves when idle.** Only live data moves (the live heart rate, the live workout).

---

## 2. Foundation: the design system

Rewritten 2026-10-24 after a pass on the phone found the build, the old mockups and this section disagreeing. The
[design system page](design-system.html) ([published copy](https://claude.ai/artifact/Q4FAqeaosZHGKA3DvGzGV3)) shows
every rule here with live examples. The page and this section say the same thing. Where the code disagrees with them,
they win. 2.9 and 2.10 list what changed. Where the page and this section disagree after 2026-11-04, this
section wins (2.10).

### 2.1 Light levels

Light is the loudest thing in the app, so it comes in fixed levels. A card takes its level from its tier and from how
its value sits against your normal. Nothing is lit between levels, and nothing glows only to decorate.

| Level | Tier | At the lit point | Reaches | Used for |
|---|---|---|---|---|
| L4 Hero | Glow card | The light colour at 100% | Melts into the body by 45% of the card | The hero of the screen, one per screen |
| L3 Second | Glow card | The light colour at 70% | 28% of the card, rim at 70% | A second glow that isn't a level. At most one more glow per screen |
| L2 Rim | Picture card | A 2 dp rim in the ink at 100%, 35% halfway out | The rim fades out over about 60% of the card's width from its point, with a wash of the ink at 13% inside | A picture card off your normal |
| L0 Quiet | Picture card, strip, list | None | None | Every card, strip and list with nothing off. A list's metric shows in its row icon |

L1, the soft edge glow on every usual card and strip (2026-10-24), is gone: light on every card said nothing (2.10).

- **Off your normal is light and a word, never a new colour.** A picture card goes from L0 to L2: the rim lights next
  to the part of its picture that is off (the late nights on Regularity's bars, the top of Skin temperature's dial,
  the tail of Heart rate's line), and a tag says what (2.5, Tags). The number stays white, the hairline, tier and hue
  never change. A rim never shows on a usual card, a list or a strip.
- **Text sits in the dark.** White text keeps at least 4.5:1 against what's behind it. A glow card's body colour may
  reach a relative luminance of 0.18 (`GlowPalette.MID_MAX_LUMINANCE`, 0.09 in the build, which makes every glow
  card muddy). The band round the core stays a step darker.
- **The fill eases linearly.** On L4 the light colour holds for the first part of the card and reaches the body at 45%,
  matching the mockup's `linear-gradient(light 0%, body 42%)` plus a 36 dp inset rim. The build's cubic ease-out
  drops to the body almost at once.
- **Locked on every glow card:** 28 dp corners, grain, the 1 dp hairline with its gloss, the black the core fades to,
  text top left. **Free per card:** hue, where the light comes from (`lightAngle`), how high it reaches (`lightLevel`).

**Levels.** A glow card that shows a level (Energy, steps toward a goal on a glow) shows it as how far the light
reaches. A picture card shows a level in its picture (Load's fill over your usual band, Active minutes' blocks), never
as light.

- On a glow card the light rises from the bottom, like a battery, on a Tall, a hero and every other level glow, Steps
  on Activity included, because its bars sit in the dark above the light. Only a short wide card with just a number
  and a line (Energy on Today) fills across from the left. The lit edge is the light colour at full, the lit part is
  the value's share of the card, then a soft 22 dp step into the dark core. At 0 the bottom 12% still glows.
- A level glow is always this bright, because its light is the value. Beside a hero (Energy on Recovery, Energy as the
  wide card under the hero on Today, in every moment that has it) it still counts as the screen's second glow, so no
  third glow joins it. L3 is for a second glow that isn't a level. Energy is always a level, wherever it shows.
- When the value changes after a sync, the light moves from the old level to the new over 600 ms with the number (3.5).

**Moods** multiply every card's light on a screen (`GlowMood`):

| Mood | When | Glow | Rim and pictures | Also |
|---|---|---|---|---|
| Day | Default | 100% | 100% | |
| Evening | Evening moment | 80% | 90% | Tone band off |
| Night | Night moment, and the Sleep tab always | 65% | 80% | Light from below only, hairlines at full strength |

The build's 60% and 40% left the Sleep tab nearly black.

### 2.2 Colour

Each metric owns one **ink** colour (`MetricColors`). **Colour says which metric it is, never how it's going.** The
ink draws the metric's picture and chart marks on every picture card, usual or not, its row icon, and its rim when off
your normal. It never colours a value or a status: dotted numbers and row values are always white, status lines are
Secondary, and news is a tag (2.5, Tags). On a glow card the glow is the colour, so its marks stay white. The glow
tones are worked out from the ink by one formula, so every card of a metric reads as the same light:

- **Light** (rim, lit edge): the ink's hue, 90% of its saturation, lightness 56%.
- **Body** (the card behind the text): hue, 80% saturation, lightness 16%, then darkened only if it passes the 0.18
  luminance cap.
- **Core**: hue, 70% saturation, lightness 3%.

| Metric | Ink | Metric | Ink |
|---|---|---|---|
| Recovery | `readiness` #7CF2C9 | Heart rate, resting, lowest, average | `heart` #FF5A5F |
| Energy | `charge` Ember #F66C28 | Steps, Activity, Active minutes, Distance | `steps` #C3F53C |
| Calories | `calories` Gold #FFD23F, `caloriesShade` #8C7314 for walking in its bars | | |
| Sleep, last night, naps, regularity | `sleepDeep` #4C6FFF, stages `sleepLight` `sleepRem` `sleepAwake` | HRV | `hrv` #2DD4BF |
| Blood oxygen | `spo2` #38BDF8 | Stress | `stress` #F062C0 |
| Skin temperature | `temperature` #FF8A3D | Breathing rate, Breathing disturbances | `sleepLight` #8FA8FF |
| Load, Time to recover | `load` #A78BFA | Habit Age | `habitAge` #D86BEA |
| Heart points, VO2 max | `heart` | Evening hero (a moment, not a metric) | `evening` #A274F2 |

- **Energy is one colour.** Ember orange on every surface. Its level shows as the light's height, never as a hue, so
  `chargeScale`, `chargeAt` and `chargeDeep` go. The old gold `charge` #FFD166 becomes #F66C28.
- **Recovery is one teal.** `readinessDeep` goes, and so does the band hue (teal, amber, coral): the word says the band.
- **No neutral light.** Strips, Your week and Workouts are unlit (L0). Your week draws its day rings in Steps' lime,
  a full ring for a day at goal.
- **Green and amber** (`good`, `warn`) are for the strap and sync only, never a health value. `bad` goes.

Surfaces and greys:

| Token | Value | Use |
|---|---|---|
| Page | #000000 | Every screen |
| Card | #0A0A0B | Picture cards and strips |
| List | #0F0F11 | `ListPanel` |
| Sheet | #141416 | Bottom sheets, the tab bar pill |
| Control | #1F1F22 | Round buttons, chips, pills, the avatar |
| Text | White at 100, 72, 50, 30% | Primary, secondary, caption, waiting |
| Lines | White at 8% card hairline and chart grid, 6% row divider | |

### 2.3 Type

Funnel Display for display roles and Funnel Sans for the rest, the faces the app ships (`Theme.kt`). Every text in the
app uses one of these roles, through `GlowType`. Material typography is not used on any v2 screen.

| Role | Face | Size and line | Colour | Where |
|---|---|---|---|---|
| Page title | Display 400 | 24 on 28 | White | You, Strap, every detail and settings page |
| Header date | Display 500 | 20 on 24 | White | `MomentHeader`, with the moment over it in Sans 500 13 at 60% |
| Card title | Sans 500 | 17 on 22 | White | Every card on every tier, hero included, and list titles |
| Hero sentence | Display 300 | 20 on 24.4, tracking -0.01 em | White | Hero only, three lines at most |
| Body | Sans 400 | 15 on 20 | White | Row names, buttons, a sentence on a picture card, the word after a number |
| Secondary | Sans 400 | 13 on 18 | 72% | Status lines, notes, a title row's note |
| Caption | Sans 500 | 11 on 14, tabular | 50% | Axes, times, tags |

- The card title is the same on a glow card and the picture card beside it. The build draws glow titles at 20 to 26
  Light and edge titles at 15 Medium, which is the mismatch on Recovery (Energy beside Recharged).
- The word after a number ("bpm", "asleep", "today") sits on the digits' baseline in Body at 72%.

**Dots are for numbers.** The main number of a card, list values and live numbers are dot-matrix digits
(`DotMatrixText`). Nothing else in the app is drawn from dots (2.7).

**Up and down.** A change has three parts, each with one carrier:

| Part | Carried by | Example |
|---|---|---|
| Which way, against your normal | The picture, and the rim lit next to the part that is off (2.1) | Skin temperature's dial past its normal, rim at the top |
| How much | A signed number with what it's compared with | "+0.6 °C vs your normal", "−3 vs yesterday" |
| Whether it matters | The tag next to the number when off your normal, none when usual (2.5, Tags) | "Above normal" |
| Over time | The chart's line against the band of your normal (2.7) | A detail's week view |

No arrows, triangles or dotted arrows anywhere: an arrow says up but not whether up is good (resting heart rate up
is bad news, HRV up is good). No red or green for a change. Always say against what ("vs yesterday", "vs your
normal", "vs last week"), never a bare "+3". A real minus sign (−), never a hyphen: `TabCopy.signed` and the Recovery
contributor deltas use "-" today. No change reads "Same as yesterday", never "+0". A change from your normal (skin
temperature) goes through `Metric.formatChange` on every surface. One format per metric everywhere, `Metric.format`:
steps with the thousands comma ("8,432", the dot font has the comma), breathing rate to one decimal ("14.6").
Breathing disturbances are "an hour" on the Wide and the Small.

**One dotted number per card.** A card has at most one number in dots, its answer. Times, ranges, totals and second
figures are text in Body or Secondary. List rows never use dots: a row's value is plain Row value text (Sans 500,
20 on 24, tabular), and a Small that breaks down the card beside it (Recharged, Drained) is plain Display 300, 34.
This holds everywhere: the sleep hero (5.2), the Sleep
score detail, the workout pages (6.3). Six dot sizes, the gap between dots 0.38 of the
dot:

| Dot | Where |
|---|---|
| 2.4 dp | Four or more figures side by side (`FigureRow`) |
| 3 dp | Picture cards, Smalls |
| 3.6 dp | Talls, second glow, glow cards that aren't the hero |
| 4.5 dp | The hero's number |
| 6 dp | Detail header |
| 9 dp | Live workout |

### 2.4 Shape, space and grid

| Token | Value |
|---|---|
| Card corner | 28 dp on every tier, and the top corners of sheets |
| Tile corner | 16 dp: sport tiles, chart pills, text fields |
| Pills, chips, round buttons | Fully round |
| Card padding | 20 dp on every side, every tier |
| List row | 14 dp top and bottom, 20 at the sides (from the panel) |
| Space scale | 4, 8, 12, 16, 20, 24, 32 |
| Inside a card | Title row to content 12, digits to their caption 4, block to block 16 |
| Between cards | 12 dp gap, 16 dp side gutter |

Two columns, unit width = (screen width minus 32 minus 12) / 2, 173 dp on a 390 dp phone.

| Size | Shape | Use |
|---|---|---|
| Large | Full width, as high as its content | The hero of the moment, with no empty band under it |
| Wide | 2x1 | A number with its picture: a line, bars, a strip, a scale or a dial at the side |
| Small | 1x1 | A small picture, a number, and a tag or status word |
| Tall | 1x2 | Energy as a battery, when it sits beside Smalls |
| Strip | Full width, 68 dp | A state or an action (check-in, Start a workout on Today, last night collapsed) |

Rows are one of: `Full(Large | Wide | Strip | List)`, `Pair(Small, Small)`, `TallPair(Tall, Small over Small)`. A lone
Small becomes a Wide. Cards never resize by user choice. Every Small draws a small version of its metric's picture
(2.7). Smalls and Wides are a unit high (160 dp), Talls two units plus the gap.

**A card's size follows how much it has to show.** Each signal is scored on four things its data has (a shape over the
day or night, a normal to compare with, a history, parts that make it up, each 0 to 2) and how much it changes your
day (0 to 3). On its tab the most detailed take Large (Recovery, Last night, Steps), then Tall (Energy), Wide (Load,
Stress, Habit Age, Heart rate, Regularity, Skin temperature as a short Wide with its dial), Small (Breathing
disturbances, Heart points, Time to recover, Active minutes, Naps, Recharged, Drained), and the rest are list rows
(HRV, resting heart rate, breathing rate, blood oxygen, calories, VO2 max, distance).

**Today is a grid one size down.** A card on Today shows one size smaller than on its tab (Steps a Wide, Heart rate,
Load and Stress Smalls), so Smalls pair up. Order on every page: the subject, what explains it (a list), cards by
weight with Smalls in pairs, then things about the last weeks.

**Unfolded** (600 dp and up, the Fold's inner screen): two columns with a 12 dp gap and a 24 dp gutter, content
centred and at most 960 dp wide. Type and dot sizes stay the same, and cards only get wider, never taller. The hero and
strips span both columns. Each grid row is one block, so a Pair or TallPair stays together in one column. A Small is
never narrower than on a folded phone (176 dp): where a column can't hold two that wide (the Fold's inner screen), a
Pair or TallPair spans both columns as a row of its own, and the cards around it keep their columns. The two
columns are balanced by height: when one would end well below the other, its last block (Habit Age, Your week, as a
rule the last optional card) moves to the foot of the shorter one. The tab bar stays centred over the content, with
its fade (3.2).

From 720 dp a detail opens in a right pane beside its tab, the tab narrowed to a phone's width on the left, and back
closes the pane (list and detail). The You and Strap pages work the same way from 600 dp: a row (Profile, Diagnostics;
Alarms, Phone alerts, Health alerts, Firmware, Zepp account) opens its page in a right pane beside the list instead of
over it, with the row marked while its page is open. Another row replaces the pane, pages opened from the pane stack in
it, and back closes the pane or goes back within it. Rows that open a sheet (Goals, Sleep goal) still open a sheet.
Folded, each opens a page of its own as before.

Any other page over a tab (Profile from a card, Ready, Choose a sport, Add a workout, the calm down page, All workouts,
Habit Age or a detail when not in the right pane) takes one centred column of at most 600 dp, its pinned button as wide
as its content. A sheet is at most 600 dp wide and centred.

### 2.5 The card tiers

Every card on every screen is exactly one of these.

| Tier | Component | Light | What it is for | Per screen |
|---|---|---|---|---|
| **Glow card** | `GlowCard` | L4 or L3 | The main card of the screen | 1, plus at most 1 more |
| **Picture card** | `PictureCard` (was `EdgeCard`) | L0, or L2 when off your normal | Every metric with a shape of its own: last night, heart rate, steps, load, stress, regularity, skin temperature, Habit Age and the Smalls | Any |
| **Grouped list** | `ListPanel` + `ListRow` | L0 | Small facts and lists of things | Usually 1, at most 2 |
| **Strip** | `PictureCard`, 68 dp | L0 | A state or an action | Any |

`DotPanel` and `DotHeader` are gone from every screen, pages under a tab included (Habit Age, All workouts).

**Never two cards in a row with the same design.** A card's design is its tier, its picture (2.7) and its shape: a
Small and a Tall each count as a design of their own (`CardLook.shape`), so a Small never matches a Wide. Colour
doesn't count: two glows in a row, or two cards drawing the same kind of picture, look the same whatever their colours.
A level glow (light filling from an edge, as Energy) differs from a hero's glow: Energy as the second glow sits right
under the hero. **Each metric draws its own picture**, and no picture kind is used by two cards that can be
neighbours: Heart rate and Stress both move through the day, so one is a line and the other a strip, and Your week
draws rings because Steps beside it draws bars. Neighbours are cards one after the other in reading order, the two
Smalls of a Pair side by side, a Tall with each of its Smalls, every card of a row with the row over and under it, and
on two columns also cards side by side or one over the other. On two columns a full-width card touches the top card of
each column under it and the last of each column over it, on Today as on the tabs. Two Wides never follow each other
on a tab: a list sits between them. A Pair never follows a Pair on Today: a Wide, a strip or the second glow sits
between them. When two neighbours would still look the same, the later one takes another tier the spec gives it (Last
night as a strip, the second glow as its picture card). Only then does a card move, and only one whose place is open:
a pin, Your week or Habit Age, to the nearest place it fits, never splitting a Small pair. `varyLooks` applies this to
Today after Edit Today's pins and hides.

**Light means something.** Glow is kept for the hero and the second glow, the rim for a card off your normal. Every
other card, strip and list is unlit: a dark panel with a hairline. An unlit strip and an unlit card read apart by
height (`Shape.Strip`), so they may follow each other.

**The moving border.** Start a workout, on Today and on Activity, is the one action the screens offer, so it has the
one moving light: a heart-red light travelling round its border once every 7 s, brightest at its head and fading
along its tail, with a soft halo (`movingBorder`). It holds still while the list scrolls or the page is off screen,
as the glows do, and rests at the top with the system's animations off.

#### Glow card

The recipe stays: lit fill, rim light, second-tone band, dark core, grain, hairline. Its light is L4 or L3 (2.1).

- `GlowMotion` is `Heartbeat` (live heart rate only) or `Still`.
- `GlowStyle.lightAngle` (degrees, counterclockwise from the right edge) says where the light comes from: 270 below,
  90 above, 225 lower left, 45 upper right, 315 lower right. The core sits on the opposite side.
- `GlowStyle.lightLevel` (0..1) is the height the light reaches (2.1, Levels).
- `GlowStyle.second` draws L3.
- The default radius becomes 28 dp (48 in the build) and the default padding 20 dp (30 by 32 by 26 in the build).

#### Picture card

Card surface #0A0A0B, 28 dp corners, a 1 dp hairline at 8% white. Inside, top to bottom: the title row, the metric's
picture in its ink (2.7), the number in white dots with its unit, then a tag when there's news and a status line in
Secondary. A Wide may put the picture at the side (Skin temperature's dial) or at the foot (Steps' bars).

| Case | Light |
|---|---|
| Usual value | None, L0. The picture in the ink, no tag |
| Off your normal, either way | L2: the rim lit next to the part of the picture that is off, and an off tag |
| A goal met | None. The goal tag (Tags). The picture shows how far, the light never does |
| A level, like Load toward your usual | Drawn in the picture, never as light |
| Recharged, Drained | None ever, no tag, no caret: no Energy history is stored, so they have no usual |
| Strip | None |

Where the rim's point sits follows the picture, not a fixed edge: the right end of a line whose latest part is high,
the top of the dial when above, the bars of the late nights, the end of the age band past your age. The border
outside the rim is never coloured, dotted or bright. Static: the rim only changes when the data changes.

#### Grouped list

`ListPanel`: List surface #0F0F11, 28 dp corners, a 1 dp hairline at 6% white, a title row (card title left, an
optional note in Secondary right).

`ListRow`: the metric's **icon**, 20 dp, stroke 1.5, in the metric's ink, then 14 dp to the name (Body) with its
status (Secondary) under it, the value as plain Row value text right-aligned in its own column, and the caret when the row
opens something. The name stays white, and on one line in every list row (a settings row on You and Strap too):
where a row's name and the value on its right don't both fit, as in a narrow list beside a pane, the value drops under
the name in Secondary, ellipsized only as the last resort. The
value stays white. Off your normal, the status under the name becomes the off tag (Tags), and a row has no rim. Each
row opens that metric's detail. A waiting row dims its
icon to 30% and shows the dim bar for a value with "After your first night" or similar.

| Row | Icon (Hugeicons) |
|---|---|
| Resting, lowest, average heart rate | `Favourite` |
| HRV | `Pulse01` |
| Blood oxygen | `Droplet` |
| Breathing rate | `Lungs` |
| Skin temperature | `Thermometer` |
| Stress | `Brain02` |
| Energy | `BatteryCharging01` |
| Last night, naps | `Moon02` |
| Steps | `FootPrints` |
| Load | `Dumbbell01` |
| Time to recover | `Hourglass` |
| Energy | `Fire` |
| Distance | `Route01` |
| VO2 max | `FastWind` |
| Habit Age (row and page) | `CalendarBlank`, apart from Time to recover's hourglass |
| Workouts (All metrics) | `PersonSimpleRun`, apart from Load's dumbbell |

Contributor rows (What drove it, where Energy went) keep their bar and no icon. Workout rows keep their sport tile.
Settings rows on the You and Strap pages keep their toned white icons.

#### Tappable says so

Every card and row that opens something shows it, and nothing else does.

- **Card:** a 14 dp caret (`CaretRight`, stroke 2) right after the title text, 6 dp from it, at 50% white. It sits
  with the title, so it never clashes with (i) or a note at the right of the title row.
- **Row:** the same caret at 30% white at the end of the row, 8 dp after the value, in every `ListRow` with
  `onClick`, settings rows included.
- **A title row's note that opens something** ("All 4") keeps its caret after the note.
- **Strips** that do one thing are buttons and need no caret. A strip that opens a page (Last night collapsed) has it.
- Cards and rows that open nothing (What drove it while it has no detail, Recharged and Drained if they don't open
  Energy) show no caret.
- Pressed, a card or row scales to 0.98 (3.5).

#### Tags

**News is a tag next to the number.** A filled pill, fully round, white at 12% with no border, Caption type in white,
8 dp after the number's unit (on a Small it drops under the number when it doesn't fit). One per number. A goal and
an off state at once show the goal tag. A usual value shows no tag, so a calm day carries none.

The existing outlined tag beside the title (`OutlineTag`, `TitleTag`: "Partial day", "First score", "Auto-detected")
is unchanged. It's about the data, not your body, so a card can carry one of those and a news tag, and the two never
look alike.

**Goal tags**, a ladder: the highest that applies, for steps, calories, active hours and weekly workouts.

| Tag | When |
|---|---|
| Personal best | Beats your best ever (the rewards' bests, `core_rewards_best_*`) |
| Best this month | Beats every day of the last 30 |
| Double goal | 200% of the goal or more |
| "+2,140 past goal" | 125% or more, the real amount past it |
| "3 days in a row" | Goal met on 3 or more days running |
| Goal met | 100% to 124% (the app's existing "Goal met") |

- **Sleep stops at Goal met.** Sleeping far past the goal isn't an achievement.
- **Calories and active hours stop at Goal met** on a day Load is above your usual or Recovery is below it, so a tag
  never cheers on what another card says to ease off.

**Off tags**, always with the rim on a card (a list row has the tag alone). "Normal" for body signals, "usual" for
behaviour, as the status words already do. A good direction is still off: the rim says "not your usual", not "bad".

| Tag | Metrics |
|---|---|
| Above normal, Below normal | Resting heart rate, HRV, heart rate, breathing rate, skin temperature |
| Below normal | Blood oxygen (high isn't news) |
| Above usual, Below usual | Recovery, Stress, Load |
| Short, Long | Last night |
| Later, Earlier | Regularity |
| Older, Younger | Habit Age, the estimate against your own age |
| More than usual | Breathing disturbances |
| Up, Down | VO2 max, as a trend |

**No tag ever:** Energy, Recharged, Drained (no Energy history) and Time to recover (a countdown, not a reading).

#### (i) on a card

A card that has an explanation shows (i) inside it: a 32 dp round button at 10% white with a 16 dp icon at 72%, at the
right of the title row. It's the same on every card that has one and opens that card's info sheet (6.2). There is
never a "How it works" panel, a separate explanation card or an expandable section on a screen.

### 2.6 Light with meaning on glow cards

| Card | Rule |
|---|---|
| Today hero | Light by moment (4.2): waking 225, midday Energy rising from below, workout window 45 with the rim at 120%, during a workout from below and pulsing, after a workout Recovery teal from below (270) with the rim at 80%, evening 315 in the Evening mood, night from below in the Night mood |
| Energy | `lightLevel` = Energy / 100, light from below, Ember orange |
| Steps (Activity tab) | `lightLevel` = share of the goal, from below |
| Recovery (tab hero, detail header) | The number sits in a dark pool: core behind the number, light all round |
| Workout summary | Glow size by effort: an easy walk leaves most of the card dark, a hard run floods it |
| Live workout heart card | Light height follows the current heart rate zone. Live signal, so it may move |

### 2.7 Lines and charts

**Only the digits are made of dots.** No border, grid, baseline, guide, ghost line, target or gap is ever dotted or
dashed. `PathEffect.dashPathEffect` goes from the UI.

| Thing | Drawn as | Weight |
|---|---|---|
| Data line | Solid, the metric's ink | 2 dp |
| Grid and guides (chart rows, hypnogram stage rows) | Solid, 8% white, at most three | 1 dp |
| Your normal | A band of the ink at 12%, no edge lines | |
| Ghost of your usual by now | Solid, 35% white | 1.5 dp |
| Target or goal | Solid, 40% white | 1 dp |
| A gap in the data | The line stops and starts again, nothing joins it | |
| Low confidence point | A hollow ring | 1.5 dp |
| Hours with no steps, hours to come | Not drawn, a hairline baseline under the bars | 1 dp |
| Pairing, strap to phone | A solid hairline | 1 dp |
| Card and panel borders | Solid hairline, white at 8% (lists 6%) | 1 dp |

Every chart kind in the app, drawn with the rules above:

| Kind | Tier | Where | Touch |
|---|---|---|---|
| Line with scrub | Picture, detail | Heart rate day, night heart rate and breathing | Hold to scrub |
| Ghost line of your usual | Picture | Steps, Load and Energy so far today, in details | Read only |
| Energy curve | Glow | Energy card and detail | Hold to scrub |
| Hourly pill bars | Glow | Steps card | Tap an hour |
| Trend dots | Picture, detail, Recovery hero | Recovery over the last 7 days beside the hero score, Habit Age over days | Tap a day |
| Arc gauge | Detail | Recovery detail header only: a half circle open at the foot, centred, 124 dp radius and a 13 dp stroke with round caps. A track of white at 11% the whole way over, the value from the left end clockwise in a near white tint of the metric's colour (16% of the way from white). The score in dots centred inside, its foot a little above the arc's ends. Fills only to the new value after a sync, never from zero on open | None |
| Horizon arc | Detail | Sleep score detail header only: a night sky rising from the horizon. A wide shallow hill (half a sine wave, 300 dp wide and 124 dp high, 12 dp stroke, round caps), centred, its ends low at both sides. A flatter track over the same ends just under it in white at 12%, the value from the left end along the hill in a light lavender (32% of the way from white to Sleep's ink). The score in dots centred under the crest. Same rule, never sweeps from zero | None |
| Progress ring | List row | Goals | None |
| Hypnogram | Glow | Last night, naps: solid rounded bands in the stage colours on four hairlines | Hold to scrub |
| Stage bar | Glow, detail | One 8 dp bar split by stage with a legend, on the sleep hero and the Sleep detail | None |
| Stacked day bars | Detail | Heart points over 7 days | Tap a day |
| Bed and wake bars | Picture | Sleep regularity | Tap a night |
| Zone arc | Workout | Live workout Heart view only | None |
| Zone bar | Workout | Workout summary and detail, thin under each workout row and on Today's workout card | Tap a zone |
| Age scale | Picture | Habit Age | None |
| Day strip, calm to high | Picture | Stress: the day as one rounded strip, each stretch's stress as the ink's strength | None |
| Dial | Picture | Skin temperature only: a half dial centred on your normal, the needle at tonight's deviation | None |
| Level fill over your usual band | Picture | Load: a bar filling to the value over a dim band of your usual, a dot at the value | None |
| Countdown ring | Picture | Time to recover: a ring filling toward Ready, full when clear | None |
| Blocks | Picture | Active minutes: a block per minute toward the week's target | None |
| Week of dots through your band | Picture | Breathing disturbances, the Recovery Small: a dot a night against the band of your usual | None |
| The day with the nap on it | Picture | Naps: the day as a hairline, the nap as its stage strip at its time | None |
| Fill bar, split bar | Picture | Recharged (34 to 72 as a fill), Drained (what took it, as parts) | None |
| Week rings | Picture | Your week: a ring a day, full and lime at goal | None |
| Contributor bars | List row | What drove Recovery, where Energy went | Tap a row |
| Route by zone | Workout | GPS workouts | Pan |
| Range with handles | Detail | Add a workout, trim a workout | Drag |

Rules for every chart:

- **Your normal** is the band behind the data. Points inside it stay dim, points outside get the full colour. Before
  there is enough data, show "Learning your normal, 4 of 14 nights" instead of a band.
- **Compare by pace**: anything "so far today" is compared with your usual by this time, drawn as the solid ghost line.
  Never against a full day.
- **Scrub moves the headline.** No floating tooltip. The big number becomes the point under the finger, the date
  replaces "Today", release snaps back.
- **Zone arc vs zone bar.** The arc answers "where am I now" and is only on the live workout. The bar answers "how
  was my time spent" and is only after. Never both on one screen. The ongoing notification uses the arc laid flat
  (five segments, the current one lit).

### 2.8 Icons

Hugeicons free, stroke rounded (`ui/theme/AppIcons.kt`), drawn at stroke 1.5, and 2.25 for a selected tab. Sizes: 20 dp
in rows, buttons and the header, 16 dp inside the (i) button and chips, 24 dp in the tab bar. Metric icons take the
metric's ink (2.5, Grouped list). Every other icon is white at 72%, or white when it's the selected tab.

### 2.9 What changed on 2026-10-24

| Topic | Before | Now |
|---|---|---|
| Typeface | Spec and mockups said Outfit, the build ships Funnel | Funnel Display and Funnel Sans |
| Card title | Glow 20 to 26 Light, edge 15 Medium | 17 Sans Medium on every card |
| Hero sentence | Spec 20, the build 26 | 20, three lines at most |
| Secondary text | 12.5 | 13 |
| Glow brightness | Body capped at luminance 0.09, fill gone by 40% on a steep curve | Body up to 0.18, L4 and L3 fills (2.1) |
| Edge light | Soft 62%, strong 75% | L1 70% over 48 dp, L2 90% over 72 dp |
| Off normal on an edge card | The glow mockup coloured the hairline | Plain hairline, strength and the coloured value only |
| Moods | Evening 60%, Night 40% | Evening 80%, Night 65% |
| Energy colour | Gold, a red to green scale by value, orange on glow | Ember orange everywhere, level shown by the light's height |
| Midday hero light | Said from above (90), while Energy rises from below | Energy rises from below, the level rule wins |
| Corners | Glow 48 in the build, 36 in the spec, edge 28 | 28 on every card |
| Card padding | Four different paddings | 20 dp on every side |
| List row marker | An 8 dp coloured dot | A 20 dp icon in the metric's ink |
| Dotted and dashed lines | Grids, ghost lines, gaps, baselines, the pairing line, `DotPanel` borders | None anywhere |
| Habit Age page | `DotPanel`s with a How it works panel | Edge cards and lists, (i) in the card |
| Dot sizes | Eight sizes from 1.7 to 9 dp | Six (2.3) |
| Starting a workout | Three unlabelled icons on Activity, Start only in the workout window | A labelled Start a workout card on Activity, a strip on Today in every moment (4.2, 5.3) |
| GPS | Off by default, even for a run | On by default for outdoor sports, remembered per sport, no row for indoor ones (6.3) |
| Workout pages | Dotted panels, Material type, a headline at 26 times 1.25 | The page, tiers and type of every screen, Start and Done pinned (6.3) |
| Sleep hero | An arc, dotted hypnogram guides, five dotted numbers | One dotted score, a solid hypnogram, a stage bar with a legend (5.2) |
| Sport picker | 38 sports in seven sections, indoor and outdoor as separate sports | Twelve everyday sports, More sports for the rest, an Outdoor or Indoor switch on Ready (6.3) |
| Up and down | "+" with "−" or "-", dotted arrows in places | Light for which way, a signed number with its reference, the status word, no arrows (2.3) |
| Dotted numbers | Several on one card | At most one per card (2.3) |
| Tap affordance | Nothing showed a card or row could be tapped | A caret after the title, a caret at the end of the row |
| You and Strap | Opened with no transition | Grow from the avatar and the strap chip (3.5) |
| Tab bar | Cards ran under it with no fade | A 120 dp black fade behind the pill (3.2) |

### 2.10 What changed on 2026-11-04

The edge light washed in from one edge of every card, usual or not, so it said nothing and every card looked alike.
Worked out in mockups (`mockups/grid-canvas.html` holds the result: the seven Today moments, the tags and the three
tabs). The [design system page](design-system.html) still shows the 2026-10-24 rules until it's redrawn.

| Topic | Before | Now |
|---|---|---|
| Edge light | L1 on every usual card and strip, L2 off normal, from a fixed edge by meaning | No light on a usual card. L2 is a rim lit next to the part of the picture that is off (2.1, 2.5) |
| Card insides | Most cards a number and a word, Smalls never a chart | Each metric draws its own picture, Smalls included, and neighbours never share one (2.5, 2.7) |
| Colour | Values and status in the ink when off normal, chart marks dim when usual | The ink means which metric: pictures always in colour, numbers always white, status in Secondary (2.2) |
| Off normal and goals | The coloured value and status | A tag next to the number, a goal ladder and off words (2.5, Tags) |
| Sizes | Chosen card by card | Ranked by how much each signal has to show (2.4) |
| Today | Rows of mostly full-width cards | A grid one size down, Smalls in pairs (2.4, 4.2) |
| Stress | A row in Right now and Body signals | A day strip card: a Small on Today at midday, a row on Recovery |
| Skin temperature | A row in Body signals | Its own short Wide with the dial on Recovery |
| Recovery's Body signals | Six rows | HRV and resting heart rate leave it, What drove it already has them |
| After a workout hero | Heart rate red | Recovery teal. Red is for effort and heart rate off normal |
| Workout window | A list "Before you start" | Load and To recover as a Small pair |
| Neutral light | On strips | Gone, strips are unlit |
| Your week | Steps a day in dots, sleep and workouts as text | A ring a day in lime, full at goal. The figures move to the week story |
| Start a workout on Today | Every moment but during a workout and the workout window | Not at night either |

---

## 3. Frame

### 3.1 Header

`ScreenParts.DateHeader` is replaced by `MomentHeader`:

- **Left:** avatar button, opens the You page.
- **Centre:** the moment on Today ("Waking up", "Midday", "Workout time", "After your run", "Evening", "Night") or the
  tab name on other tabs, over the day ("Thu 23 Oct"). Never wraps. Yesterday's short name peeks in dimmed on the
  left. Swipe the header to change day. Tapping the date opens the calendar sheet, which marks days with data.
- **Right:** the All metrics button (a round bar chart icon, opens the All metrics sheet, on every tab), then the
  strap chip. **Hidden for now:** the button is off until All metrics earns its place. The sheet stays built.
  The strap chip shows battery and a dot when all is well. Words only when they matter: "Charging 64%", "Not
  synced" (amber dot), "18 min ago" (amber, last seen), "Not paired". Tapping it opens the Strap page from any tab.

Chevrons go. Changing the day still changes it on all four tabs (shared `DayViewModel`), as now.

### 3.2 Tab bar

Four tabs in a pill, centred, and nothing else: Today, Recovery, Sleep, Activity. The active capsule is tinted with its
section colour (white on Today). The bar only ever switches tabs. Nothing beside it does an action or changes with
the moment.

- **Hidden over a detail.** A detail that covers the tab (folded) is a screen stepped into: the bar slides away and
  only the detail's back button leads out, as in most apps. Back returns to the tab, and the bar slides back. So the
  bar never names a tab you aren't looking at, and a tab is always left from its root. Unfolded, where the detail
  opens in the right pane beside the tab, the bar stays. The live workout capsule drops to 16 dp above the bottom
  while the bar is away.

- **All metrics** is the grid button in the header (3.1).
- **Start a workout** is the first card on Activity (5.3), a strip on Today in every moment but during a workout, and
  the Start button in Today's workout-window hero (4). All of them open Ready (6.3).
- **During a workout** the live capsule above the bar shows the heart rate in the zone colour and opens the live
  screen.
- **The fade behind it.** A black fade 120 dp tall sits behind the pill (solid black for its lower 45%), so cards
  slide under the bar softly instead of cutting into its edge. Every scrolling screen ends 24 dp above the pill.
- **Under pinned buttons** (Profile's Save, Start on Ready, Done, Choose the sport) the same fade: solid black from the
  bottom of the screen up to the buttons, then clearing over 66 dp above them, the tab bar fade's ramp. The page ends
  66 dp above the buttons, so its last content scrolls fully clear of them. Folded, unfolded and in the right pane.
- The pill sits on the Sheet surface with an 8% hairline. Tabs use 24 dp icons and Caption text, Sans 500.

**All metrics sheet.** A modal sheet over the current tab, in the style of the sheets over the app: every metric grouped by section,
each row with its value for the day. Tapping a row closes the sheet, then opens that metric's detail on the current
tab. Recovery and Sleep rows switch to their tab at its root, and Energy to the Recovery tab with its card in view.
Each row shows what its card shows: Recovery the strap's estimate (so labelled) while OpenStrap's score is withheld,
Energy a dash "After your first night" until anchored, Habit Age the rounded estimate. Workouts, in heart red, counts the
last 7 days, and says so.
Back or a swipe down closes it. The tab bar under it does nothing while it's open.

### 3.3 Navigation rules

- A card opens its own detail, grown out of the card (3.5), on Today as on the tabs. Energy on Today (the card and the
  midday hero) opens the Energy detail, as the battery on Recovery does. Recovery on Today (the waking hero and the
  "This morning" card) opens the Recovery detail, and Last night (the card and its strip) the Sleep score detail, as
  the Sleep tab's hero does. The evening hero, a summary of the day, opens the Recovery screen. **No card ever switches tab.** `FocusTarget` and the tab
  jumps in `MainActivity` are removed.
- Tapping the current tab scrolls to top. Tapping it again pops to its root. Tapping another tab keeps every stack
  (only reachable unfolded, since folded the bar is hidden over a detail).
- Back from a tab root goes to the previous tab, then Today, then leaves.
- The You page, the Strap page and the All metrics sheet open over the current tab and close back to it. Pages
  opened from them stack on top, so Back from Profile returns to You and Back from Alarms returns to the Strap page.
- Deep links keep working: `open_workout`, `show_workout`, `ready_sport`. Health alert and body-signal notifications
  gain a route to their metric detail (`open_metric`).

### 3.4 You page and Strap page

**You page** (from the avatar, a page like the Strap page, with the same round back button): Profile, Goals (steps, active hours, sleep goal, and your sleep need shown
with it), Your data (Save a copy, Restore from a copy, Health Connect), Diagnostics, About. Every row shows its current
value as a second line. It was a sheet, but most rows open pages of their own, and a sheet that closes to open a
page loses its way back.

**Strap page**: hero glow card with the strap, state line with relative time and battery. Sync now and Find strap.
Rows with values: Alarms, Phone alerts, Health alerts, Off-body reminder, Auto-detect workouts (moved from Activity),
Stay connected in background, Firmware, Zepp account (moved out of Firmware). Then Restart and Forget. Alarms,
Firmware and Profile pages move to the same edge and list styling (no Material panels).

**Health alerts**: one row per setting with its value on the right and a down caret (`PageMenuRow`). A tap opens a
menu at the row with every value the strap offers and a tick on the chosen one, in place of a row of chips per
setting. An Off value is dimmer. The measuring row says "Heart rate alerts need Continuous or every minute." under it
only when the chosen measuring turns the heart rate alerts off.

**Every page over a tab** (You, Strap, details, Habit Age, All workouts, Alarms, Phone alerts, Health alerts,
Firmware, Profile, Goals, Diagnostics): a 44 dp round back button on the Control surface, the page title in Page title
type (2.3), then cards in the tiers of 2.5. Material list items, cards, outlined text fields and dividers are replaced
by `ListPanel` rows. Switches stay Material, white with black on top.

### 3.5 Motion

Motion is signal. Specs use Compose springs with no bounce (`dampingRatio = 1f`).

| Motion | Spec | Signals |
|---|---|---|
| Card to detail | Shared bounds transform: the card's surface grows into the detail, its number becomes the header. Spring stiffness 500 in, 300 out | Where it came from |
| Avatar to You, strap chip to Strap | The same shared bounds from the 44 dp button: the button's circle grows into the page, Back shrinks the page into it. 500 in, 300 out. A page opened any other way (Profile from a card) fades in | Where it came from |
| Sheets (info, calendar, Edit Today, All metrics) | Slide up with stiffness 400, the scrim fades to 60% black | It sits over the page |
| Value change | Animate from the old to the new value over about 450 ms, only after a sync or a live update. Never from zero on open | What changed |
| Level change (Energy, steps, Load) | The light rises or falls from the old level to the new over 600 ms, with the number | What changed |
| Moment change | Hero content crossfades over 300 ms. Rows reorder only at a moment boundary, never while the screen is visible | The day moved on |
| Press | Scale to 0.98 over 100 ms, back on release. No ripple on cards | It heard you |
| Live heart rate | `Heartbeat` at the real bpm | Alive |
| Collapsing summary | When the hero scrolls off, a slim strip with Recovery, Energy and Sleep pins under the header, tracking scroll exactly | Keeps the answer in view |
| Tab switch | The pager slides. Cards inside it don't animate | |
| Default | Stiffness 400 | |
| Workout | Stiffness 800 | Snappy |
| Evening and night | Stiffness 250 | Calm |

Removed: idle core motion on every card, the sleep arc sweep from zero, the bouncy switch knob, the generic slide
for details. When the system's animator scale is 0, everything jumps to its end state.

### 3.6 Haptics

| Event | `HapticFeedbackType` |
|---|---|
| Scrub lands on a new point (day, hour, stage) | `SegmentFrequentTick`, at most every 40 ms |
| Scrub crosses into or out of your normal, or hits the highest or lowest point | `SegmentTick` |
| Heart rate zone changes (at most every 10 s), a strength set is done, a km split during a workout | `SegmentTick` |
| Workout starts, workout ends (hold completes), pairing succeeds | `Confirm` |
| A manual sync can't start or fails | `Reject` |
| A range handle passes the detected start or end | `SegmentTick` |
| Ordinary taps, pull to sync ratchet | None |

---

## 4. Today

### 4.1 Moments

`MomentResolver` (new, pure Kotlin, unit tested) returns one moment for "now". It only reads data the app already
has. It computes nothing about the body.

| Moment | Starts | Ends |
|---|---|---|
| During a workout | A recording is running | It ends |
| After a workout | A workout ended (phone or strap) | 2 h later |
| Workout window | 45 min before your usual workout time | 45 min after it |
| Waking up | Main sleep ends today (05:00 if none) | 3 h later (the current `chargeLeads` rule) |
| Night | 45 min before your usual bedtime | 04:00 |
| Evening | 3 h before your usual bedtime (19:00 if unknown) | Night starts |
| Midday | Otherwise | |

Priority when they overlap, top wins: during, after, workout window, waking, night, evening, midday.

- **Usual workout time:** the median start time of workouts in the last 28 days, per weekday when that weekday has 3
  or more, otherwise across all days when there are 3 or more. No workout window until then.
- **Usual bedtime:** from `RecoveryEngine.usualSleep`.
- Past days always show the waking layout.

### 4.2 Layout per moment

Rows in order, as a grid one size down from the tabs (2.4): Smalls in pairs, a pair never right after a pair. Sizes
per 2.4, tiers per 2.5, pictures per 2.7. Every card is a picture card unless it says otherwise. Each moment ends with
Your week (Wide, unlit, week rings) and Start a workout (strip) where they apply, below.

| Moment | Hero (glow) | Then |
|---|---|---|
| Waking up | Recovery: "Slept 7 h 40. Recovered enough for your usual training." Score, status word, "Energy at waking 84" | Check-in strip. List "Your body this morning" (resting HR, HRV, blood oxygen, breathing rate). Wide: Last night (the sleep score in dots with its tag and time asleep under it, as on the Sleep tab, then the stage bar), the card the morning is about. Pair: Heart rate, Steps |
| Midday | Energy: sentence, value with `lightLevel`, recharged and drained | Pair: Recovery (a week of dots), Last night (the night's stage bar). Wide: Steps (hourly bars, "Goal 8,000" as the note, the pace in words). Pair: Heart rate (the line through your band), Stress (the day strip). The Right now list is gone: Stress has its card, HRV lives on Recovery |
| Workout window | "Ready for effort. You usually run around now." Start button, Other sport | Second glow: Energy (wide), a level glow, the light filling across from the left to the value. Pair: Load (level fill over your usual band) and To recover (countdown ring), replacing the "Before you start" list. Wide: Steps. Strip: Last night |
| During a workout | The workout, live heart rate | Wide: Steps. Pair: Recovery, Last night. Heart rate and Stress leave (the hero has the heart) |
| After a workout | "Since your run, 40 min. Heart rate settling." Drop curve into your resting range (a dim band), calm glow | Wide: the workout (its fact and zone bar). Pair: Load (rimmed and "Above usual" when the run took it past your usual) and To recover. Second glow: Energy, as in the workout window, with its line so far. Pair: Steps, Last night |
| Evening | "A full day. A run, past your step goal and a Load above your usual." The steps said against the goal, since the count is in dots under it. Load only in the sentence and Energy only on its card right under the hero; with that card hidden in Edit Today, "Energy 31 left" as the last line. "Bedtime in 1 h 10". Dim mood | Second glow: Energy, as in the workout window. Pair: Steps (hourly bars, the goal tag once met), Heart rate (the day's line). Strip: Last night, unlit |
| Night | As evening, Night mood | As evening, without Start a workout |

How each moment looks. The [design system page](design-system.html#moments) shows each hero.

| Moment | Header | Hero colour | Light from | Strength | Mood | Motion | Leads with |
|---|---|---|---|---|---|---|---|
| Waking up | "Waking up" | Recovery | Lower left, 225 | L4 | Day | 400 | A sentence |
| Midday | "Midday" | Energy | Rises from below to the value | L4 level | Day | 400 | A sentence |
| Workout window | "Workout time" | Heart rate | Upper right, 45 | L4, rim at 120% | Day | 800 | A short prompt and Start |
| During a workout | The sport ("Running") | The current zone's colour | Below, pulsing at the real bpm | L4 | Day | 800 | The number |
| After a workout | "After your run" | Recovery | Below, 270 | L4, rim at 80% | Day | 400 | A sentence |
| Evening | "Evening" | Evening violet | Lower right, 315 | L4 | Evening | 250 | A sentence |
| Night | "Night" | Sleep | Below only | L4 | Night | 250 | A sentence |

The rest of the screen takes the moment's mood and motion too. The hero's light never moves while it's in view. It
changes with the moment, crossfading over 300 ms (3.5).

The After a workout hero is Recovery teal: red stays for effort (the workout window, the live workout) and for heart
rate off your normal, so it stops once the run is done.

Heart rate shows at waking, midday, the evening and night, never in the workout window, during or after a workout.
On Today it's a Small: the day's heart rate as a line through your resting range (your normal resting heart rate,
mean ± one standard deviation over 30 days) as a dim band, rimmed at the line's end when off your normal. Tapping
opens the detail. Scrubbing lives on its Wide on Activity and in the detail. The Steps card draws today's hourly bars
in lime with the goal as the title row's note and the pace in words under the number, no progress bar. Energy's
level glow draws its line so far until the evening.

Present in every moment when they apply:

- Workout offer and suggestion (strip), from today only.
- Health alerts from the strap (strip, "From your strap"), opening the metric.
- "Start a workout" (strip, unlit, the moving border) above Edit Today, in every moment except during a workout, the
  workout window, whose hero has Start, and the night. The title, your usual sport under it ("Outdoor run, your usual") and a
  44 dp white play button. Tapping opens Ready with that sport.
- "Your week" (wide card, unlit) Monday through Sunday, opening the week story: a ring a day in Steps' lime, full
  for a day at goal, "4 of 7 days at goal" as the note.
- Habit Age (Wide, the same card as on Recovery) only when there's a new estimate: from the Sunday it updates
  until it has been opened or the Monday ends. Pinned in Edit Today it shows every day. Never before the first
  estimate, pinned or not.
- "Edit Today" at the end: hide cards, pin up to two under the hero. Replaces the What changed tile chooser.

### 4.3 States

| State | Today shows |
|---|---|
| No strap paired | Hero is the pairing stage: the phone on the left, the strap on the right as on the Strap page, both 16 dp in from the text, a solid hairline from the phone to the pod, one sentence per step, "Find my strap". Each step moves: ripples out of the phone while searching, a pulse from the pod once found, sparks along the line while connecting, the line lighting up to the strap with a flash at the pod when connected, the line broken and amber when it fails. Below, waiting cards dimmed |
| First nights | Cards keep their shape, value as a dim dash, "After your first night" |
| No sleep last night | Waking hero: "No sleep recorded last night. Was the strap on?" Energy leads from then on |
| Sync stopped | Chip turns amber "Not synced". Stale cards get an "Up to 09:40" marker. The app retries in the background. No bar |
| Sync stuck when it matters | Still failing after 3 h, or in the morning when last night is on the strap: the hero becomes "Last night is still on the strap" with Sync now and "Last tried 07:42". Lists show "Waiting for last night" |
| Strap disconnected | Chip shows "18 min ago" in amber. Nothing else changes until data is stale |
| Strap charging | Chip "Charging 64%". The Strap page says wear data pauses while charging |
| Partial day | One "Partial day" marker next to the affected card's title, explained on tap |
| Recovery failed to compute | The Recovery card keeps yesterday's value dimmed with "Couldn't work out today's score. Try again", never "Calculating" forever |

---

## 5. Recovery, Sleep, Activity

### 5.1 Recovery tab

Each tab keeps one set of cards whatever the day, each at its ranked size with its own picture (2.4, 2.7), chosen so
no two neighbours ever look the same (2.5). `TabPlans.kt` holds the order, looks, sizes and wide-screen columns, and a test goes through
every optional card on a phone and on two columns. On two columns a full-width card also counts as touching the top
card of each column under it.

1. Glow hero: sentence ("Recovered enough for your usual training. Mostly held back by HRV."), score in a dark pool,
   status word, "+3 vs yesterday", (i), and trend dots for the last 7 days inside the glow, today the only full dot.
   While OpenStrap's score is withheld the strap's stands in, "Strap's estimate" under it, as on Today.
   "Learning, 9 of 14 nights" marker while calibrating. The normal band behind the dots waits for a normal of the
   score (step 6).
2. List "What drove it": the three contributors that weigh most, as contributor bars (name, bar, word, the off tag
   in place of the word when off usual). "Top 3 of 9" in the title until the Recovery detail exists to open (step 6).
3. TallPair: Energy as a Tall glow battery (`lightLevel`, the light rising to the value), the Energy curve (hold to
   scrub, the number follows), the number and "Down 28 since you woke". Beside it, Recharged ("+38", a fill bar from
   34 to 72, "Overnight, 34 to 72") over Drained ("−28", a split bar of what took it, "Half of it the run"). Both
   plain: unlit, no tag, no caret (2.5).
4. Short Wide: Skin temperature, the deviation in dots with the dial beside it, centred on your normal. Rimmed at the
   dial's top or foot when off.
5. List "Body signals": breathing rate, blood oxygen, stress. HRV and resting heart rate leave it: What drove it on
   the same page already has them.
6. Wide: Habit Age, always last. See 5.4.

Wide: the hero spans both columns. What drove it and Habit Age on the left, the Energy battery, Skin temperature and
Body signals on the right, so no two lists sit side by side.

The off-baseline panel, How it works and the strap's own score are gone from the screen. The strap's score and the
method move to the Recovery info sheet. The body-signal Watch and Alert panel is gone too: an alert reads in the
hero's sentence, and an off-normal signal shows as its row's tag.

### 5.2 Sleep tab

Night mood always.

1. Glow hero: "7 h 40, your longest this week." (longest or shortest of the week's nights once there are 3 others to
   compare, else "7 h 40 asleep."). "Longest" rather than "best": more sleep isn't always better. Bedtime to wake as
   the title row's note. The score in 4.5 dp dots with "Sleep score, good" and "Woke 3 times" beside it, the only
   dotted number on the card. The hypnogram to scrub (2.7), then the stage bar with its legend ("Deep 2 h 51",
   "REM 1 h 31", "Light 3 h 39", "Awake 18 min", each with an 8 dp colour mark, Secondary type). No arc of any kind
   on the hero, no dotted stage totals. The Sleep score detail drops its dotted times the same way, and holds its
   score in the horizon arc (2.7), centred in its header. The horizon arc lives only there.
2. List "Night vitals": lowest heart rate, HRV, breathing rate, blood oxygen, skin temperature, average heart rate.
   Each opens its detail; scoped to the night in step 6.
3. Wide: Sleep regularity, score, tag or status word, usual bedtime and wake, the week's bed and wake bars in the ink
   against dim bands at your usual times. Off, the rim lights beside the nights that are off.
4. Small pair, when there are naps: Breathing disturbances (a week of dots through your band, "Usual 2.1, early
   estimate") and Naps (the day with the nap on it: time napped, the latest nap's stage strip, "At 14:10").
   With no naps Breathing disturbances is a Wide. With naps but no breathing summary, Naps is.

Wide: the night spans both columns, vitals and the Small pair (or lone naps) on the left, regularity and a lone
breathing card on the right.

Gone from the screen: the stages panel (totals sit in the hero), What shaped the score and the night's heart rate
and breathing charts (Sleep detail, step 6), Sleep health and the week's averages (regularity's one home now), and
each nap's heart rate (the naps in the Sleep detail, step 6).

### 5.3 Activity tab

1. Wide card "Start a workout", unlit, with the moving border. Today only. Your three most used sports as 64 dp
   tiles with their names under them, then All sports. Outdoor sports carry a small location mark on the tile. A tile
   opens Ready with that sport (6.3).
2. Level glow: Steps, drawn like the Energy battery (2.1 Levels, the page's level card): the light rises from
   below to the share of the goal, full at the bottom edge, with the near-black core above it. Title top left with "Goal
   10,000" as its note, then the hourly pill bars in the dark part (white at 45%, the latest hour, or the one tapped,
   full white; hours with no steps and those still to come are not drawn, only the hairline baseline, 2.7), then the
   number at the foot in the light in 3.6 dp dots with "62% of your goal, fewer than usual by now" under it (the pace
   against your usual steps by now once there is one, 2.7 Compare by pace). The goal is the card's full height, so no
   goal line; the pace is in words, so no ghost line on the bars. The goal tag sits after the number once met (2.5,
   Tags). Opens the Steps detail.
3. Small pair: Active minutes ("This week, goal 150", blocks toward the target) and Heart points ("Last 7 days, aim
   for 100", the zone bar).
4. Wide: Load, a level fill over your usual band, rimmed at the right end and "Above usual" when past it, "12 today", "Your usual is 40", balance word ("Building",
   "Steady", "Easing off") from the last 7 days against the last 6 weeks, with Today's 25% band for usual. No bar.
   The ghost line of your usual Load by now waits for Load by the hour (step 6). Strain lives in its detail.
5. List "Today", "So far": VO2 max, time to recover, energy, distance.
6. Wide: Heart rate, the day's line with your resting range as a dim band, hold to scrub (the number follows),
   rimmed at the line's end when off your normal. It sits between the two lists, so they never touch.
7. List, unlit: "Workouts", the day's and the latest before it (5 in all), under small dim day
   headers ("Today", "Yesterday", "Wed 21 Oct") instead of a date on each row, a pending suggestion as a row with
   Save, "Add a workout you didn't record", "All workouts" always. Each row (version A of the workout mocks):
   - **Effort light:** the sport's toned white icon on a small dark tile (38 dp, 12 dp corners) with a soft
     heart-red glow behind it, as strong as the workout's Load (its EPOC, the strap's or OpenStrap's) against your
     usual workout's: the median of the last 20 with one, a usual one at half, twice it at full. Under 3 workouts
     with a Load, 150 is full. No Load keeps a faint minimum. Not the day's Load, which is TRIMP on another scale.
   - **One fact per sport,** then the start time: distance and pace for runs ("5.2 km, 5:24 a km, 21:28"),
     distance for walks, sets for strength ("14 sets, 07:10"), average heart rate otherwise ("Average 128 bpm,
     09:04"). Each falls back to the next the workout has. Distance (GPS or "≈" from steps, no pace from steps)
     and sets come from phone workouts only. The strap's workouts carry neither, so theirs say heart rate.
   - **Auto-detected:** a quiet outlined tag beside the name of a workout the strap detected or filed as
     "Activity".
   - The duration as a plain row value on the right and the thin zone bar under the row, lined up with the name.
   - A personal best would be one warm line after the fact. The app doesn't detect them yet.
   All workouts uses the same row inside its day panels.

Wide: the strip and Steps span both columns. The Small pair and Load on the left, the list, Heart rate and Workouts on
the right.

Gone from the screen: the offer card (Today only), active hours, the step counter mismatch note and every method
note (Paluch, Mifflin-St Jeor, Jurca, FRIEND, acute:chronic, the strap's weekly EPOC range), which belong in the info
sheets.

### 5.4 Habit Age

One design wherever it shows: a Wide picture card with the title,
"Updated Sun 18 Oct", the age in dots, one status line and the age scale (the range as an orchid band, a white dot
at the estimate, a hairline at your age, "27 to 31" under the band and "You, 34" over the hairline), and (i) at the
right of the title row (2.5). The glow card and How it works are gone, on the tab and on the Habit Age page alike.
The method, the pace of aging and what moves it live in its info sheet.

- **Habit Age page** (opened from the card): the same card at the top, then "Pace of aging" and "What moves it"
  as `ListPanel`s with icon rows. Pace of aging: the pace as the title row's note ("Slower, 0.5x"), one row
  (`Hourglass` in Habit Age's ink) with what it means, and the scale from -1x to 3x under it. What moves it: "Over 6
  months" as the note, the scale's ends in Caption, then a row per habit with its metric's icon in its ink (Load's
  `Dumbbell01` for strength, `FastWind` and `Favourite` in heart red, `FootPrints` in steps lime, `Moon02` in sleep
  blue), what it takes off or adds over the numbers behind it, and its bar. No row opens anything, so no carets. No
  `DotPanel`, no expandable How it works.

- **Status line:** "1 year younger than last week", "2 years older than last week" or "Same as last week", in whole
  years against last week's estimate. Until two weeks exist it says the range, "Likely 27 to 31". The number stays
  white. "Older" or "Younger" as the tag, with the rim at the band's end, when the estimate is past your own age.
- **Last week:** each Sunday's estimate is stored as shown (`habit_age_week`, database version 18) when it's worked
  out, and the next week compares with the latest one from the 8 days before.
- **Before the first estimate:** your own age from your birth year in its place, "Your age for now. First estimate
  Sunday 22 Nov" under it (or what the profile still needs). No number without a birth year. Never on Today.
- **No birth year or sex:** says what's needed and opens the profile.
- **Where:** always at the bottom of Recovery. On Today only while new (4.2), or pinned.

---

## 6. Details

### 6.1 Metric detail template

One template for every number, including Recovery, Energy, Sleep score, Heart points, Load, VO2 max, active minutes,
energy, regularity and steps. Habit Age keeps its own screen in the same style.

1. Hero area tinted with the metric colour, fading to black. Back, metric switcher (siblings in the same group),
   (i).
2. Value in dot-matrix with its status word ("41 ms, Below your normal"), the word in the metric colour. Recovery's
   detail holds its score in the arc gauge and the Sleep score detail in the horizon arc (2.7): the gauge, the label
   and the status word are one block, centred in the header, the number centred in the gauge.
3. A seven-day strip with a dot under days that have data.
4. Day, Week, Month, Year pills. The value and the sentence change with the range.
5. Chart per 2.7 with your normal as a band and off-normal points lit.
6. One time-aware sentence.
7. Related rows (list).

### 6.2 Info sheet

From (i) on a card or a detail header (2.5): a Sheet surface sheet with a bold one-line meaning, the scale as coloured bars with "You are here", then folded rows: How it's worked
out, What moves it, Why it might be off. Every method name, formula, citation and caveat in the app moves here (24
strings on cards today).

### 6.3 Workouts

Every workout page is a page like any other (3.4) with the tiers of 2.5 and the type of 2.3. No `DotPanel`, no
Material type, no headline scaled up. The way in is in 5.3 and 4.2.

- **Choose a sport** (All sports, or tapping the sport on Ready): round back button, "Choose a sport" as the page
  title, a search field on the Control surface with 16 dp corners and a search icon. "Recent" first (your last four
  sports), then "Sports", twelve everyday ones: Run, Walk, Hike, Cycling, Swim, Strength, HIIT, Yoga, Pilates,
  Rowing, Elliptical, Other (free training). Then a "More sports" row that opens every other sport as an A to Z list
  of icon rows. Search looks through all of them. Four tiles to a row: a 64 dp Control tile, 16 dp corners, the icon
  at 24 dp, the name in Secondary under it. The chosen sport's tile is white with a black icon. A sport done only
  outdoors (Hike, Trail run, Skiing) carries a 12 dp location mark in the tile's corner. Today the picker lists 38
  sports in seven sections. No sport type is removed: the strap still names detected workouts with every type
  (`Workouts.SPORT_NAMES`), they only move behind More sports.
- **Outdoor or indoor is a switch, not a second sport.** Run, Cycling, Swim and Walk appear once. Ready shows a
  two-way switch under the sport pill: Outdoor or Treadmill for Run, Outdoor or Indoor for Cycling, Open water or
  Pool for Swim, Outdoor or Indoor for Walk. It's remembered per sport. The saved workout keeps the strap's own sport
  type (Outdoor run 1 or Treadmill 2, Outdoor cycling 4 or Indoor cycling 8, Open water 7 or Pool 6, Walk 3 either
  way), so history and detection don't change.
- **Ready:** the close button and the sport as a pill with a down arrow (tap to change). The hero is the strap: a glow
  card in heart red with the live heart rate in 4.5 dp dots, pulsing at the real bpm (`Heartbeat`), "Live from your
  strap" and the battery when low, (i) for how recording, GPS and the route work. Not connected, the hero is an
  outline card: "Strap not connected. You can start anyway." Then the settings as list rows with switches: GPS and
  Keep the route (outdoor sports), sets and rest chips (strength), the target chips under a Caption label, Ask to end
  when I stop. **Start** is a 56 dp white pill pinned to the bottom, naming the sport ("Start run"), in the target's
  zone colour when one is set.
- **GPS by sport:** the Outdoor side of the switch, and every sport done only outdoors (`Workouts.OUTDOOR`), turns
  GPS on, with a GPS row to turn it off for this workout. Keep the route sits under it, off by default. The indoor
  side, and every indoor sport, has no GPS row at all. Without GPS, walks,
  runs, treadmill and stairs get distance from steps, marked ≈. When location is off or not allowed, the row says
  "Location is off" with Turn on, and Start still works.
- **Live:** two views. Heart: zone arc with bpm, zone name and range, "9 min in this zone". Map: the map with a bpm
  chip in the zone colour. Swipe with two dots, the last view is remembered. Stats row unchanged. After each km a line
  shows for 10 s ("Km 3 in 5:42, avg 152") with one `SegmentTick`. Paused drops the card to an outline. Hold to end
  unchanged.
- **Notification:** time, distance and pace, bpm in the zone colour, the flat zone strip, Pause and Lap. Collapsed:
  sport, time, bpm.
- **Right after (calm down):** a page titled "Run saved" (the sport's short name). The hero is the drop: a level glow
  in heart red lit from below as high as the effort, "Heart rate drop" with (i), the sentence ("Down 34 in the first
  minute. A quick drop for you.") and the heart rate now in dots. Wide: the workout with its caret, duration in
  dots, distance and pace as text, the zone bar. Wide: Load from this run (rimmed, "Above usual", when above it), a
  personal best as one line under it ("Fastest 5 km since March") and a short glow swell, no modal. List: Splits and
  Trim the start or end as rows with carets. **Done** is the 56 dp white pill pinned to the bottom, "Delete this
  workout" quiet text under it.
- **Workout detail:** the same page for strap and phone workouts, with Back in place of Done. The hero is the sentence
  ("Hard run, mostly in zone 4."), the day and times as the note, the duration in dots, lit as high as the effort.
  Heart rate line coloured by zone, zone bar, splits, training effect behind (i). "Recovery heart rate" becomes
  "Heart rate drop", "Recovery time" becomes "Time to recover".

---

## 7. Copy

### 7.1 Rules

From the brief, enforced in code review and by a test (8.2):

1. Meaning first, then the number, then how (one tap away).
2. Calm, sharp friend: short, direct, specific. No cheering, emoji, exclamation marks, invented empathy or "you
   should".
3. Sentence first in the morning and evening, number first during workouts and live readings.
4. One caveat marker per card, explained on tap.
5. Time-aware: "so far today", "this morning", "since your run", "tonight".
6. **No interpunct (·), semicolon (;) or em dash (—) in any user-facing string.** Separate parts are separate text
   elements, or a full stop or comma. En dashes in time ranges are fine. The empty value is a dim dash drawn by the
   UI, not a character in a string.

### 7.2 Names

| Use | Never |
|---|---|
| Recovery (the score and the tab) | Readiness, recovered (as a score name) |
| Energy | Charge (it read as the strap's battery), battery, energy score, BioCharge |
| Calories | Energy (that's the body's Energy, above) |
| Load | Strain (on cards), training load, TRIMP |
| Heart points | PAI (named once, in its info sheet) |
| Sleep regularity | SRI, regularity index |
| HRV | Heart-rate variability on cards (spelled out in its info sheet) |
| VO2 max | VO₂max, VO2max |
| Time to recover | Recovery time |
| Heart rate drop | Recovery heart rate |
| Breathing rate, Breathing disturbances | Breathing (alone) |
| Habit Age | Healthspan |
| Above your normal, Usual, Below your normal | Pay attention, Optimal, Higher, Lower, off baseline |
| From your strap | Strap alerts, alert |
| Save a copy, Restore from a copy | Export CSV, Import CSV |

The copy pass of step 9 rewrote the 223 strings the inventory listed.

---

## 8. Build

### 8.1 Order

Each step is one or a few commits on `design-v2`, with tests and before-and-after screenshots over adb.

1. **Foundation:** `GlowStyle` additions (`lightAngle`, `lightLevel`, `second`), motion reduced to `Heartbeat` and
   `Still`, `GlowMood`, `EdgeCard`, `ListPanel` and `ListRow`, the grid row types, `MetricColors.load`, a haptics helper
   with the 3.6 table.
2. **Frame:** `MomentHeader`, four-tab bar, the All metrics button in the header, the navigation rules (removing `FocusTarget` and
   tab jumps), You page, Strap page and its moved settings.
3. **Moments:** `MomentResolver` with unit tests for every boundary and overlap, including usual workout time.
4. **Today:** layouts per moment, states, Edit Today.
5. **Tabs:** Recovery, Sleep, Activity.
6. **Details:** the metric detail template for every score, info sheets, the card to detail transform, scrub haptics.
7. **Workouts:** two live views, the split line, calm down, zone bar, notification.
8. **States:** sync quiet and stuck, pairing stage, waiting cards, errors in place.
9. **Copy:** every string per section 7, the punctuation test turned on.
10. **Review:** retake `docs/screenshots/`, check the brief's measures (morning answer under 3 seconds, every screen's
    purpose in one sentence, no unexplained acronym).
11. **Design system pass** (added 2026-10-24): an audit of every screen over adb against section 2, then fixes in this
    order: tokens in code (`GlowType`, light levels, `MetricColors`, radius and padding, dot sizes), the glow and edge
    renderers, list row icons, lines and charts (no dashes), Habit Age, the sleep hero, Start a workout and the
    workout pages with GPS by sport, You and Strap transitions, the tab bar fade,
    then every screen and page re-checked on the phone, folded and unfolded.
12. **Picture cards** (added 2026-11-04, 2.10): `EdgeCard` becomes `PictureCard` with the rim, a picture per metric
    (2.7) on Wides and Smalls, the colour rule (pictures in the ink, numbers white), news tags with the goal ladder,
    the ranked sizes on the tabs, Today's grid one size down per moment (4.2), the After a workout hero in Recovery
    teal, `varyLooks` reworked for pictures in place of edges.

### 8.2 Tests

- `MomentResolver`: each moment, each overlap, no-sleep and no-workout fallbacks, past days.
- Usual workout time: weekday rule, all-days fallback, fewer than 3 workouts.
- Rim: no rim on a usual card, a list row or a strip, and one on every off card, at the point its picture gives.
- Tags: one per number, the goal ladder's order and thresholds, goal over off, Sleep stopping at Goal met, calories
  and active hours stopping there when Load is above usual or Recovery below it, no tag on Energy, Recharged, Drained
  or Time to recover.
- Pictures: no two neighbours on any page, phone or two columns, draw the same picture kind.
- Design tokens: no `PathEffect.dashPathEffect` and no `DotPanel` under `ui/`, every `RoundedCornerShape` on a card
  is the card corner, every `fontSize` comes from `GlowType`, and every `DotMatrixText` dot is one of the six sizes.
  Fails with file and line.
- Light: `GlowPalette` keeps white text at 4.5:1 or more on the body of every metric colour, and L4 reaches the body
  at 45% of the card.
- Punctuation: a unit test that scans user-facing string literals under `ui/` and `data/*Copy*.kt` for `·`, `;` and
  `—` and fails with file and line.
- Existing tests keep passing. No algorithm test changes, since no algorithm changes.

### 8.3 Removed

`DotPanel`, `DotHeader`, every dotted or dashed `PathEffect`, `MetricColors.chargeScale`, `chargeAt`, `chargeDeep`,
`readinessDeep` and `bad`, the cubic fill ease in `GlowCard`, `GlowMotion.Drift`, `.Breath`, `.Stride`, `FocusTarget`, the You tab, the Heart rate card's
duplicate live switch on Today (it stays in the Heart rate detail), the dead `dashboard/ChargeCard.kt` and
`dashboard/NapsPanel.kt`.

---

## 9. Open questions for hardware

These don't block the build but need a real strap:

- Breathing disturbances units (keep the "Early estimate" marker until confirmed).
- Whether the strap's charging state is reliable enough to drive the chip text.
- How long a stuck morning sync usually lasts, to tune the 3 h escalation.
