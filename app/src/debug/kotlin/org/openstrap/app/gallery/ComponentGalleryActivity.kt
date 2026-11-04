package org.openstrap.app.gallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openstrap.app.ui.glow.CardSize
import org.openstrap.app.ui.glow.draw.CoreShape
import org.openstrap.app.ui.glow.DotMatrixText
import org.openstrap.app.ui.glow.draw.DotSize
import org.openstrap.app.ui.glow.EdgeCard
import org.openstrap.app.ui.glow.draw.EdgeKind
import org.openstrap.app.ui.glow.draw.EdgeStatus
import org.openstrap.app.ui.glow.GlowCard
import org.openstrap.app.ui.glow.GlowGrid
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowGridRow
import org.openstrap.app.ui.glow.draw.GlowMood
import org.openstrap.app.ui.glow.draw.GlowMotion
import org.openstrap.app.ui.glow.draw.GlowStyle
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.GridCard
import org.openstrap.app.ui.glow.packGrid
import org.openstrap.app.ui.glow.draw.LightAngle
import org.openstrap.app.ui.glow.ListPanel
import org.openstrap.app.ui.glow.ListRow
import org.openstrap.app.ui.glow.LocalGlowMood
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.theme.OpenStrapTheme
import androidx.compose.foundation.layout.Box
import org.openstrap.app.data.Phase
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.presentation.WorkoutWords
import org.openstrap.app.ui.glow.LineDots
import org.openstrap.app.ui.glow.PeakBars
import org.openstrap.app.ui.glow.PlainLine
import org.openstrap.app.ui.glow.StatusDot
import org.openstrap.app.ui.glow.TrendBad
import org.openstrap.app.ui.glow.TrendGood
import org.openstrap.app.ui.glow.live.EffortBar
import org.openstrap.app.ui.glow.live.MindRing
import org.openstrap.app.ui.glow.live.MovingTime
import org.openstrap.app.ui.glow.live.RestRing
import org.openstrap.app.ui.glow.live.RoundBar
import org.openstrap.app.ui.glow.live.WorkoutHeartCard
import org.openstrap.app.ui.glow.live.ZoneTimeBar
import org.openstrap.app.ui.workout.IntervalMoment
import org.openstrap.app.ui.workout.ZoneSeconds

/**
 * Debug builds only: every design v2 foundation piece on one scrolling screen, to check them on a phone, then the live
 * workout's parts with fixed numbers, which an emulator can't show otherwise (no strap, no workout).
 * `adb shell am start -n org.openstrap.app.dev/org.openstrap.app.gallery.ComponentGalleryActivity` (the dev build), with
 * `--es section live` for the live parts alone.
 */
class ComponentGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val liveOnly = intent.getStringExtra("section") == "live"
        setContent { OpenStrapTheme { Gallery(liveOnly) } }
    }
}

/** A glow card style as the v2 mockups draw it: a near-black oval core, light from [angle]. */
private fun glow(color: Color, angle: Float? = null) = GlowStyle(
    color = color,
    coreShape = CoreShape.Radial,
    coreWidth = 0.95f, coreHeight = 0.85f, coreBlur = 26.dp, coreLightness = 0.03f,
    toneShift = -12f, toneWidth = 14.dp,
    glowSize = 26.dp, glowStrength = 0.85f,
    lightAngle = angle,
)

private val Charge = GlowStyle.Ember
private val SmallPadding = GlowLayout.CardPadding

@Composable
private fun Gallery(liveOnly: Boolean) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Inset the scrolling area itself, so nothing scrolls under the status or navigation bar.
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = GlowGrid.Gutter, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(GlowGrid.Gap),
    ) {
        if (liveOnly) {
            LiveParts()
            return@Column
        }
        Heading("Glow cards, light angle")
        Row(horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
            AngleCard("Waking up", "Lower left", MetricColors.readiness, LightAngle.LowerLeft, Modifier.weight(1f))
            AngleCard("Midday", "Above", MetricColors.temperature, LightAngle.Above, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
            AngleCard("Workout time", "Upper right", MetricColors.heart, LightAngle.UpperRight, Modifier.weight(1f))
            AngleCard("Evening", "Lower right", Color(0xFF7C6CF0), LightAngle.LowerRight, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
            AngleCard("After your run", "Below", MetricColors.load, LightAngle.Below, Modifier.weight(1f))
            GlowCard(
                glow(MetricColors.heart).copy(motion = GlowMotion.Heartbeat(64)),
                Modifier.weight(1f).height(GlowGrid.UnitHeight),
                contentPadding = SmallPadding,
            ) { GlowText("Heart rate", "64", "Live, beating") }
        }

        Heading("Glow cards, light level")
        // Tall cards (one unit wide, two high), as Charge sits on the grid.
        listOf(listOf(0f, 0.3f), listOf(0.7f, 1f)).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
                pair.forEach { level ->
                    GlowCard(
                        Charge.copy(lightLevel = level),
                        Modifier.weight(1f).height(GlowGrid.height(CardSize.Tall)!!),
                        contentPadding = SmallPadding,
                    ) { GlowText("Charge", "${(level * 100).toInt()}", "Light level") }
                }
            }
        }

        Heading("Hero and second glow card")
        GlowCard(glow(MetricColors.readiness, LightAngle.LowerLeft), Modifier.fillMaxWidth().height(GlowGrid.UnitHeight)) {
            GlowText("Recovery", "78", "A good day for effort")
        }
        GlowCard(Charge.copy(lightLevel = 0.62f, second = true), Modifier.fillMaxWidth().height(GlowGrid.UnitHeight)) {
            GlowText("Charge", "62", "Second card, softer light")
        }

        Heading("Edge light cards")
        Row(horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
            Edge("Resting heart rate", "52", "Usual", MetricColors.heart, EdgeStatus.Usual, EdgeKind.Value, Modifier.weight(1f))
            Edge("Skin temperature", "34.6", "Above your normal", MetricColors.temperature, EdgeStatus.Above, EdgeKind.Value, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
            Edge("HRV", "41", "Below your normal", MetricColors.hrv, EdgeStatus.Below, EdgeKind.Value, Modifier.weight(1f))
            Edge("Steps", "6,420", "80% of your goal", MetricColors.steps, EdgeStatus.Usual, EdgeKind.Progress, Modifier.weight(1f), progress = 0.8f)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
            Edge("Heart rate", "71", "Now", MetricColors.heart, EdgeStatus.Usual, EdgeKind.Live, Modifier.weight(1f))
            Edge("Recovery", "78", "This morning", MetricColors.readiness, EdgeStatus.Usual, EdgeKind.Earlier, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
            Edge("Last night", "7:40", "Asleep", MetricColors.sleepDeep, EdgeStatus.Usual, EdgeKind.Sleep, Modifier.weight(1f))
            Edge("Load", "64", "Above your normal", MetricColors.load, EdgeStatus.Above, EdgeKind.Progress, Modifier.weight(1f), progress = 1f)
        }

        Heading("Grouped list")
        ListPanel(title = "Your body this morning", note = "Against your normal") {
            ListRow("Resting heart rate", MetricColors.heart, "52", "Usual", onClick = {})
            ListRow("HRV", MetricColors.hrv, "41", "Below your normal", offNormal = true, onClick = {})
            ListRow("Blood oxygen", MetricColors.spo2, "97%", "Usual", onClick = {})
            ListRow("Breathing rate", MetricColors.sleepLight, null, "After your first night")
        }

        Heading("Grid rows")
        GlowGridColumn(
            listOf(
                GridCard(CardSize.Large) { m -> GlowCard(glow(MetricColors.readiness, LightAngle.LowerLeft), m) { GlowText("Large", "78", "The hero of the moment") } },
                GridCard(CardSize.Small) { m -> EdgeCard(MetricColors.heart, m, contentPadding = SmallPadding) { CardText("Small", "52", "Usual") } },
                GridCard(CardSize.Small) { m -> EdgeCard(MetricColors.hrv, m, status = EdgeStatus.Below, contentPadding = SmallPadding) { CardText("Small", "41", "Below your normal") } },
                GridCard(CardSize.Tall) { m -> GlowCard(Charge.copy(lightLevel = 0.55f, second = true), m, contentPadding = SmallPadding) { GlowText("Tall", "55", "Charge") } },
                GridCard(CardSize.Small) { m -> EdgeCard(MetricColors.spo2, m, contentPadding = SmallPadding) { CardText("Small", "97%", "Usual") } },
                GridCard(CardSize.Small) { m -> EdgeCard(MetricColors.stress, m, contentPadding = SmallPadding) { CardText("Small", "28", "Usual") } },
                GridCard(CardSize.Wide) { m -> EdgeCard(MetricColors.sleepDeep, m, kind = EdgeKind.Sleep) { CardText("Wide", "7:40", "Last night") } },
                GridCard(CardSize.Strip) { m -> EdgeCard(Color.White, m, contentPadding = PaddingValues(horizontal = 20.dp)) { StripText("Strip", "Start a workout") } },
                GridCard(CardSize.Small) { m -> EdgeCard(MetricColors.habitAge, m, kind = EdgeKind.Earlier) { CardText("A lone small, now wide", "38", "Habit Age") } },
                GridCard(CardSize.List) { m ->
                    ListPanel(m, title = "List") {
                        ListRow("Time to recover", MetricColors.load, "14", "Hours")
                        ListRow("Energy", MetricColors.temperature, "2,140", "Calories so far")
                    }
                },
            ),
        )

        GlowMood.entries.forEach { mood ->
            Heading("Mood: ${mood.name}")
            CompositionLocalProvider(LocalGlowMood provides mood) {
                Row(horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
                    GlowCard(glow(MetricColors.readiness, LightAngle.LowerLeft), Modifier.weight(1f).height(GlowGrid.UnitHeight), contentPadding = SmallPadding) {
                        GlowText("Recovery", "78", "Lower left")
                    }
                    Edge("Last night", "7:40", "Asleep", MetricColors.sleepDeep, EdgeStatus.Usual, EdgeKind.Sleep, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
                    GlowCard(Charge.copy(lightLevel = 0.4f), Modifier.weight(1f).height(GlowGrid.UnitHeight), contentPadding = SmallPadding) {
                        GlowText("Charge", "40", "Level")
                    }
                    Edge("HRV", "41", "Below your normal", MetricColors.hrv, EdgeStatus.Below, EdgeKind.Value, Modifier.weight(1f))
                }
            }
        }
        LiveParts()
        Spacer(Modifier.height(24.dp))
    }
}

/** A run 25 minutes in, recording on the phone, for the live parts. */
private val LiveRun = WorkoutState.Recording(AppWorkout(start = LIVE_START, end = null, sportType = 1), maxHeartRate = 190)

private const val LIVE_START = 1_792_000_000L
private const val LIVE_ACTIVE = 1534L

/** The last ten minutes of [LiveRun] every five seconds, climbing into zone 3 with a little wave. */
private val LiveRecent = (0 until 120).map { i ->
    val t = LIVE_START + LIVE_ACTIVE - 600 + i * 5L
    t to (128 + i / 6 + (i % 9) - 4)
}

private val LiveZones = ZoneSeconds(listOf(120, 420, 780, 180, 34))

/** A calm session's heart rate settling from the high 80s, as the mind screen draws it. */
private val MindLine: List<Float?> = List(120) { i -> 88f - i / 8f + (i % 7) * 0.6f }

/**
 * The live workout's parts (workouts spec 2.3) with fixed numbers: the heart card, the bars, rings and lines the kinds'
 * screens are built from, the top bar's status dots and the moving time.
 */
// The parts' numbers are fixed sample readings, picked to show each part mid workout, not tuning values.
@Suppress("MagicNumber")
@Composable
private fun LiveParts(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
        Heading("Live parts")
        Label("Live heart card, with the zone time bar")
        WorkoutHeartCard(
            LiveRun, 148, LiveRecent, LiveZones, connected = true, active = { LIVE_ACTIVE }, Modifier.fillMaxWidth(), zoneBar = true,
        )
        Label("Live heart card, paused")
        val paused = LiveRun.copy(workout = LiveRun.workout.copy(pausedAt = LIVE_START + LIVE_ACTIVE))
        WorkoutHeartCard(paused, 148, LiveRecent, LiveZones, connected = true, active = { LIVE_ACTIVE }, Modifier.fillMaxWidth())
        Label("Zone time bar, in zone 3")
        ZoneTimeBar(LiveZones, current = 2)
        Label("Effort bar, a game at 3 of 5")
        EffortBar(3)
        Label("Round bar, round 3 of 8 under way")
        RoundBar(IntervalMoment(Phase.Work(round = 3, start = 0, endsAt = 20), round = 3, rounds = 8, left = 8), workLength = 20)
        Label("Peak bars, five rounds of eight")
        PeakBars(
            listOf(158, 166, 171, null, 169), { MetricColors.heart }, Modifier.fillMaxWidth(), height = 56.dp, rounds = 8, corner = 3.dp,
        )
        Label("Rest ring, 45 s of 90 left")
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RestRing({ 45L }, 90L) }
        Label("Mind ring, 10 min of 30")
        MindRing({ 600L }, 1800L)
        Label("Mind heart line")
        PlainLine(MindLine, MetricColors.evening.copy(alpha = 0.7f), 64.dp, smooth = false, dots = LineDots.Last, stroke = 2.dp)
        Label("Status dots")
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
            StatusDot("GPS good", TrendGood, dotAfter = true)
            StatusDot("Strap 82%", TrendGood, dotAfter = true)
            StatusDot(WorkoutWords.STRAP_GONE, TrendBad, dotAfter = true)
        }
        Label("Moving time")
        MovingTime { LIVE_ACTIVE }
    }
}

/** What the part under it is, small. */
@Composable
private fun Label(text: String) {
    Text(
        text, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** Packs [cards] with [packGrid] and lays the rows out one under the other, [GlowGrid.Gap] apart. */
// The gallery builds its card lists once and never changes them in place.
@Suppress("UnstableCollections", "ComposeUnstableCollections")
@Composable
private fun GlowGridColumn(cards: List<GridCard>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GlowGrid.Gap)) {
        packGrid(cards.map { it.size to it }).forEach { GlowGridRow(it) }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
}

@Composable
private fun AngleCard(title: String, note: String, color: Color, angle: Float, modifier: Modifier) {
    GlowCard(glow(color, angle), modifier.height(GlowGrid.UnitHeight), contentPadding = SmallPadding) {
        GlowText(title, "${angle.toInt()}", note)
    }
}

@Composable
private fun Edge(
    title: String,
    value: String,
    status: String,
    color: Color,
    edgeStatus: EdgeStatus,
    kind: EdgeKind,
    modifier: Modifier,
    progress: Float? = null,
) {
    EdgeCard(color, modifier.height(GlowGrid.UnitHeight), status = edgeStatus, kind = kind, progress = progress, contentPadding = SmallPadding) {
        CardText(title, value, status, valueColor = if (edgeStatus == EdgeStatus.Usual) Color.White else color)
    }
}

/** A title at the top, the number and a status line at the bottom. */
@Composable
private fun ColumnScope.CardText(title: String, value: String, status: String, valueColor: Color = Color.White) {
    Text(title, color = Color.White, style = GlowType.cardTitle)
    Spacer(Modifier.weight(1f))
    DotMatrixText(value, dot = DotSize.Edge, color = valueColor)
    Text(status, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary, modifier = Modifier.padding(top = 6.dp))
}

/** [CardText] for a glow card, whose content is a box. */
@Composable
private fun GlowText(title: String, value: String, status: String) {
    Column(Modifier.fillMaxSize()) { CardText(title, value, status) }
}

@Composable
private fun ColumnScope.StripText(title: String, action: String) {
    Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, style = GlowType.cardTitle, modifier = Modifier.weight(1f))
        Text(action, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
    }
}
