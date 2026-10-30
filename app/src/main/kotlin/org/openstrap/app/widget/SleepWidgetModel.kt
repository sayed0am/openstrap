package org.openstrap.app.widget

import org.openstrap.app.data.SleepHistory
import org.openstrap.app.data.Summaries
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.ui.components.StageSpan
import org.openstrap.app.ui.dashboard.AFTER_FIRST_NIGHT
import org.openstrap.app.ui.dashboard.SLEEP_NAMES
import org.openstrap.app.ui.dashboard.TabCopy
import org.openstrap.app.ui.dashboard.TodayCopy
import org.openstrap.app.ui.glow.DotSize
import org.openstrap.app.ui.glow.draw.stageTotals
import java.time.LocalDate
import java.time.ZoneId
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * One night as the Sleep tab's hero reads it ([org.openstrap.app.ui.dashboard.DayUiState]): the night's main sleep, its
 * stages and their totals, and the week's other nights for the longest or shortest rule.
 */
data class SleepWidgetNight(
    /** The day the night ended on, which a tap opens the Sleep score on. */
    val date: LocalDate,
    val session: SleepSession,
    val stages: List<StageSpan>,
    val breakdown: Summaries.SleepBreakdown?,
    /** The week's other nights, seconds asleep ([SleepHistory.weekOthers]). */
    val others: List<Long>,
) {
    /** As the hero has it: the stages' time asleep, else the whole session. */
    val asleepSeconds: Long get() = breakdown?.asleepSeconds ?: (session.end - session.start)

    companion object {
        /** The night [main] ended on [date], with its [stages] and the week's [nights] ([SleepHistory.mainSleeps]). */
        fun of(date: LocalDate, main: SleepSession, stages: List<SleepStage>, nights: Map<LocalDate, SleepSession>): SleepWidgetNight = SleepWidgetNight(
            date = date,
            session = main,
            stages = stages.map { StageSpan(it.start, it.end, it.stage) },
            breakdown = if (stages.isEmpty()) null else Summaries.sleepBreakdown(stages),
            others = SleepHistory.weekOthers(nights, date),
        )
    }
}

/** A stage on the stage bar's legend: its colour mark's stage and "Deep 1 h 32". */
data class SleepLegendItem(val stage: Int, val label: String)

/** A row of the Large's stages: "Deep", its [share] of the night as a bar, "1 h 32". */
data class SleepStageRow(val stage: Int, val name: String, val time: String, val share: Float)

/**
 * What the Sleep widget shows (widgets spec 3.2), the same model at every size: the Small shows the title, [score] with
 * [word], [asleep] and the stage bar; the Wide adds [note], [scoreLine] in place of [word] and the [legend]; the Large
 * adds [sentence], [woke] in place of [asleep] and the time in each stage ([stages]) in place of the bar and
 * legend. Without a night there's only the title and [waiting].
 */
data class SleepWidgetModel(
    /** The night's day, which a tap opens the Sleep score's detail on; null while waiting. */
    val date: LocalDate?,
    val title: String,
    /** Bedtime to waking ("22:58–06:56"), or "Last synced night" while this morning's night isn't in. */
    val note: String?,
    /** Yesterday's night, shown while this morning's isn't synced. */
    val stale: Boolean,
    /** The strap's sleep score in dots; null when it gave none. */
    val score: String?,
    /** The Small's word, "Good". */
    val word: String?,
    /** The Wide's and Large's, as the Sleep tab says it: "Sleep score, good", or "No score from the strap". */
    val scoreLine: String?,
    /** "7 h 40 asleep". */
    val asleep: String?,
    /** "Woke 3 times"; null when the strap didn't say. */
    val woke: String?,
    /** The Sleep tab's sentence: "7 h 40, your longest this week." */
    val sentence: String?,
    /** The stage bar's stages and seconds ([stageTotals]), deepest first; empty without stages. */
    val totals: List<Pair<Int, Long>>,
    val legend: List<SleepLegendItem>,
    /** The Large's time in each stage, a row each in [totals]' order. */
    val stages: List<SleepStageRow>,
    /** In place of the night, in Secondary: "After your first night" and the like. */
    val waiting: String?,
) {
    /** What TalkBack reads for the dotted number: "Sleep score 82, good". */
    val scoreDescription: String? get() = score?.let { s -> word?.let { tr(R.string.sleep_widget_model_sleep_score, s, it.lowercase()) } ?: tr(R.string.sleep_widget_model_sleep_score_2, s) }

    /** The Large's right of the score row: how often you woke, else time asleep. */
    val largeRight: String? get() = woke ?: asleep

    companion object {
        val TITLE: String get() = tr(R.string.common_last_night_2)

        /** The title while yesterday's night stands in: it isn't last night. */
        val STALE_TITLE: String get() = tr(R.string.common_sleep)
        val LAST_SYNCED: String get() = tr(R.string.sleep_widget_model_last_synced_night)

        /**
         * The widget for [today]'s night, or [yesterday]'s with "Last synced night" as the note while this morning's
         * isn't synced. Before any: the title and why, "After your first night" before the strap has seen one
         * ([firstNight]), else what the Sleep tab says for a day with no night ([napsToday] as it reads them).
         */
        fun from(today: SleepWidgetNight?, yesterday: SleepWidgetNight?, firstNight: Boolean, napsToday: Boolean, zone: ZoneId = ZoneId.systemDefault()): SleepWidgetModel {
            today?.let { return night(it, stale = false, zone) }
            yesterday?.let { return night(it, stale = true, zone) }
            val line = if (firstNight) AFTER_FIRST_NIGHT else TabCopy.noNight(napsToday, isToday = true)
            return SleepWidgetModel(null, TITLE, null, false, null, null, null, null, null, null, emptyList(), emptyList(), emptyList(), line)
        }

        /** Each stage's name, time and share of the night's stage seconds, as the Sleep score detail lists them. */
        fun stageRows(totals: List<Pair<Int, Long>>): List<SleepStageRow> {
            val all = totals.sumOf { it.second }.coerceAtLeast(1L)
            return totals.map { (stage, seconds) -> SleepStageRow(stage, SLEEP_NAMES[stage].orEmpty(), TodayCopy.duration(seconds), seconds.toFloat() / all) }
        }

        private fun night(n: SleepWidgetNight, stale: Boolean, zone: ZoneId): SleepWidgetModel {
            val s = n.session
            val score = s.score
            // Stage totals as text, never dots (spec 2.3): a stage under a minute leaves its segment and legend out.
            val totals = n.breakdown?.let { stageTotals(it.byStage) }.orEmpty()
            return SleepWidgetModel(
                date = n.date,
                title = if (stale) STALE_TITLE else TITLE,
                note = if (stale) LAST_SYNCED else TabCopy.nightTimes(s.start, s.end, zone),
                stale = stale,
                score = score?.toString(),
                word = score?.let(TabCopy::sleepScoreWord),
                scoreLine = TabCopy.sleepScoreLine(score),
                asleep = TabCopy.asleep(n.asleepSeconds),
                woke = TabCopy.woke(s.wakeCount),
                sentence = TabCopy.night(n.asleepSeconds, n.others),
                totals = totals,
                legend = totals.map { (stage, seconds) -> SleepLegendItem(stage, TabCopy.stageTotal(stage, seconds)) },
                stages = stageRows(totals),
                waiting = null,
            )
        }
    }
}

/**
 * The sums that fit the Sleep widget into its smallest cell at any system font size (spec 2.1): what each size keeps
 * when it can't hold everything, worked out at the real cell's content size ([WidgetFrame.contentWidth]), text with
 * [WidgetText]'s rough sums, dp throughout.
 */
object SleepLayout {
    /** The dots on a Small, and on a Wide or Large (spec 3). */
    val SMALL_DOT = DotSize.Edge.dot.value
    val WIDE_DOT = DotSize.Second.dot.value

    /** Between the score and its word: the Small's, the Wide's. Before what's on the right of the score row. */
    const val SMALL_WORD_GAP_DP = 6f
    const val WORD_GAP_DP = 8f
    const val SIDE_GAP_DP = 12f

    /** Between the Small's rows under the title (the mockup's 8). */
    const val SMALL_GAP_DP = 8f

    /** The stage bar's height: the Small's 6, the Wide's and Large's 8. Then the gap to its legend. */
    const val SMALL_BAR_DP = 6f
    const val BAR_DP = 8f
    const val BAR_GAP_DP = 8f

    /** The legend: Caption size, an 8 dp mark 6 dp before its label, items at least 12 dp apart, rows 6 dp apart. */
    const val LEGEND_SP = WidgetText.CAPTION_SP
    const val MARK_DP = 8f
    const val MARK_GAP_DP = 6f
    const val LEGEND_GAP_DP = 12f
    const val LEGEND_ROW_GAP_DP = 6f

    /**
     * The Large's stage rows: the name in [STAGE_NAME_DP], the bar between, the time in [STAGE_TIME_DP] on the right,
     * [STAGE_GAP_DP] apart, rows [STAGE_ROW_GAP_DP] apart, the bar [STAGE_BAR_DP] high on a white 10% track.
     */
    const val STAGE_NAME_DP = 52f
    const val STAGE_TIME_DP = 56f
    const val STAGE_GAP_DP = 10f
    const val STAGE_ROW_GAP_DP = 6f
    const val STAGE_BAR_DP = 6f
    const val MIN_STAGE_TRACK_DP = 48f

    /** The Large's sentence at most this many lines. */
    const val SENTENCE_LINES = 3

    /** The least room between the blocks that share out the spare height. */
    const val MIN_GAP_DP = 4f

    /* ---------------------------------------------- Legend ---------------------------------------------- */

    /** One legend item's width: mark, gap, label. */
    fun legendItem(label: String, fontScale: Float): Float = MARK_DP + MARK_GAP_DP + WidgetText.width(label, LEGEND_SP, fontScale)

    /**
     * The legend's rows in [width]: 1 when all of [items] fit on one row, 2 (two a row, each in half the width) when
     * not, 0 when even that would wrap a label, so no item ever breaks mid-label.
     */
    fun legendRows(items: List<SleepLegendItem>, width: Float, fontScale: Float): Int {
        if (items.isEmpty()) return 0
        val widths = items.map { legendItem(it.label, fontScale) }
        if (widths.sum() + LEGEND_GAP_DP * (items.size - 1) <= width) return 1
        val half = (width - LEGEND_GAP_DP) / 2
        return if (widths.all { it <= half }) 2 else 0
    }

    /** The legend's height at [rows]. */
    fun legendHeight(rows: Int, fontScale: Float): Float =
        if (rows <= 0) 0f else rows * legendLine(fontScale) + (rows - 1) * LEGEND_ROW_GAP_DP

    private fun legendLine(fontScale: Float) = maxOf(MARK_DP, WidgetText.line(LEGEND_SP, fontScale))

    /* --------------------------------------------- Score row --------------------------------------------- */

    /**
     * The score row: the [dot] the score draws at (0 without a score), the [word] beside it, the [right] text and how
     * many lines the word takes (only "No score from the strap" ever takes two).
     */
    data class ScoreRow(val dot: Float, val word: String?, val right: String?, val wordLines: Int = 1) {
        fun height(fontScale: Float): Float = maxOf(
            if (dot > 0f) WidgetDots.height(dot) else 0f,
            if (word != null) wordLines * WidgetText.line(WidgetText.BODY_SP, fontScale) else 0f,
            if (right != null) WidgetText.line(WidgetText.SECONDARY_SP, fontScale) else 0f,
        )

        /** Its width as laid out with [gap] between the dots and the word, for the tests. */
        fun width(score: String?, fontScale: Float, gap: Float = WORD_GAP_DP): Float =
            (if (dot > 0f && score != null) WidgetDots.width(score, dot) + (if (word != null) gap else 0f) else 0f) +
                (word?.let { WidgetText.width(it, WidgetText.BODY_SP, fontScale) / wordLines } ?: 0f) +
                (right?.let { SIDE_GAP_DP + WidgetText.width(it, WidgetText.SECONDARY_SP, fontScale) } ?: 0f)
    }

    /**
     * The Wide's and Large's score row in [width]: the score at 3.6 dp dots with the tab's "Sleep score, good" and
     * [right]; when that doesn't fit, the dots down to the Small's 3 dp, then the short word ("Good") keeping [right],
     * then without [right]. Without a score, "No score from the strap" with [right] if both fit, else on its own,
     * on up to two lines.
     */
    fun scoreRow(m: SleepWidgetModel, right: String?, width: Float, fontScale: Float, maxDot: Float = WIDE_DOT): ScoreRow {
        val rightW = right?.let { SIDE_GAP_DP + WidgetText.width(it, WidgetText.SECONDARY_SP, fontScale) } ?: 0f
        val score = m.score
        if (score == null) {
            val line = m.scoreLine ?: TabCopy.NO_STRAP_SCORE
            val lineW = WidgetText.width(line, WidgetText.BODY_SP, fontScale)
            if (lineW + rightW <= width) return ScoreRow(0f, line, right)
            return ScoreRow(0f, line, null, WidgetText.lines(line, WidgetText.BODY_SP, width, fontScale).coerceAtMost(2))
        }
        val tries = listOf(m.scoreLine to true, m.word to true, m.scoreLine to false, m.word to false)
        for ((word, withRight) in tries) {
            if (word == null || (withRight && right == null)) continue
            val room = width - (if (withRight) rightW else 0f) - WORD_GAP_DP - WidgetText.width(word, WidgetText.BODY_SP, fontScale)
            val dot = WidgetDots.fit(score, room, maxDot)
            if (room > 0f && dot >= SMALL_DOT) return ScoreRow(dot, word, if (withRight) right else null)
        }
        return ScoreRow(WidgetDots.fit(score, width, maxDot), null, null)
    }

    /* ----------------------------------------------- Small ----------------------------------------------- */

    /** A Secondary line under the Small's score, on [lines] lines. */
    data class SmallLine(val text: String, val lines: Int = 1)

    /**
     * What the Small keeps: its score [row], the Secondary [lines] under it ("Last synced night" while stale, then
     * time asleep) and whether the stage [bar] shows.
     */
    data class Small(
        val row: ScoreRow, val lines: List<SmallLine>, val bar: Boolean,
        val top: TopRow = TopRow(null, false, 0f), val spread: Spread = Spread(0f, 0),
    )

    /** The Small's dots: the mockup's 3 dp (spec 3), growing to 7 on a tall cell before the gaps do. */
    val SMALL_DOTS = listOf(3f, 3.6f, 4.5f, 5f, 5.5f, 6f, 6.5f, 7f)

    /** The Wide's dots on a tall cell, from 3.6. */
    val WIDE_GROWTH = listOf(4.5f, 5f, 5.5f, 6f)

    /**
     * The Small in [width] by [height]: the title and the score with its word always (the dots smaller for a long
     * score, the word dropped before the dots get smaller than 2.4 dp), then while they fit time asleep, the stage bar
     * and "Last synced night", dropped in that order. Time asleep only on one line;
     * "Last synced night" on two when one can't hold it, since without it yesterday's night would pass for last
     * night's.
     */
    fun small(m: SleepWidgetModel, width: Float, height: Float, fontScale: Float, status: StatusLine? = null): Small {
        val top = TopRow.of(m.title, null, status, width, fontScale, withWords = false)
        val sec = WidgetText.line(WidgetText.SECONDARY_SP, fontScale)
        fun linesOf(text: String) = WidgetText.lines(text, WidgetText.SECONDARY_SP, width, fontScale)
        val note = m.note?.takeIf { m.stale }?.let { SmallLine(it, linesOf(it)) }?.takeIf { it.lines <= 2 }
        val asleep = m.asleep?.let { SmallLine(it, linesOf(it)) }?.takeIf { it.lines == 1 }
        fun at(row: ScoreRow, lines: List<SmallLine>, bar: Boolean): Small {
            val parts = lines.map { it.lines * sec } + listOfNotNull(SMALL_BAR_DP.takeIf { bar })
            val foot = parts.sum() + (parts.size - 1).coerceAtLeast(0) * SMALL_GAP_DP
            return Small(row, lines, bar, top, Spread.of(top.height, row.height(fontScale), foot))
        }
        // The top row and the score at least MIN_GAP_DP apart, the score and what's under it SMALL_GAP_DP.
        fun fits(f: Small, h: Float) = f.spread.content + MIN_GAP_DP + (if (f.spread.inner > 1) SMALL_GAP_DP else 0f) <= h + 0.01f
        val row = smallRow(m, width, fontScale)
        val hasBar = m.totals.isNotEmpty()
        // Time asleep goes first, then the bar, then the note, which says the night isn't last night's.
        val tries = listOf(
            listOfNotNull(note, asleep) to hasBar, listOfNotNull(note) to hasBar, listOfNotNull(note) to false, emptyList<SmallLine>() to false,
        )
        val base = tries.map { (lines, bar) -> at(row, lines, bar) }.firstOrNull { fits(it, height) } ?: return at(row, emptyList(), false)
        // A tall cell grows the dots, never at the cost of the word.
        val ladder = SMALL_DOTS.map { at(smallRow(m, width, fontScale, it), base.lines, base.bar) }.filter { it.row.word == base.row.word }
        val fitting = (listOf(base) + ladder.filter { it.row.dot > base.row.dot }).distinct().filter { fits(it, height) }
        return fitting.firstOrNull { it.spread.gap(height) <= Spread.TARGET_GAP_DP } ?: fitting.last()
    }

    /** The Small's height as laid out with the least gaps, for the tests. */
    fun smallHeight(f: Small, @Suppress("UNUSED_PARAMETER") fontScale: Float): Float =
        f.spread.content + MIN_GAP_DP + (if (f.spread.inner > 1) SMALL_GAP_DP else 0f)

    private fun smallRow(m: SleepWidgetModel, width: Float, fontScale: Float, maxDot: Float = SMALL_DOT): ScoreRow {
        val score = m.score ?: return ScoreRow(
            0f, m.scoreLine ?: TabCopy.NO_STRAP_SCORE, null,
            WidgetText.lines(m.scoreLine ?: TabCopy.NO_STRAP_SCORE, WidgetText.BODY_SP, width, fontScale).coerceAtMost(2),
        )
        val word = m.word
        if (word != null) {
            val dot = WidgetDots.fit(score, width - SMALL_WORD_GAP_DP - WidgetText.width(word, WidgetText.BODY_SP, fontScale), maxDot)
            if (dot >= MIN_SMALL_DOT) return ScoreRow(dot, word, null)
        }
        return ScoreRow(WidgetDots.fit(score, width, maxDot), null, null)
    }

    /** The smallest the Small's dots go to keep its word beside them. */
    private const val MIN_SMALL_DOT = 2.4f

    /* ------------------------------------------------ Wide ----------------------------------------------- */

    /** What the Wide keeps: the note, the score row, the bar and the legend's rows (0 without it). */
    data class Wide(
        val note: Boolean, val row: ScoreRow, val bar: Boolean, val legendRows: Int,
        val top: TopRow = TopRow(null, note, 0f), val spread: Spread = Spread(0f, 0),
    )

    /** Whether the title row holds [title] and [note] on one line in [width]. */
    fun noteFits(title: String, note: String?, width: Float, fontScale: Float): Boolean = note != null &&
        WidgetText.width(title, WidgetText.TITLE_SP, fontScale) + SIDE_GAP_DP + WidgetText.width(note, WidgetText.SECONDARY_SP, fontScale) <= width

    /**
     * The Wide in [width] by [height]: the title row, the score row ([scoreRow] with time asleep) and the stage bar
     * always, the legend on one row or two while it fits, else none.
     */
    fun wide(m: SleepWidgetModel, width: Float, height: Float, fontScale: Float, status: StatusLine? = null): Wide {
        val top = TopRow.of(m.title, m.note, status, width, fontScale, withWords = true)
        val bar = m.totals.isNotEmpty()
        fun at(row: ScoreRow): Wide {
            val base = top.height + row.height(fontScale) + 2 * MIN_GAP_DP + (if (bar) BAR_DP else 0f)
            val rows = legendRows(m.legend, width, fontScale).takeIf { bar } ?: 0
            val legend = rows.takeIf { base + BAR_GAP_DP + legendHeight(it, fontScale) <= height } ?: 0
            val stages = if (bar) BAR_DP + (if (legend > 0) BAR_GAP_DP + legendHeight(legend, fontScale) else 0f) else null
            return Wide(top.note, row, bar, legend, top, Spread.of(top.height, row.height(fontScale), stages))
        }
        val base = at(scoreRow(m, m.asleep, width, fontScale))
        // A tall cell grows the dots, never at the cost of the word, what's on the right or the legend.
        val grown = WIDE_GROWTH.map { at(scoreRow(m, m.asleep, width, fontScale, it)) }
            .filter { it.row.word == base.row.word && it.row.right == base.row.right && it.legendRows == base.legendRows && it.row.dot > base.row.dot }
        val fitting = (listOf(base) + grown).filter { wideHeight(it, fontScale) <= height + 0.01f }
        return fitting.firstOrNull { it.spread.gap(height) <= Spread.TARGET_GAP_DP } ?: fitting.lastOrNull() ?: base
    }

    /** The Wide's height as laid out with the least gaps, for the tests. */
    fun wideHeight(w: Wide, @Suppress("UNUSED_PARAMETER") fontScale: Float): Float = w.spread.content + 2 * MIN_GAP_DP

    /* ----------------------------------------------- Large ----------------------------------------------- */

    /**
     * What the Large keeps: the note, the sentence's lines (0 without it), the score row, and the stage rows or, when
     * they don't fit, the stage bar.
     */
    data class Large(
        val note: Boolean, val sentenceLines: Int, val row: ScoreRow, val stages: Boolean, val bar: Boolean,
        val top: TopRow = TopRow(null, note, 0f),
    )

    /** The Large's stage rows' height: a Secondary line a stage. */
    fun stagesHeight(count: Int, fontScale: Float): Float =
        if (count <= 0) 0f else count * WidgetText.line(WidgetText.SECONDARY_SP, fontScale) + (count - 1) * STAGE_ROW_GAP_DP

    /**
     * The Large in [width] by [height]: the Wide's title row, score row (how often you woke on the right, else time
     * asleep), then the time in each stage as rows, with the sentence while it fits. The sentence goes first, then
     * the rows give way to the stage bar.
     */
    fun large(m: SleepWidgetModel, width: Float, height: Float, fontScale: Float, status: StatusLine? = null): Large {
        val top = TopRow.of(m.title, m.note, status, width, fontScale, withWords = true)
        val row = scoreRow(m, m.largeRight, width, fontScale)
        val bar = m.totals.isNotEmpty()
        val lines = m.sentence?.let { WidgetText.lines(it, WidgetText.HERO_SP, width, fontScale) }?.takeIf { it <= SENTENCE_LINES } ?: 0
        val rowsFit = bar && width - STAGE_NAME_DP - STAGE_TIME_DP - 2 * STAGE_GAP_DP >= MIN_STAGE_TRACK_DP
        // Stage rows with the sentence, without it, then the bar with and without it.
        val tries = listOf(rowsFit to lines, rowsFit to 0, false to lines, false to 0).distinct()
        for ((stages, sentence) in tries) {
            val l = Large(top.note, sentence, row, stages, bar, top)
            if (largeHeight(l, m.stages.size, fontScale) <= height) return l
        }
        return Large(top.note, 0, row, false, bar, top)
    }

    /** Everything on the Large with the least gaps between its blocks, [stageCount] rows when it shows them. */
    fun largeHeight(l: Large, stageCount: Int, fontScale: Float): Float = largeSpread(l, stageCount, fontScale).content + 3 * MIN_GAP_DP

    /** How the Large's blocks share its height: the top row, the sentence, the score row, the rows or the bar. */
    fun largeSpread(l: Large, stageCount: Int, fontScale: Float): Spread = Spread.of(
        l.top.height.takeIf { it > 0f } ?: WidgetText.line(WidgetText.TITLE_SP, fontScale),
        l.sentenceLines * WidgetText.line(WidgetText.HERO_SP, fontScale),
        l.row.height(fontScale),
        if (l.stages) stagesHeight(stageCount, fontScale) else if (l.bar) BAR_DP else 0f,
    )
}
