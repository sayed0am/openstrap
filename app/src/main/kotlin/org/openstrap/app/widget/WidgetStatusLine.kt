package org.openstrap.app.widget

import org.openstrap.app.ui.dashboard.clockText
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * The strap status line as a widget shows it (widgets spec 5): the [status] and its wordings, longest first, so a
 * narrow top row takes a shorter one before the words drop and the button stands alone.
 */
data class StatusLine(val status: StrapStatus, val options: List<String>) {
    /** The whole line, as [strapStatusLine] words it: what TalkBack reads whichever wording shows. */
    val full: String get() = options.first()

    companion object {
        /**
         * [status]'s wordings at [now] (epoch seconds): the line ("Synced Tuesday"), then shorter ones that keep its
         * sense beside the button's icon ("Synced Tue", "Tuesday", "Tue"). Never a dot, semicolon or dash.
         */
        fun of(status: StrapStatus, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): StatusLine {
            val full = strapStatusLine(status, now, zone, locale)
            val shorter = when (status) {
                StrapStatus.NotPaired -> listOf(tr(R.string.widget_status_line_pair_strap))
                is StrapStatus.LowBattery -> listOf("${status.percent}%")
                StrapStatus.Syncing -> emptyList()
                is StrapStatus.StaleSync -> {
                    val then = Instant.ofEpochSecond(status.at).atZone(zone).toLocalDate()
                    val days = ChronoUnit.DAYS.between(then, Instant.ofEpochSecond(now).atZone(zone).toLocalDate())
                    when {
                        days <= 0 -> listOf(clockText(status.at, zone))
                        days == 1L -> listOf(tr(R.string.common_yesterday))
                        days < 7 -> {
                            val day = then.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
                            val short = then.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                            listOf(tr(R.string.widget_status_line_synced, short), day, short)
                        }
                        else -> listOf(full.removePrefix(tr(R.string.widget_status_line_synced, "")))
                    }
                }
            }
            return StatusLine(status, (listOf(full) + shorter).distinct())
        }
    }
}

/**
 * The status button (spec 5, option C): a [SIZE_DP] dark circle at the right end of the title row with the state's
 * icon, and on the Wide and Large the state's words just left of it in [WORDS_SP] amber. Its tap reaches past the
 * circle to [TOUCH_DP] (an overlay, so the row still counts the circle's height).
 */
object StatusButton {
    const val SIZE_DP = 32f
    const val TOUCH_DP = 44f
    const val ICON_DP = 16f

    /** The words' size: Secondary, one line, never smaller. */
    const val WORDS_SP = WidgetText.SECONDARY_SP

    /** Between the words and the button. */
    const val WORDS_GAP_DP = 8f

    /** At least this between the title and what's right of it. */
    const val TITLE_GAP_DP = 12f

    /** The first of [line]'s wordings that fits [room] dp at [WORDS_SP]; null for none or no line. */
    fun words(line: StatusLine?, room: Float, fontScale: Float): String? =
        line?.options?.firstOrNull { WidgetText.fitWidth(it, WORDS_SP, fontScale) <= room }
}

/**
 * A widget's top row (spec 5): the title at [titleSp], always; while the strap needs something its [status] button at
 * the right with the [words] that fit beside it (none on a Small, or when none fits); else whether the [note] on the
 * right still fits. [height] is what every layout's sums use: the title's line, or the button's when it shows.
 */
data class TopRow(
    val status: StatusLine?, val note: Boolean, val height: Float,
    val words: String? = null, val titleSp: Float = WidgetText.TITLE_SP,
) {
    companion object {
        /** The title's sizes while the button shows, largest first, so a long title steps down rather than be cut. */
        val TITLE_SIZES = listOf(WidgetText.TITLE_SP, WidgetText.BODY_SP, WidgetText.SECONDARY_SP)

        /**
         * The top row in [width] dp: [title] at [titleSizes]' first with [note] on the right, or with the status
         * [line]'s button there and, when [withWords], the first wording that fits between the title and the button.
         * The words replace the note while the line shows. While it shows the title takes the first of [titleSizes]
         * that fits beside the button, or the next size down when only that leaves room for the words.
         */
        fun of(
            title: String, note: String?, line: StatusLine?, width: Float, fontScale: Float, withWords: Boolean,
            titleSizes: List<Float> = TITLE_SIZES,
        ): TopRow {
            if (line == null) {
                val sp = titleSizes.first()
                val keep = note != null &&
                    WidgetText.fitWidth(title, sp, fontScale) + StatusButton.TITLE_GAP_DP + WidgetText.fitWidth(note, WidgetText.SECONDARY_SP, fontScale) <= width
                return TopRow(null, keep, WidgetText.line(sp, fontScale), titleSp = sp)
            }
            val beside = StatusButton.SIZE_DP + StatusButton.TITLE_GAP_DP
            fun room(sp: Float) = width - WidgetText.fitWidth(title, sp, fontScale) - StatusButton.TITLE_GAP_DP - StatusButton.WORDS_GAP_DP - StatusButton.SIZE_DP
            val fitting = titleSizes.firstOrNull { WidgetText.fitWidth(title, it, fontScale) + beside <= width } ?: titleSizes.last()
            // The words are the news: the title may step down one size to make room for them, no further.
            val sp = if (!withWords) fitting else titleSizes.take(2).filter { it <= fitting }
                .firstOrNull { StatusButton.words(line, room(it), fontScale) != null } ?: fitting
            val words = if (withWords) StatusButton.words(line, room(sp), fontScale) else null
            val h = maxOf(WidgetText.line(sp, fontScale), StatusButton.SIZE_DP, if (words != null) WidgetText.line(StatusButton.WORDS_SP, fontScale) else 0f)
            return TopRow(line, false, h, words, sp)
        }
    }
}

/**
 * How a layout shares its spare height (spec 2.1 and step 5's rule): [content] dp of blocks with [inner] gaps between
 * them, half a gap above the first and below the last, all alike.
 */
data class Spread(val content: Float, val inner: Int) {
    /** The gap between blocks in [height] dp. */
    fun gap(height: Float): Float = ((height - content) / (inner + 1)).coerceAtLeast(0f)

    /** The half gap above the first block and below the last. */
    fun edge(height: Float): Float = gap(height) / 2

    /** Whether it fits [height] with at least [minGap] between the blocks. */
    fun fits(height: Float, minGap: Float): Boolean = content + inner * minGap <= height + 0.01f

    companion object {
        /** The most a gap should be on a tall cell: the parts grow before a gap passes it. */
        const val TARGET_GAP_DP = 32f

        /** [blocks] laid out: their heights, the gaps between them. */
        fun of(vararg blocks: Float?): Spread {
            val shown = blocks.filterNotNull().filter { it > 0f }
            return Spread(shown.sum(), (shown.size - 1).coerceAtLeast(0))
        }

        /**
         * The first of [ladder] (least grown first) that fits [height] with [minGap] and brings the gap to
         * [TARGET_GAP_DP] or under, else the most grown that fits, else the first.
         */
        fun <T> grow(ladder: List<T>, height: Float, minGap: Float, spread: (T) -> Spread): T {
            val fitting = ladder.filter { spread(it).fits(height, minGap) }
            return fitting.firstOrNull { spread(it).gap(height) <= TARGET_GAP_DP } ?: fitting.lastOrNull() ?: ladder.first()
        }
    }
}
