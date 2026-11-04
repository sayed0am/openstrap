package org.openstrap.app.ui.glow

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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

    /** HIIT's phase word over the seconds, WORK or REST (boards HiitWork, HiitRest): Display 500, 30 on 34, spaced out. */
    val phaseWord = TextStyle(fontFamily = FunnelDisplay, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = 0.14.em)

    /** Mind and body's time inside its ring (board LiveMind): Display 300, 72 on 76, tabular. */
    val mindClock = TextStyle(fontFamily = FunnelDisplay, fontWeight = FontWeight.Light, fontSize = 72.sp, letterSpacing = (-0.02).em, lineHeight = 76.sp, fontFeatureSettings = "tnum")

    /** The moving time on the map's panel, beside its two figures (board RunMap): Display 300, 38 on 42, tabular. */
    val mapClock = TextStyle(fontFamily = FunnelDisplay, fontWeight = FontWeight.Light, fontSize = 38.sp, letterSpacing = (-0.02).em, lineHeight = 42.sp, fontFeatureSettings = "tnum")

    /** A run's distance, time and pace on its share card (board ShareRun): Display 300, 30 on 33, tabular. */
    val storyStat = TextStyle(fontFamily = FunnelDisplay, fontWeight = FontWeight.Light, fontSize = 30.sp, letterSpacing = (-0.01).em, lineHeight = 33.sp, fontFeatureSettings = "tnum")

    /** The sport at the top of a share card ("Push day", boards ShareStrength, ShareHiit): Display 500, 30 on 34. */
    val storyTitle = TextStyle(fontFamily = FunnelDisplay, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 34.sp)

    /** "OpenStrap" on a share card: Display 500, 14 on 18, spaced out a little. */
    val storyBrand = TextStyle(fontFamily = FunnelDisplay, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.04.em)

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

    /**
     * A goal's value on Ready, typed in place ("7.5 km", "1 h 15 min", "6 × 400 m"): Display 300, 44 on 44, tabular.
     * Plain, since the card's dotted number is the heart rate's.
     */
    val goalValue = TextStyle(fontFamily = FunnelDisplay, fontWeight = FontWeight.Light, fontSize = 44.sp, letterSpacing = (-0.02).em, lineHeight = 44.sp, fontFeatureSettings = "tnum")

    /** The smaller numbers beside a [goalValue] (the rest in "with 90 s rest between", the ×): Display 300, 26 on 28. */
    val goalValueMinor = TextStyle(fontFamily = FunnelDisplay, fontWeight = FontWeight.Light, fontSize = 26.sp, lineHeight = 28.sp, fontFeatureSettings = "tnum")
}
