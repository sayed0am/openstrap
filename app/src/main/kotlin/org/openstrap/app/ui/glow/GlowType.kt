package org.openstrap.app.ui.glow

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.openstrap.app.ui.theme.FunnelDisplay
import org.openstrap.app.ui.theme.FunnelSans

/**
 * The type roles of the design system (spec 2.3): Funnel Display for display roles, Funnel Sans for the rest. Every
 * text in the app should use one of these, so a role changes in one place. Colours are the caller's, except the
 * roles drawn dimmed, which name their alpha.
 */
object GlowType {
    /** You, Strap, every detail and settings page: Display 400, 24 on 28. */
    val pageTitle = TextStyle(fontFamily = FunnelDisplay, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 28.sp)

    /** The date in the moment header: Display 500, 20 on 24. */
    val headerDate = TextStyle(fontFamily = FunnelDisplay, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 24.sp)

    /** The moment over the header date ("Waking up"): Sans 500, 13, drawn at [MOMENT_ALPHA] white. */
    val moment = TextStyle(fontFamily = FunnelSans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp)

    /** White for [moment] text. */
    const val MOMENT_ALPHA = 0.6f

    /**
     * Every card's title on every tier, the hero's included, and list titles: Sans 500, 17 on 22. One style, so a glow
     * card and the edge card beside it read as one family.
     */
    val cardTitle = TextStyle(fontFamily = FunnelSans, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp)

    /**
     * The sentence that leads a hero ("Slept 7 h 40. Recovered enough for your usual training."): Display 300, 20 on
     * 24.4, three lines at most. Display against the title's Sans, so title and sentence don't run together.
     */
    val sentence = TextStyle(fontFamily = FunnelDisplay, fontWeight = FontWeight.Light, fontSize = 20.sp, letterSpacing = (-0.01).em, lineHeight = 24.4.sp)

    /** Row names, buttons, a sentence on an edge card, the word after a number: Sans 400, 15 on 20. */
    val body = TextStyle(fontFamily = FunnelSans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp)

    /** Status lines, notes, a title row's note: Sans 400, 13 on 18, drawn at [SECONDARY_ALPHA] white unless coloured. */
    val secondary = TextStyle(fontFamily = FunnelSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)

    /** White for [secondary] text. */
    const val SECONDARY_ALPHA = 0.72f

    /** Axes, times, tags: Sans 500, 11 on 14, tabular so times line up, drawn at [CAPTION_ALPHA] white. */
    val caption = TextStyle(fontFamily = FunnelSans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, fontFeatureSettings = "tnum")

    /** White for [caption] text. */
    const val CAPTION_ALPHA = 0.5f

    /** A list row's value: Sans 500, 20 on 24, tabular so a column of values lines up. Plain text, never dots. */
    val rowValue = TextStyle(fontFamily = FunnelSans, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 24.sp, fontFeatureSettings = "tnum")

    /**
     * A Small that breaks down the card beside it (Recharged, Drained): Display 300, 34 on 36, tabular. Plain, since
     * the dotted answer is the other card's.
     */
    val smallValue = TextStyle(fontFamily = FunnelDisplay, fontWeight = FontWeight.Light, fontSize = 34.sp, letterSpacing = (-0.02).em, lineHeight = 36.sp, fontFeatureSettings = "tnum")
}

/**
 * The six dot sizes of dot-matrix numbers (spec 2.3), by where the number sits. [DotMatrixText] takes only these, so
 * the same kind of number is the same size on every screen.
 */
enum class DotSize(val dot: Dp) {
    /** Four or more figures side by side ([FigureRow]). List rows are plain text ([GlowType.rowValue]). */
    Row(2.4.dp),

    /** Edge cards and Smalls. */
    Edge(3.dp),

    /** Talls, the second glow, glow cards that aren't the hero. */
    Second(3.6.dp),

    /** The hero's number. */
    Hero(4.5.dp),

    /** A detail's header. */
    Detail(6.dp),

    /** The live workout. */
    Live(9.dp),
    ;

    /** The space between dots: a fixed share of the dot, so every size has the same texture. */
    val gap: Dp get() = dot * DOT_GAP

    companion object {
        /** The gap between dots as a share of the dot. */
        const val DOT_GAP = 0.38f
    }
}
