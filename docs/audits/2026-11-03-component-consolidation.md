# Component consolidation list

Branch `refactor/consolidate-components`, cut from `develop` at 4b1b811. 645 composables in `app/src/main`. Paths start at `app/src/main/kotlin/org/openstrap/app/`. Caller counts are call sites found by grep, definitions left out.

The generation line: `ui/glow/PageParts.kt`, `GlowCard`, `EdgeCard` and `GlowType` are current. Anything styled with `MaterialTheme.typography`, the glass surfaces or Material `TextButton` is the older generation.

## 1. Delete (dead or exact copies)

| Component | Where | Callers | Action |
|---|---|---|---|
| `GlowBar` | ui/glow/GlowRecoveryScreen.kt:379 | 0 | Delete (only named in KDoc in GlowHealthspanScreen) |
| `GlowGridColumn` | ui/glow/GlowGrid.kt:111 | 0 in main | Delete with its debug gallery entry |
| `FigureRow` | ui/glow/TabParts.kt:172 | 0 | Delete (the 3 refs were the widget's own `FigureRow`) |
| `Title` | ui/firmware/FirmwareScreen.kt:286 | 6 | Replace with `CardTitle` |
| `FieldLabel` | ui/device/ProfileScreen.kt:226 | 3 | Replace with `PageNote` |
| `SectionLabel` + `ui/components/Cards.kt` | ui/components/Cards.kt:13 | 1 | Delete the file. Restyle DayCalendar:96 with GlowType. `PanelShape` there is an alias of `GlowLayout.CardShape` |
| `GlassAlertDialog` | ui/components/Glass.kt:60 | 2 | Move DeleteWorkout and BluetoothBlockedDialog to `PageConfirm`, then delete |
| `LoadCard(best)` param | ui/glow/GlowWorkoutSummary.kt:344 | all 3 pass null | Drop the param |

## 2. Fold into an existing canonical

| Canonical | Fold in | Note |
|---|---|---|
| `PageButton` PageParts.kt:398 (79) | `HeroButton` TodayCards.kt:118 (6), `HeroSecondaryButton` TodayCards.kt:134 (2), `RowPill` TabParts.kt:156 (2) | Old Material type, 48 dp vs 52 dp. RowPill needs a small size |
| `PageNote` PageParts.kt:589 (31) | `Note` GlowRecoveryScreen.kt:398 (26) | Same colour, bodySmall vs GlowType.secondary. Check the size change on screen |
| `PageRow` PageParts.kt:187 (51) | `LinkRow` TabParts.kt:142 (3) | Only padding differs |
| `PageChip` PageParts.kt:569 | `UnitChip` ReadyCardio.kt:817 (1), `LiveChip` GlowMetricDetail.kt:266 (1) | LiveChip fills when off |
| `QuietPanel` GlowQuiet.kt:40 (13) | `PagePanel` PageParts.kt:152 (9) | Same surface, hairline, padding. Title row becomes `PanelTitle` |
| `GlowHeader` GlowLayout.kt:424 | `PanelTitle` GlowQuiet.kt:62 (7) | Check the chevron and warning extras |
| `RecoveryFailedHero` GlowRecoveryScreen.kt:216 | `FailedHero` GlowTodayScreen.kt:483 (1) | Identical body, pass style and spacing |
| `SummaryHeroCard` GlowWorkoutSummary.kt:224 | `WorkoutHero` GlowWorkoutScreen.kt:252 (1) | Needs header/clock vs unit/figures slots |
| `DetailFigures` GlowMetricDetail.kt:363 (8) | `SummaryFigures` SummarySections.kt:164 (2), maybe `LiveFigures` / `LiveStat` | Value style and missing text as params |
| `SportTile` ChooseScreen.kt:207 | `SportTile` GlowActivityScreen.kt:237 | Same 64 dp tile. Make internal with `selected`, `outdoor` |
| `ZoneBar` GlowCharts.kt:795 (3) | `ZoneStrip` SummarySections.kt:448, `ZoneStrip` ShareCards.kt:550, `ZoneTimeBar` live/LiveIndoor.kt:87 (2), `StageBar` GlowSleepScreen.kt:198 | Build on a generic `SegmentBar`. Keep StageBarLook sizing |
| `RoundBar` live/LiveIntervals.kt:198 (2) | `GoalBar` GoalsAndBests.kt:92 (1), `EffortBar` live/LiveGame.kt:133 (2) | Same canvas loop. Add per segment colour |
| `GlowBars` GlowCharts.kt:268 (3) | `KcalBars` ActivityDetail.kt:734 (2), `StackedDayBars` ActivityDetail.kt:207 (1) | One stacked bar chart, goal line and oldest day shade as params |
| `NormalTrend` GlowCharts.kt:681 (6) | `TrendDots` GlowCharts.kt:648 (1), maybe `GlowTrend` GlowCharts.kt:329 (1) | Keep GlowTrend if the stem look is wanted |
| `WeekDots` SummarySections.kt:644 (3) | `SetDots` live/LiveSets.kt:328 (2) | Same dots, no letters |
| `DotMatrixText` | `StoryNumber` ShareCards.kt:381 (3) | Add a `maxWidth` fit param, then delete |

## 3. Merge into one new shared component

| New | Replaces | Note |
|---|---|---|
| `RoundIconButton(icon, label, size, surface, dot)` | `RoundButton` MomentHeader.kt:132, `RoundButton` GlowMetricDetail.kt:280, `InfoButton` GlowLayout.kt:318, `BackButton` PageParts.kt:100 as a wrapper | Only size and fill differ, about 13 call sites |
| `IconPill(icon or dot, label, caret, onClick)` | `StrapChipButton` MomentHeader.kt:208, `RecentChipButton` ChooseScreen.kt:183, `PlacePill` ReadyCardio.kt:213, `KindPill` ReadyCardio.kt:369, `Pill` GlowWorkoutMode.kt:214 | 36 to 44 dp pills, height and fill differ |
| `FillBar(share, color, height)` | `ProgressBar` EdgeCard.kt:111, `GoalBar` ActivityDetail.kt:656, `StageBars` SleepDetail.kt:375, `SplitBars` GlowWorkoutSummary.kt:449 | Same track and fill. EffectBar and PaceScale share its track and dot |
| `ProgressRing(share, color, stroke)` | `MindRing` live/LiveMind.kt:127, `RestRing` live/LiveSets.kt:273, `RowRing` GlowList.kt:206 | Same track and sweep from minus 90 degrees |
| `PlainLine(values, color, smooth, dots)` | `SmoothLine` SummarySections.kt:555, `MindHeartLine` live/LiveMind.kt:160, `CalmLine` ShareCards.kt:619 | Axis free lines with an end dot |
| `drawZoneLine` helper | used by `ZoneHeartLine`, `RangeChart`, `DropLine` | Keep the three composables |
| `Legend(items, swatch, flow)` | `KcalLegend` ActivityDetail.kt:799, `StageLegend` GlowSleepScreen.kt:209, inline legend in `ZoneStripPanel` | Circle vs square swatch |
| `LitPanel(color, lightCenter, reach)` | hand built in `MaxHero` HeartZonesScreen.kt:84, `ReadyHeartCard` ReadyCardio.kt:105 | Radial light, so not EdgeCard |
| `HeroValue(value, unit)` | hand written in GlowWorkoutSummary:233 and :277, GoalsAndBests:82, ReadyCardio:125, HeartZonesScreen:98, WorkoutCapsule:177, StrapHero | Gap 8 to 14 dp varies |
| `effortGlow(color, effort)` | style built three ways in WorkoutHero, SummaryHeroCard, CalmDownHero | |
| `RelatedPanel` + score row helper | `RelatedList`, `ScoreRelated`, `SleepRelated`, `ActivityRelated` | Recovery and Sleep score rows copied word for word |
| `PeakBars` | `PeakColumns` ShareCards.kt:584, `RoundPeaks` live/LiveIntervals.kt | One Row of boxes, one Canvas |
| shared night bucketing | `ScrubLine` SleepDetail.kt:428, `NightChart` GlowMetricDetail.kt:584 | Same 5 minute buckets and headline |

## 4. Home screen widgets (Glance, can't share with app Compose)

| Helper | Copies | Action |
|---|---|---|
| `Gap` | Moment:175, Recovery:95, Sleep:106, Steps:110, identical | Move to WidgetParts.kt. StartWidget also writes the same Spacer inline 26 times |
| `Edge` | Recovery:99, Sleep:110, Steps:114 | Move as `Edge(spread, f)` beside `Spread` |
| Moment `edge()` | MomentWidget.kt:117 | Same maths as `Spread`. Give Moment's Fit a Spread |
| `Figure` cell | Recovery `Figures` inner column, Start `Figure` StartWidget.kt:536 | Move as `FigureCell`, keep each wrapper |
| `ScoreRow`, `NumberRow`, `StageBar`, `GoalBar`, `UsualRow`, Small/Wide/LargeContent | | Keep. They differ or only share a name |
| `StatusButtonView`, `StatusWords`, `glowBackground`, `edgeBackground`, `openStrap` | WidgetParts.kt | Make private |

## 5. Rename only (same name, different job)

- `LoadCard` to `DayLoadCard` (GlowActivityScreen.kt:337) and `WorkoutLoadCard` (GlowWorkoutSummary.kt:344)
- `FactsList` in GlowActivityScreen.kt:357 and GlowWorkoutScreen.kt:374
- `SplitBars` live/LiveDistance.kt:359 (keep, too specific) vs the one folding into FillBar

## 6. Keep as is

`GlowCard`, `EdgeCard`, `PageHeader`, `GlowHeader`, `ListRow`, `PageRow`, `PageSwitchRow`, `GlowSwitch`, `PageSheet` / `GlassSheet`, `WorkoutButton`, `FlatButton`, `BarPill`, `PageTextButton`, `TypedNumber`, `NoteText`, `GlowLineChart`, `ArcGauge`, `HorizonArc`, `ZoneArc`, `PageDots`, `WeekStrip`, `HeroNumber`, `HeroFigure`, `DetailScoreNumber`, `DetailHero` / `DetailTemplate`, Today heroes, live cards, Ready plan cards, `Modifier.scrub`, `CardLooks.kt` / `TodayLooks.kt` (card look rules).

Small moves: `OptionButton` (ZeppSignIn.kt:285) and `NumberField` (ProfileScreen.kt:209) to PageParts.kt. `DayCalendarSheet` restyle off Material type. Check whether `KeepConnectedRow` (DeviceScreen.kt:282) is just a `PageSwitchRow` and whether `RestCard` can use GlowCard's outline style.
