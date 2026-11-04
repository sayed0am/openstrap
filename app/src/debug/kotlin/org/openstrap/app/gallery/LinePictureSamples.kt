package org.openstrap.app.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.openstrap.app.presentation.StageSpan
import org.openstrap.app.ui.dashboard.PictureKind
import org.openstrap.app.ui.glow.pictures.BedWake
import org.openstrap.app.ui.glow.pictures.BedWakeBars
import org.openstrap.app.ui.glow.pictures.DayLine
import org.openstrap.app.ui.glow.pictures.DayStrip
import org.openstrap.app.ui.glow.pictures.Nap
import org.openstrap.app.ui.glow.pictures.NapDay
import org.openstrap.app.ui.glow.pictures.PictureSize
import org.openstrap.app.ui.glow.pictures.TempDial
import org.openstrap.app.ui.glow.pictures.bedWakeRim
import org.openstrap.app.ui.glow.pictures.dayLineRim
import org.openstrap.app.ui.glow.pictures.dayStripRim
import org.openstrap.app.ui.glow.pictures.tempDialRim
import org.openstrap.app.ui.theme.MetricColors
import java.time.LocalTime
import kotlin.math.sin

/* Fake but realistic days: ten minute buckets of heart rate to 15:00, half hours of stress, a week of nights. */

private const val HEART_SLOTS = 144
private const val NOW_SLOT = 90

/** A calm day in the band, a gap while the strap was off, then a climb at the end when off. */
private fun heartDay(off: Boolean): List<Float?> = List(HEART_SLOTS) { i ->
    when {
        i > NOW_SLOT -> null
        i in 40..46 -> null
        i < 36 -> 54f + 3f * sin(i / 5f)
        off && i > NOW_SLOT - 14 -> 66f + (i - (NOW_SLOT - 14)) * 2.2f
        else -> 64f + 7f * sin(i / 7f) + if (i in 60..64) 22f else 0f
    }
}
private val HeartBand = 52f..72f

private const val STRESS_SLOTS = 48
private fun stressDay(off: Boolean): List<Float?> = List(30) { i ->
    when {
        i in 12..13 -> null
        off && i >= 24 -> 0.7f + (i - 24) * 0.05f
        else -> (0.25f + 0.2f * sin(i / 3f) + if (i in 18..20) 0.4f else 0f).coerceIn(0f, 1f)
    }
}

private fun t(h: Int, m: Int) = LocalTime.of(h, m)
private val UsualNight = BedWake(t(23, 10), t(7, 0))
private fun week(off: Boolean): List<BedWake?> = listOf(
    BedWake(t(23, 0), t(6, 50)), BedWake(t(23, 25), t(7, 10)), BedWake(t(22, 55), t(6, 45)), null,
    BedWake(t(23, 15), t(7, 5)),
    if (off) BedWake(t(0, 40), t(8, 20)) else BedWake(t(23, 30), t(7, 15)),
    if (off) BedWake(t(1, 20), t(8, 50)) else BedWake(t(23, 5), t(6, 55)),
)

private const val DAY_START = 1_762_236_000L // 06:00
private const val H = 3600L
private val Naps = listOf(
    Nap(
        DAY_START + 8 * H, DAY_START + 8 * H + 40 * 60,
        listOf(
            StageSpan(DAY_START + 8 * H, DAY_START + 8 * H + 12 * 60, 1),
            StageSpan(DAY_START + 8 * H + 12 * 60, DAY_START + 8 * H + 26 * 60, 2),
            StageSpan(DAY_START + 8 * H + 26 * 60, DAY_START + 8 * H + 34 * 60, 3),
            StageSpan(DAY_START + 8 * H + 34 * 60, DAY_START + 8 * H + 40 * 60, 0),
        ),
    ),
    Nap(DAY_START + 12 * H, DAY_START + 12 * H + 20 * 60),
)

/** The line-like pictures (ui/glow/pictures/LinePictures.kt) with fake data, for [PictureGallery]. */
internal val LinePictureSamples: List<PictureSample> = listOf(
    PictureSample(
        PictureKind.Line, "Heart rate", "84", "bpm", MetricColors.heart, "Above usual",
        rim = { size -> dayLineRim(heartDay(true), high = true, size, HEART_SLOTS) },
    ) { size, off, modifier -> DayLine(heartDay(off), HeartBand, MetricColors.heart, size, modifier, HEART_SLOTS) },
    PictureSample(
        PictureKind.DayStrip, "Stress", "62", null, MetricColors.stress, "Above usual",
        rim = { size -> dayStripRim(stressDay(true), size, STRESS_SLOTS) },
    ) { size, off, modifier -> DayStrip(stressDay(off), MetricColors.stress, size, modifier, STRESS_SLOTS) },
    PictureSample(
        PictureKind.Dial, "Skin temperature", "+0.9", "°C", MetricColors.temperature, "Above normal",
        rim = { size -> tempDialRim(high = true, size) },
    ) { size, off, modifier ->
        val dev = if (off) 0.9f else 0.1f
        if (size == PictureSize.Small) TempDial(dev, -0.3f..0.3f, MetricColors.temperature, size, modifier)
        else Box(modifier, contentAlignment = Alignment.CenterEnd) {
            TempDial(dev, -0.3f..0.3f, MetricColors.temperature, size, Modifier.fillMaxWidth(0.44f).fillMaxHeight())
        }
    },
    PictureSample(
        PictureKind.BedWakeBars, "Regularity", "71", null, MetricColors.sleepDeep, "Irregular",
        rim = { size -> bedWakeRim(week(true), UsualNight, size) },
    ) { size, off, modifier -> BedWakeBars(week(off), UsualNight, MetricColors.sleepDeep, size, modifier) },
    PictureSample(
        PictureKind.NapDay, "Naps", "1:00", "h", MetricColors.sleepDeep, null,
        rim = { null },
    ) { size, _, modifier -> NapDay(Naps, DAY_START, DAY_START + 16 * H, MetricColors.sleepDeep, size, modifier) },
)
