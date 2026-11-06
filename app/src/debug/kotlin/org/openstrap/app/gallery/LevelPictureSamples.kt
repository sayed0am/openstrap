package org.openstrap.app.gallery

import org.openstrap.app.ui.dashboard.PictureKind
import org.openstrap.app.ui.glow.pictures.AgeScalePicture
import org.openstrap.app.ui.glow.pictures.Blocks
import org.openstrap.app.ui.glow.pictures.CountdownRing
import org.openstrap.app.ui.glow.pictures.DayBars
import org.openstrap.app.ui.glow.pictures.LevelFill
import org.openstrap.app.ui.glow.pictures.RangeFill
import org.openstrap.app.ui.glow.pictures.SplitBar
import org.openstrap.app.ui.glow.pictures.WeekOfDots
import org.openstrap.app.ui.glow.pictures.WeekRings
import org.openstrap.app.ui.glow.pictures.ageScaleRim
import org.openstrap.app.ui.glow.pictures.levelFillRim
import org.openstrap.app.ui.glow.pictures.weekOfDotsRim
import org.openstrap.app.ui.theme.MetricColors

private val recoveryWeek = listOf(64f, 72f, 68f, 61f, 70f, 66f, 71f)
private val recoveryWeekOff = listOf(64f, 72f, 68f, 61f, 70f, 66f, 41f)
private val recoveryUsual = 60f..74f

private val breathingWeek = listOf(5f, 7f, 4f, null, 6f, 5f, 5f)
private val breathingWeekOff = listOf(5f, 7f, 4f, null, 6f, 5f, 14f)
private val breathingUsual = 3f..8f

private const val LOAD_USUAL = 420f
private val loadUsual = LOAD_USUAL * 0.75f..LOAD_USUAL * 1.25f

/** The level and count pictures (ui/glow/pictures/LevelPictures.kt) with fake data, for [PictureGallery]. */
internal val LevelPictureSamples: List<PictureSample> = listOf(
    PictureSample(
        PictureKind.WeekDots, "Recovery", "71", null, MetricColors.readiness, "Below usual",
        rim = { size -> weekOfDotsRim(recoveryWeekOff, recoveryUsual, size) },
        draw = { size, off, m -> WeekOfDots(if (off) recoveryWeekOff else recoveryWeek, recoveryUsual, MetricColors.readiness, size, m) },
    ),
    PictureSample(
        PictureKind.WeekDots, "Breathing disturbances", "5", "an hour", MetricColors.sleepLight, "Above usual",
        rim = { size -> weekOfDotsRim(breathingWeekOff, breathingUsual, size) },
        draw = { size, off, m -> WeekOfDots(if (off) breathingWeekOff else breathingWeek, breathingUsual, MetricColors.sleepLight, size, m) },
    ),
    PictureSample(
        PictureKind.LevelFill, "Load", "420", null, MetricColors.load, "Above usual",
        rim = { size -> levelFillRim(640f, loadUsual, size) },
        draw = { size, off, m -> LevelFill(if (off) 640f else 420f, loadUsual, MetricColors.load, size, m) },
    ),
    PictureSample(
        PictureKind.CountdownRing, "To recover", "9 h", null, MetricColors.load, null,
        rim = { null },
        draw = { _, off, m -> CountdownRing(if (off) 1f else 0.62f, MetricColors.load, m) },
    ),
    PictureSample(
        PictureKind.Blocks, "Active minutes", "110", "min", MetricColors.steps, null,
        rim = { null },
        draw = { size, off, m -> Blocks(if (off) 165 else 110, MetricColors.steps, size, m) },
    ),
    PictureSample(
        PictureKind.DayBars, "Heart points", "31", null, MetricColors.heart, null,
        rim = { null },
        draw = { size, off, m ->
            DayBars(if (off) listOf(12f, 40f, 8f, null, 22f, 35f, 52f) else listOf(18f, 25f, 9f, 30f, 14f, 21f, 31f), MetricColors.heart, size, m, target = 30f)
        },
    ),
    PictureSample(
        PictureKind.FillBar, "Recharged", "+38", null, MetricColors.charge, null,
        rim = { null },
        draw = { size, off, m -> if (off) RangeFill(58f, 50f, MetricColors.charge, size, m) else RangeFill(34f, 72f, MetricColors.charge, size, m) },
    ),
    PictureSample(
        PictureKind.SplitBar, "Drained", "-28", null, MetricColors.charge, null,
        rim = { null },
        draw = { size, off, m -> SplitBar(if (off) listOf(0f, 9f, 14f) else listOf(14f, 8f, 6f), MetricColors.charge, size, m) },
    ),
    PictureSample(
        PictureKind.WeekRings, "Your week", "4", "of 7", MetricColors.steps, null,
        rim = { null },
        draw = { size, off, m ->
            WeekRings(if (off) listOf(1f, 1f, 1f, 1f, 1f, 1f, 1.2f) else listOf(1f, 1f, 0.55f, 1f, 0.7f, 0.02f, null), MetricColors.steps, size, m)
        },
    ),
    PictureSample(
        PictureKind.AgeScale, "Habit Age", "34", "years", MetricColors.habitAge, "Older",
        rim = { size -> ageScaleRim(32f, 36f, 31f, size) },
        draw = { size, off, m ->
            if (off) AgeScalePicture(32f, 36f, 34f, 31f, MetricColors.habitAge, size, m)
            else AgeScalePicture(27f, 31f, 29f, 30f, MetricColors.habitAge, size, m)
        },
    ),
)
