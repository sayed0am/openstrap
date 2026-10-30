package org.openstrap.app.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.text.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.openstrap.app.OpenStrapApp
import org.openstrap.app.data.DailyMetric
import org.openstrap.app.data.DayWindow
import org.openstrap.app.data.LiveSteps
import org.openstrap.app.data.Summaries
import org.openstrap.app.ui.glow.EdgeKind
import org.openstrap.app.ui.glow.EdgeStatus
import org.openstrap.app.ui.glow.GlowMood
import org.openstrap.app.ui.glow.draw.WeekBarsLook
import org.openstrap.app.ui.glow.draw.drawGhostLine
import org.openstrap.app.ui.glow.draw.drawPillBar
import org.openstrap.app.ui.glow.draw.ghostLineGeometry
import org.openstrap.app.ui.glow.draw.renderToBitmap
import org.openstrap.app.ui.glow.draw.weekBars
import org.openstrap.app.ui.glow.draw.weekStub
import org.openstrap.app.ui.glow.edgeLight
import org.openstrap.app.ui.theme.MetricColors
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * The Steps widget (widgets spec 3.3): today's steps against the goal, glanceable all day. The Wide adds what's to go
 * and the day so far as the Steps card's line, the Large this week day by day. An edge card lit from the bottom, a
 * value, in Steps' lime and the moment's mood, as Today's Steps card. The whole widget opens the Steps detail.
 */
class StepsWidget : LoadingWidget<StepsWidget.Shown>(WidgetKind.Steps) {
    override suspend fun loadShown(context: Context): Shown = load(context)

    override fun sample(): Shown = Shown(WidgetSamples.steps(java.time.LocalDate.now()), GlowMood.Day)

    @Composable
    override fun Content(data: Shown) = StepsContent(data)

    /** What the widget shows and the light it's lit in. */
    data class Shown(val model: StepsWidgetModel, val mood: GlowMood)

    companion object {
        /**
         * Today's steps as Today's Steps card counts them: the synced minutes with the strap's live total on top
         * ([LiveSteps]), the day's line from the minutes hour by hour, the goal from settings, and this week's earlier
         * days from the daily totals the app's week shows. The mood from the moment now ([widgetMood]).
         */
        internal suspend fun load(context: Context, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Shown {
            val container = (context.applicationContext as OpenStrapApp).container
            val at = now.atZone(zone)
            val today = at.toLocalDate()
            val goal = container.settings.goals.first().steps
            val model = try {
                val day = DayWindow.of(today, zone)
                val minutes = container.database.healthDao().activityOnce(day.from, day.to)
                val steps = LiveSteps().today(Summaries.totalSteps(minutes), container.strap.todaySteps.value)
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val past = if (monday < today) {
                    container.health.daily(DailyMetric.Steps, monday, today.minusDays(1), zone).first()
                        .filter { it.date < today }.associate { it.date to it.value.roundToInt() }
                } else emptyMap()
                StepsWidgetModel.from(today, steps, Summaries.hourlySteps(minutes, day.from), at.hour, goal, past)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(WIDGET_TAG, "widgets: steps for $today not read: $e")
                StepsWidgetModel.from(today, 0, List(24) { 0f }, at.hour, goal, emptyMap())
            }
            val bedtime = try {
                container.health.usualBedtime(today)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            return Shown(model, widgetMood(container, bedtime, now, zone))
        }
    }
}

/** The Steps widget's receiver. */
class StepsWidgetReceiver : PlacedWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StepsWidget()
}

/* ------------------------------------------------------------------------------------------------ */
/* Layout                                                                                            */
/* ------------------------------------------------------------------------------------------------ */

/** Steps' lime, as Today's Steps card. */
private val Ink = MetricColors.steps

/** The goal bar's track (the mockup's): white at 10%. */
private const val TRACK_ALPHA = 0.10f

/** The widget at the size Glance lays out, lit from the bottom as Today's Steps card ([EdgeKind.Progress]). */
@Composable
private fun StepsContent(data: StepsWidget.Shown) {
    val m = data.model
    val open = openMetric(LocalContext.current, STEPS_ROUTE, m.date)
    EdgeWidget(edgeLight(EdgeStatus.Usual, EdgeKind.Progress), Ink, data.mood, open, BITMAP_EXTRA) { frame ->
        when (frame.bucket) {
            WidgetSizes.Small -> SmallContent(m, frame)
            WidgetSizes.Wide -> WideContent(m, frame)
            else -> LargeContent(m, frame)
        }
    }
}

/** A gap between blocks: they share the spare height alike, with half a gap above and below. */
@Composable
private fun ColumnScope.Gap() = Spacer(GlanceModifier.defaultWeight())

/** The half gap above the first block or below the last, at the cell's real height. */
@Composable
private fun Edge(spread: Spread, f: WidgetFrame) = Spacer(GlanceModifier.height(spread.edge(f.drawContentHeight).dp))

/** Small: the top row, then the steps with "today", then the goal bar and the share of the goal. */
@Composable
private fun ColumnScope.SmallContent(m: StepsWidgetModel, f: WidgetFrame) {
    val fit = StepsLayout.small(m.stepsText, m.share, f.contentWidth, f.contentHeight, f.fontScale, f.status)
    Edge(fit.spread, f)
    TopRowView(m.title, null, fit.top, f, fit.spread.edge(f.drawContentHeight))
    Gap()
    // Each block one child of the column: Glance drops a column's children past ten.
    Column(GlanceModifier.fillMaxWidth()) {
        DotNumber(m.stepsText, fit.dot, f.density, m.description)
        if (fit.word) {
            Spacer(GlanceModifier.height(StepsLayout.SMALL_WORD_GAP_DP.dp))
            Text(m.word, style = WidgetType.word, maxLines = 1)
        }
    }
    Gap()
    Column(GlanceModifier.fillMaxWidth()) {
        GoalBar(m.progress, f)
        if (fit.share) {
            Spacer(GlanceModifier.height(StepsLayout.SMALL_BAR_GAP_DP.dp))
            Text(m.share, style = WidgetType.secondary, maxLines = 1)
        }
    }
    Edge(fit.spread, f)
}

/** Wide: the top row with the goal, the steps with what's to go, the day so far as a line (or the goal bar). */
@Composable
private fun ColumnScope.WideContent(m: StepsWidgetModel, f: WidgetFrame) {
    val fit = StepsLayout.wide(m, f.contentWidth, f.contentHeight, f.fontScale, f.status)
    Edge(fit.spread, f)
    TopRowView(m.title, m.goalNote, fit.top, f, fit.spread.edge(f.drawContentHeight))
    Gap()
    NumberRow(m, fit.row, f)
    Gap()
    StepsLine(m, fit.line, f)
    Edge(fit.spread, f)
}

/** Large: the Wide plus this week, day by day against the goal. */
@Composable
private fun ColumnScope.LargeContent(m: StepsWidgetModel, f: WidgetFrame) {
    val fit = StepsLayout.large(m, f.contentWidth, f.contentHeight, f.fontScale, f.status)
    Edge(fit.spread, f)
    TopRowView(m.title, m.goalNote, fit.top, f, fit.spread.edge(f.drawContentHeight))
    Gap()
    NumberRow(m, fit.row, f)
    Gap()
    StepsLine(m, fit.line, f)
    Gap()
    fit.week?.let { WeekRow(m, it, f.density) }
    Edge(fit.spread, f)
}

/** The steps in dots with "today" beside them when it fits, what's to go (or "Goal met") on the right. */
@Composable
private fun NumberRow(m: StepsWidgetModel, row: StepsLayout.NumberRow, f: WidgetFrame) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        DotNumber(m.stepsText, row.dot, f.density, m.description)
        if (row.word) {
            Spacer(GlanceModifier.width(StepsLayout.WORD_GAP_DP.dp))
            Text(m.word, style = WidgetType.word, maxLines = 1)
        }
        Spacer(GlanceModifier.defaultWeight())
        Text(m.toGo, style = WidgetType.secondary, maxLines = 1)
    }
}

/**
 * The day so far as the Steps card's line, solid in lime and ending in its dot, [height] dp high with the goal at the
 * top, so it fills as the day reaches the goal. The goal bar instead when there's no room or no step yet.
 */
@Composable
private fun StepsLine(m: StepsWidgetModel, height: Float?, f: WidgetFrame) {
    if (!m.showLine || height == null) {
        // No line (a large font, or none to draw): the goal bar shows the progress instead.
        GoalBar(m.progress, f)
        return
    }
    val w = f.drawContentWidth * f.density
    val h = height * f.density
    val bitmap = renderToBitmap(w, h) { c ->
        ghostLineGeometry(m.line, null, m.lineMax, w, h, f.density)?.let { drawGhostLine(c, it, Ink, f.density) }
    }
    Image(
        ImageProvider(bitmap),
        contentDescription = tr(R.string.steps_widget_steps_so_far_today, m.share),
        modifier = GlanceModifier.fillMaxWidth().height(height.dp),
        contentScale = ContentScale.FillBounds,
    )
}

/** The goal bar: 4 dp, lime on white at 10%, as far as the goal, full past it. */
@Composable
private fun GoalBar(progress: Float, f: WidgetFrame) {
    Image(
        ImageProvider(goalBar(progress, f.drawContentWidth, f.density)),
        contentDescription = null,
        modifier = GlanceModifier.fillMaxWidth().height(StepsLayout.BAR_DP.dp),
        contentScale = ContentScale.FillBounds,
    )
}

/** "This week" and how many days met the goal on one line, then a bar a day Monday to Sunday under it. */
@Composable
private fun WeekRow(m: StepsWidgetModel, week: StepsLayout.Week, density: Float) {
    // One child of the Large's column: Glance drops a column's children past ten.
    Column(GlanceModifier.fillMaxWidth()) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            if (week.title) {
                Text(StepsWidgetModel.THIS_WEEK, style = WidgetType.secondaryShown, maxLines = 1)
                Spacer(GlanceModifier.defaultWeight())
            }
            Text(m.weekLine, style = WidgetType.secondary, maxLines = 1)
        }
        Spacer(GlanceModifier.height(StepsLayout.WEEK_LINE_GAP_DP.dp))
        WeekChart(ImageProvider(weekBitmap(m, week.column, density)), tr(R.string.steps_widget_steps_each_day_this), m.week.map { it.letter to (it.state == StepsDay.Today) }, week.column)
    }
}

/* ------------------------------------------------------------------------------------------------ */
/* Bitmaps                                                                                           */
/* ------------------------------------------------------------------------------------------------ */

/** The route `open_metric` takes for the Steps detail ([org.openstrap.app.ui.dashboard.Detail.fromRoute]). */
internal val STEPS_ROUTE: String get() = tr(R.string.common_steps)

/** Room for the dots, the line, the bar and the week beside the backgrounds, in the bitmap budget. */
private const val BITMAP_EXTRA = 1.35

/** The goal bar [width] dp wide at [density]: the track, then the fill as far as [progress]. */
private fun goalBar(progress: Float, width: Float, density: Float): Bitmap {
    val w = width * density
    val h = StepsLayout.BAR_DP * density
    val r = h / 2
    return renderToBitmap(w, h) { c ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawRoundRect(0f, 0f, w, h, r, r, paint.apply { color = Color.White.copy(alpha = TRACK_ALPHA).toArgb() })
        val fill = w * progress.coerceIn(0f, 1f)
        if (fill > 0f) c.drawRoundRect(0f, 0f, fill.coerceAtLeast(h), h, r, r, paint.apply { color = Ink.toArgb() })
    }
}

/** This week's bars: a met day lime, a missed one white at 30%, today white, a day to come a stub at 12%. */
private fun weekBitmap(m: StepsWidgetModel, column: Float, density: Float): Bitmap {
    val bars = weekBars(StepsWidgetModel.barValues(m.week, m.goal), density, column)
    return renderToBitmap(WeekBarsLook.width(m.week.size, column) * density, WeekBarsLook.HEIGHT_DP * density) { c ->
        m.week.forEachIndexed { i, d ->
            when (d.state) {
                StepsDay.ToCome -> drawPillBar(c, weekStub(i, density, column), Color.White.copy(alpha = WeekBarsLook.STUB_ALPHA))
                StepsDay.NoRecord -> Unit
                StepsDay.Met -> bars[i]?.let { drawPillBar(c, it, Ink) }
                StepsDay.Missed -> bars[i]?.let { drawPillBar(c, it, Color.White.copy(alpha = WeekBarsLook.DIM_ALPHA)) }
                StepsDay.Today -> bars[i]?.let { drawPillBar(c, it, Color.White) }
            }
        }
    }
}
