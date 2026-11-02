package org.openstrap.app.widget

import org.openstrap.app.data.DetailRoutes
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.text.Text
import org.openstrap.app.ui.glow.draw.EdgeKind
import org.openstrap.app.ui.glow.draw.EdgeStatus
import org.openstrap.app.ui.glow.draw.GlowMood
import org.openstrap.app.ui.glow.draw.WeekBarsLook
import org.openstrap.app.ui.glow.draw.drawWeekBars
import org.openstrap.app.ui.glow.draw.renderToBitmap
import org.openstrap.app.ui.glow.draw.weekBars
import org.openstrap.app.ui.glow.draw.edgeLight
import org.openstrap.app.ui.theme.MetricColors
import java.time.LocalDate

/**
 * The Recovery widget (widgets spec 3.4): this morning's score and word in the same spot every day, with what's
 * behind it on the Wide and the sentence and the last seven days on the Large. An edge card lit from the left, about
 * earlier, in Recovery's ink and the moment's mood, as Today's Recovery card after the morning. The whole widget opens
 * the Recovery screen.
 */
class RecoveryWidget : LoadingWidget<RecoveryWidget.Shown>(WidgetKind.Recovery) {
    override fun shown(data: WidgetData, context: Context): Shown = load(data)

    override fun sample(): Shown = Shown(WidgetSamples.recovery(LocalDate.now()), GlowMood.Day)

    @Composable
    override fun Content(data: Shown) = RecoveryContent(data)

    /** What the widget shows and the light it's lit in. */
    data class Shown(val model: RecoveryWidgetModel, val mood: GlowMood)

    companion object {
        /**
         * Today's Recovery, worked out as the app does ([org.openstrap.app.data.HealthRepository.recovery]), and
         * yesterday's while this morning's isn't in. The mood from the moment then ([widgetMood]).
         */
        internal fun load(data: WidgetData): Shown {
            val read = checkNotNull(data.recovery) { "not read for Recovery" }
            val model = RecoveryWidgetModel.from(read.today, read.yesterday)
            return Shown(model, widgetMood(data, read.today?.let { read.todayResult?.usualBedtime }))
        }
    }
}

/** The Recovery widget's receiver. The first one placed stops Moment leading with Recovery (spec 3.1). */
class RecoveryWidgetReceiver : PlacedWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RecoveryWidget()
}

/* ------------------------------------------------------------------------------------------------ */
/* Layout                                                                                            */
/* ------------------------------------------------------------------------------------------------ */

/** Recovery's ink. */
private val Ink = MetricColors.readiness

/**
 * The widget at the size Glance lays out: the edge light behind, drawn at the cell's real size, then the size's
 * content. Any size the launcher gives lands on one of the three ([WidgetSizes.bucket]).
 */
@Composable
private fun RecoveryContent(data: RecoveryWidget.Shown) {
    val model = data.model
    val open = openMetric(LocalContext.current, DetailRoutes.RECOVERY, model.date)
    EdgeWidget(edgeLight(EdgeStatus.Usual, EdgeKind.Earlier), Ink, data.mood, open, BITMAP_EXTRA) { frame ->
        when {
            model.score == null -> WaitingContent(model.title, model.waiting, frame)
            frame.bucket == WidgetSizes.Small -> SmallContent(model, RecoveryLayout.small(model, frame.contentWidth, frame.contentHeight, frame.fontScale, frame.status), frame)
            frame.bucket == WidgetSizes.Wide -> WideContent(model, RecoveryLayout.wide(model, frame.contentWidth, frame.contentHeight, frame.fontScale, frame.status), frame)
            else -> LargeContent(model, RecoveryLayout.large(model, frame.contentWidth, frame.contentHeight, frame.fontScale, frame.status), frame)
        }
    }
}

/** A gap between blocks: they share the spare height alike, with half a gap above and below ([Edge]). */
@Composable
private fun ColumnScope.Gap() = Spacer(GlanceModifier.defaultWeight())

/** The half gap above the first block or below the last, at the cell's real height. */
@Composable
private fun Edge(fit: RecoveryLayout.Fit, f: WidgetFrame) = Spacer(GlanceModifier.height(fit.spread.edge(f.drawContentHeight).dp))

/** Small: the top row, the score, its word, the dots grown to fill a tall cell. */
@Composable
private fun ColumnScope.SmallContent(m: RecoveryWidgetModel, fit: RecoveryLayout.Fit, f: WidgetFrame) {
    Edge(fit, f)
    TopRowView(m.title, null, fit.top, f, fit.spread.edge(f.drawContentHeight))
    Gap()
    m.score?.let { DotNumber(it, fit.dot, f.density, m.scoreDescription) }
    if (fit.word) m.word?.let {
        Gap()
        Text(it, style = WidgetType.word.copy(fontSize = fit.wordSp.sp), maxLines = 1)
    }
    Edge(fit, f)
}

/** Wide: the top row with "This morning", the score and word, the three figures. */
@Composable
private fun ColumnScope.WideContent(m: RecoveryWidgetModel, fit: RecoveryLayout.Fit, f: WidgetFrame) {
    Edge(fit, f)
    TopRowView(m.title, m.note, fit.top, f, fit.spread.edge(f.drawContentHeight))
    Gap()
    ScoreRow(m, fit.dot, f.density)
    if (fit.figures) {
        Gap()
        Figures(m.figures)
    }
    Edge(fit, f)
}

/** Large: the Wide plus the sentence and the last seven days beside the score. */
@Composable
private fun ColumnScope.LargeContent(m: RecoveryWidgetModel, fit: RecoveryLayout.Fit, f: WidgetFrame) {
    Edge(fit, f)
    TopRowView(m.title, m.note, fit.top, f, fit.spread.edge(f.drawContentHeight))
    if (fit.sentenceLines > 0) m.sentence?.let {
        Gap()
        Text(it, style = WidgetType.hero.copy(fontSize = fit.sentenceSp.sp), maxLines = fit.sentenceLines)
    }
    Gap()
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        ScoreRow(m, fit.dot, f.density)
        if (fit.week) {
            Spacer(GlanceModifier.defaultWeight())
            Week(m.week, fit.weekColumn, f.density)
        }
    }
    if (fit.figures) {
        Gap()
        Figures(m.figures)
    }
    Edge(fit, f)
}

/** The score in dots [dot] dp across with its word beside it, read out as "78, Ready". */
@Composable
private fun ScoreRow(m: RecoveryWidgetModel, dot: Float, density: Float) {
    Row(verticalAlignment = Alignment.Bottom) {
        m.score?.let { DotNumber(it, dot, density, m.scoreDescription) }
        m.word?.let {
            Spacer(GlanceModifier.width(RecoveryLayout.WORD_GAP_DP.dp))
            Text(it, style = WidgetType.word, maxLines = 1)
        }
    }
}

/** Resting HR, HRV and Sleep in a row, each a Caption over a Body value. */
@Composable
private fun Figures(figures: List<RecoveryFigure>) {
    Row(GlanceModifier.fillMaxWidth()) {
        figures.forEachIndexed { i, f ->
            if (i > 0) Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(f.label, style = WidgetType.caption, maxLines = 1)
                Spacer(GlanceModifier.height(2.dp))
                Text(f.value, style = WidgetType.body, maxLines = 1)
            }
        }
    }
}

/** The last seven days as solid bars, the day shown in Recovery's ink, each day's letter under its bar. */
@Composable
private fun Week(week: List<RecoveryWeekDay>, column: Float, density: Float) {
    val width = WeekBarsLook.width(week.size, column)
    val bars = weekBars(RecoveryLayout.barValues(week), density, column)
    val bitmap = renderToBitmap(width * density, WeekBarsLook.HEIGHT_DP * density) { drawWeekBars(it, bars, week.indexOfFirst { d -> d.shown }, Ink) }
    WeekChart(ImageProvider(bitmap), "Recovery each day for the last seven days", week.map { it.letter to it.shown }, column)
}

/* ------------------------------------------------------------------------------------------------ */
/* Bitmaps                                                                                           */
/* ------------------------------------------------------------------------------------------------ */

/** Room for the dots and the week beside the backgrounds, in the bitmap budget. */
private const val BITMAP_EXTRA = 1.25
