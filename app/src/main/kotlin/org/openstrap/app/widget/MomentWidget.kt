package org.openstrap.app.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.openstrap.app.MainActivity
import org.openstrap.app.OpenStrapApp
import org.openstrap.app.data.DayWindow
import org.openstrap.app.data.MomentResolver
import org.openstrap.app.data.SleepHistory
import org.openstrap.app.data.Summaries
import org.openstrap.app.data.LiveSteps
import org.openstrap.app.data.Workouts
import org.openstrap.app.device.WidgetUpdater
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.ui.components.ChartPoint
import org.openstrap.app.ui.dashboard.SLEEP_NIGHTS
import org.openstrap.app.ui.dashboard.TabCopy
import org.openstrap.app.ui.dashboard.TodayCopy
import org.openstrap.app.ui.dashboard.usualSport
import org.openstrap.app.ui.glow.draw.drawGhostLine
import org.openstrap.app.ui.glow.draw.drawStageStrip
import org.openstrap.app.ui.glow.draw.ghostLineGeometry
import org.openstrap.app.ui.glow.draw.renderToBitmap
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.SportIcons
import java.time.Instant
import java.time.ZoneId

/**
 * The Moment widget (widgets spec 3.1): what matters now, following the day as Today's hero does, from the same
 * moment, sentences and numbers. A glow card in the moment's light and mood, never leading with a metric whose own
 * widget is placed. The whole widget opens Today, or the live screen while a workout records; in the workout window
 * its Start opens Ready with your usual sport. It refreshes at each moment's end and, while it counts minutes, every
 * five ([WidgetUpdater.scheduleMoment]).
 */
class MomentWidget : LoadingWidget<MomentWidgetModel>(WidgetKind.Moment) {
    override suspend fun loadShown(context: Context): MomentWidgetModel = load(context)

    override fun sample(): MomentWidgetModel = java.time.ZoneId.systemDefault().let { WidgetSamples.moment(java.time.LocalDate.now(it), it) }

    @Composable
    override fun Content(data: MomentWidgetModel) = MomentContent(data)

    companion object {
        /**
         * Everything Today's hero reads, from the database and the app's own sums: the moment's inputs
         * ([widgetMomentInputs]), today's Recovery and Energy ([org.openstrap.app.data.HealthRepository.recovery]),
         * last night as the Sleep tab reads it, the workouts, the day's heart rate and steps. Then the alarm for the
         * next change, from the same inputs.
         */
        internal suspend fun load(context: Context, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): MomentWidgetModel {
            val container = (context.applicationContext as OpenStrapApp).container
            val today = now.atZone(zone).toLocalDate()
            val t = now.epochSecond
            val dao = container.database.healthDao()
            suspend fun <T> read(what: String, default: T, block: suspend () -> T): T = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(WIDGET_TAG, "widgets: $what for Moment not read: $e")
                default
            }
            val profile = container.settings.profile.first()
            val rec = read("Recovery", null) { container.health.recovery(today, profile) }
            val bedtime = rec?.usualBedtime ?: read("bedtime", null) { container.health.usualBedtime(today) }
            val inputs = widgetMomentInputs(container, bedtime, now, zone)
            val dayStart = DayWindow.of(today, zone).from
            val strapScore = read("the strap's score", null) { dao.strapReadinessOnce(dayStart, dayStart).firstOrNull()?.score }
            val night = read("last night", null) {
                val span = DayWindow(DayWindow.nightEndingOn(today.minusDays(SLEEP_NIGHTS - 1L), zone).from, DayWindow.nightEndingOn(today, zone).to)
                val sessions = dao.sleepSessionsOnce(span.from, span.to)
                SleepHistory.mainSleep(sessions, DayWindow.nightEndingOn(today, zone))?.let { main ->
                    SleepWidgetNight.of(today, main, dao.sleepStagesOnce(main.start), SleepHistory.mainSleeps(sessions, zone))
                }
            }
            // Workouts over the usual workout time's look-back, as Today's usual sport reads them.
            val from = t - MomentResolver.USUAL_WORKOUT_DAYS * 86_400L
            val phone = read("phone workouts", emptyList()) { dao.appWorkoutsOnce(from, t) }
            // The strap's record of a phone workout is that workout, as in the lists ([Workouts.sameWorkout]).
            val strap = read("workouts", emptyList()) { dao.workoutsOnce(from, t) }.filter { Workouts.phoneTwin(it, phone, t) == null }
            val yours = strap.filter { it.subtype != Workouts.SUBTYPE_AUTO_DETECTED && it.sportType != Workouts.GENERIC_ACTIVITY }.map { it.sportType to it.start } +
                phone.filter { it.sportType != Workouts.GENERIC_ACTIVITY }.map { it.sportType to it.start }
            val all = strap.map { it.sportType to it.start } + phone.map { it.sportType to it.start }
            val usual = usualSport(yours) ?: usualSport(all.filter { it.first != Workouts.GENERIC_ACTIVITY }) ?: Workouts.DEFAULT_SPORTS.first()
            val ended = strap.map { MomentWorkout(it.sportType, it.end) } + phone.mapNotNull { w -> w.end?.let { MomentWorkout(w.sportType, it) } }
            val last = ended.filter { it.end <= t }.maxByOrNull { it.end }
            val day = DayWindow.of(today, zone)
            val dayWorkouts = (strap.map { it.start to it.sportType } + phone.map { it.start to it.sportType })
                .filter { (start, sport) -> start in day.from..day.to && sport != Workouts.GENERIC_ACTIVITY }.sortedBy { it.first }.map { it.second }
            val recording = (container.workout.state.value as? WorkoutState.Recording)?.let { MomentRecordingInput(it.workout.sportType, it.workout.start) }
            val minutes = read("steps", emptyList()) { dao.activityOnce(day.from, day.to) }
            val hr = read("heart rate", emptyList()) { dao.heartRateOnce(day.from, day.to) }
            val series = Summaries.heartRateSeries(hr, minutes)
            val heart = Summaries.bucketAverage(series).map { ChartPoint(it.first, it.second.toFloat()) }
            val resting = read("resting heart rate", null) { dao.restingHrOnce(day.from, day.to).firstOrNull()?.bpm }
                ?: if (series.size >= RESTING_ESTIMATE_MINUTES) Summaries.estimateRestingHr(series) else null
            val input = MomentWidgetInput(
                inputs = inputs,
                placed = WidgetRefresh.placedOverride ?: WidgetUpdater.placed(context),
                recovery = rec?.let { TodayCopy.recoveryShown(it.readiness, strapScore, TabCopy.RECOVERY_WAITING) },
                score = rec?.readiness?.score,
                alert = rec?.readiness?.strainAlert == true,
                noScore = TodayCopy.noScore(rec?.readiness),
                firstNights = rec?.charge?.anchored != true,
                energy = rec?.charge?.let(MomentEnergy::of),
                load = rec?.load,
                loadStatus = rec?.let { TodayCopy.loadStatus(it.load, it.chronicLoad, endOfDay = false) },
                night = night,
                usualSport = usual,
                lastWorkout = last,
                recording = recording,
                dayWorkouts = dayWorkouts,
                heartRate = heart,
                restingHr = resting,
                steps = LiveSteps().today(Summaries.totalSteps(minutes), container.strap.todaySteps.value),
                stepGoal = container.settings.goals.first().steps,
            )
            val forced = WidgetRefresh.momentOverride
            val model = MomentWidgetModel.from(input, now, zone, forced)
            // The debug gallery's forced moment has no end of its own.
            if (forced == null) WidgetUpdater.scheduleMoment(context, inputs, model.tick, now, zone)
            return WidgetRefresh.moodOverride?.let { model.copy(mood = it) } ?: model
        }

        /** Our own resting estimate needs most of a day's minutes, as Today's ([org.openstrap.app.ui.dashboard.DayViewModel]). */
        private const val RESTING_ESTIMATE_MINUTES = 6 * 60
    }
}

/** The Moment widget's receiver. Its first placed sets the moment's alarm as it renders, its last removed clears it. */
class MomentWidgetReceiver : PlacedWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MomentWidget()
}

/* ------------------------------------------------------------------------------------------------ */
/* Taps                                                                                              */
/* ------------------------------------------------------------------------------------------------ */

/** Today, as it is now (spec 4): its root, over whatever tab or page was showing. */
internal fun openToday(context: Context): Intent =
    Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_MOMENT, MainActivity.MOMENT_NOW).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

/* ------------------------------------------------------------------------------------------------ */
/* Layout                                                                                            */
/* ------------------------------------------------------------------------------------------------ */

/** The moment's name: every widget's title (spec 3.1). */
private val NameStyle = WidgetType.title

/** The sport's name while recording: Body 500 on the Small, the hero's light type on the Wide and Large. */
private val SportStyle = WidgetType.body.copy(fontWeight = FontWeight.Medium)

/** Open's word, black on its white pill. */
private val OpenStyle = WidgetType.body.copy(color = ColorProvider(Color.Black), fontWeight = FontWeight.Medium)

/** Room for the dots, the chart, play and the tile beside the backgrounds, in the bitmap budget. */
private const val BITMAP_EXTRA = 1.4

/** The moment's glow behind, then the size's content. */
@Composable
private fun MomentContent(m: MomentWidgetModel) {
    val context = LocalContext.current
    val open = if (m.opensLive) openLiveWorkout(context) else openToday(context)
    // While a workout records, the recording is the news and the live screen shows the strap: no status line.
    GlowWidget(m.light.style(m.mood), open, BITMAP_EXTRA, showStatus = m.recording == null) { f ->
        when (f.bucket) {
            WidgetSizes.Small -> SmallContent(m, f)
            WidgetSizes.Wide -> WideContent(m, MomentLayout.wide(m, f.contentWidth, f.contentHeight, f.fontScale, f.status), f)
            else -> WideContent(m, MomentLayout.large(m, f.contentWidth, f.contentHeight, f.fontScale, f.status), f)
        }
    }
}

/** The half gap above and below at the cell's real height; the gaps between are weights and share the rest. */
private fun edge(fit: MomentLayout.Fit, f: WidgetFrame): Float = fit.edgeGap(f.drawHeight - 2 * WidgetSizes.PADDING_DP)

/** Small: the name, the number (with Start beside it in the workout window), its word; or the recording's. */
@Composable
private fun ColumnScope.SmallContent(m: MomentWidgetModel, f: WidgetFrame) {
    val fit = MomentLayout.small(m, f.contentWidth, f.contentHeight, f.fontScale, f.status)
    val edge = edge(fit, f)
    Spacer(GlanceModifier.height(edge.dp))
    Top(fit, f, edge)
    val r = m.recording
    if (r != null) {
        if (fit.tile > 0f) { Gap(); Tile(r, fit.tile, f) }
        fit.sport?.let { Gap(); Text(it.text, style = SportStyle.copy(fontSize = it.sp.sp), maxLines = 1) }
        fit.since?.let { Gap(); Since(it, f) }
    } else if (fit.dot > 0f) {
        Gap()
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            DotNumber(m.number.orEmpty(), fit.dot, f.density, m.numberDescription)
            if (fit.play) {
                Spacer(GlanceModifier.defaultWeight())
                Play(m, f)
            }
        }
        fit.word?.let { Gap(); Text(it.text, style = WidgetType.word.copy(fontSize = it.sp.sp), maxLines = 1) }
    } else {
        fit.waiting?.let { Gap(); Text(it.text, style = WidgetType.secondary.copy(fontSize = it.sp.sp), maxLines = 1) }
        if (fit.play) { Gap(); Play(m, f) }
    }
    Spacer(GlanceModifier.height(edge.dp))
}

/** Wide and Large: the name, the sentence, the number row, the Large's chart; or the recording's. */
@Composable
private fun ColumnScope.WideContent(m: MomentWidgetModel, fit: MomentLayout.Fit, f: WidgetFrame) {
    val edge = edge(fit, f)
    Spacer(GlanceModifier.height(edge.dp))
    val r = m.recording
    if (r != null) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Name(fit.name)
            Spacer(GlanceModifier.defaultWeight())
            if (fit.open) OpenPill(f)
        }
        if (fit.tile > 0f) { Gap(); Tile(r, fit.tile, f) }
        fit.sport?.let { Gap(); Text(it.text, style = WidgetType.hero.copy(fontSize = it.sp.sp), maxLines = 1) }
        fit.since?.let { Gap(); Since(it, f) }
    } else {
        Top(fit, f, edge)
        fit.sentence?.let { Gap(); Text(it.text, style = WidgetType.hero.copy(fontSize = it.sp.sp), maxLines = MomentLayout.SENTENCE_LINES) }
        fit.row?.let { Gap(); NumberRow(m, it, f) }
        fit.waiting?.let { Gap(); Text(it.text, style = WidgetType.secondary.copy(fontSize = it.sp.sp), maxLines = 1) }
        if (fit.chart > 0f) m.chart?.let { Gap(); Chart(it, fit.chart, f) }
    }
    Spacer(GlanceModifier.height(edge.dp))
}

/** A gap between blocks: they all share the spare height alike. */
@Composable
private fun ColumnScope.Gap() = Spacer(GlanceModifier.defaultWeight())

@Composable
private fun Name(name: MomentLayout.Line) = Text(name.text, style = NameStyle.copy(fontSize = name.sp.sp), maxLines = 1)

/** The top row: the moment's name, with the strap status button at its right when it shows (spec 5), [above] dp under the content's top. */
@Composable
private fun Top(fit: MomentLayout.Fit, f: WidgetFrame, above: Float) {
    val top = fit.top
    if (top != null) TopRowView(fit.name.text, null, top, f, above, NameStyle) else Name(fit.name)
}

/** The dots with the word beside them, the figure or the Start button on the right, bottoms aligned. */
@Composable
private fun NumberRow(m: MomentWidgetModel, row: MomentLayout.NumberRow, f: WidgetFrame) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        DotNumber(m.number.orEmpty(), row.dot, f.density, m.numberDescription)
        row.word?.let {
            Spacer(GlanceModifier.width(MomentLayout.WORD_GAP_DP.dp))
            Text(it.text, style = WidgetType.word.copy(fontSize = it.sp.sp), maxLines = 1)
        }
        Spacer(GlanceModifier.defaultWeight())
        when {
            row.play -> Play(m, f)
            row.figure != null -> Text(row.figure.text, style = WidgetType.secondary.copy(fontSize = row.figure.sp.sp), maxLines = 1)
        }
    }
}

/** The Start button, white with the black play triangle, opening Ready with your usual sport. */
@Composable
private fun Play(m: MomentWidgetModel, f: WidgetFrame) {
    val sport = m.start ?: return
    val context = LocalContext.current
    Image(
        ImageProvider(playBitmap(MomentLayout.PLAY_DP, StartLayout.PLAY_ICON_DP, f.density)),
        contentDescription = m.startLabel,
        modifier = GlanceModifier.size(MomentLayout.PLAY_DP.dp).clickable(actionStartActivity(openReady(context, sport))),
        contentScale = ContentScale.FillBounds,
    )
}

/** The chart [height] dp high across the content, its times under it as Captions. */
@Composable
private fun Chart(chart: MomentChart, height: Float, f: WidgetFrame) {
    val w = f.drawContentWidth * f.density
    val h = height * f.density
    val bitmap = renderToBitmap(w, h) { c ->
        when (chart.kind) {
            MomentChartKind.Stages -> drawStageStrip(c, chart.stages, w, h)
            MomentChartKind.Line -> ghostLineGeometry(chart.shares, null, 1f, w, h, f.density)?.let { drawGhostLine(c, it, Color.White, f.density) }
        }
    }
    Column(GlanceModifier.fillMaxWidth()) {
        Image(
            ImageProvider(bitmap),
            contentDescription = chart.description,
            modifier = GlanceModifier.fillMaxWidth().height(height.dp),
            contentScale = ContentScale.FillBounds,
        )
        Spacer(GlanceModifier.height(MomentLayout.CHART_LABEL_GAP_DP.dp))
        Row(GlanceModifier.fillMaxWidth()) {
            Text(chart.from, style = WidgetType.caption, maxLines = 1)
            Spacer(GlanceModifier.defaultWeight())
            Text(chart.to, style = WidgetType.caption, maxLines = 1)
        }
    }
}

/** The sport's tile, [size] dp, as Start's. */
@Composable
private fun Tile(r: MomentRecording, size: Float, f: WidgetFrame) {
    val corner = StartLayout.TILE_CORNER_DP * size / StartLayout.TILE_DP
    val icon = (size * StartLayout.ICON_DP / StartLayout.TILE_DP).coerceIn(StartLayout.ICON_DP, 28f)
    Image(
        ImageProvider(tileBitmap(SportIcons.of(r.sport.shownType), size, corner, icon, f.density)),
        contentDescription = null,
        modifier = GlanceModifier.size(size.dp),
        contentScale = ContentScale.FillBounds,
    )
}

/** The red recording dot and "Recording since 17:42". */
@Composable
private fun Since(line: MomentLayout.Line, f: WidgetFrame) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            ImageProvider(dotBitmap(MetricColors.heart, f.density)),
            contentDescription = null,
            modifier = GlanceModifier.size(MomentLayout.REC_DOT_DP.dp),
            contentScale = ContentScale.FillBounds,
        )
        Spacer(GlanceModifier.width(MomentLayout.REC_DOT_GAP_DP.dp))
        Text(line.text, style = WidgetType.secondary.copy(fontSize = line.sp.sp), maxLines = 1)
    }
}

/** Open, a white pill as Start's while recording, opening the live screen. */
@Composable
private fun OpenPill(f: WidgetFrame) {
    val context = LocalContext.current
    val w = MomentLayout.openWidth(f.fontScale)
    Box(
        GlanceModifier.size(w.dp, MomentLayout.PLAY_DP.dp)
            .background(ImageProvider(pillBitmap(w, MomentLayout.PLAY_DP, f.density)), ContentScale.FillBounds)
            .clickable(actionStartActivity(openLiveWorkout(context))),
        contentAlignment = Alignment.Center,
    ) { Text(MomentLayout.OPEN, style = OpenStyle, maxLines = 1) }
}
