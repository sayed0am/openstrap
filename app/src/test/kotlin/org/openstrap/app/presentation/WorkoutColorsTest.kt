package org.openstrap.app.presentation

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.app.ui.glow.PausedColor
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.workout.ZONE_COLORS

/** The workout colours are the live screen's. */
class WorkoutColorsTest {
    @Test
    fun zonesAreTheLiveScreens() {
        assertEquals(ZONE_COLORS.map { it.toArgb() }, WorkoutColors.ZONES)
        assertEquals(MetricColors.heart.toArgb(), WorkoutColors.ZONE_5)
    }

    @Test
    fun pausedIsTheLiveScreensGreyAndRestIsZoneTwo() {
        assertEquals(PausedColor.toArgb(), WorkoutColors.PAUSED_GREY)
        assertEquals(ZONE_COLORS[1].toArgb(), WorkoutColors.REST_GREEN)
    }

    @Test
    fun zoneByIndex() {
        assertEquals(WorkoutColors.ZONE_1, WorkoutColors.zone(0))
        assertEquals(WorkoutColors.ZONE_5, WorkoutColors.zone(4))
        assertEquals(WorkoutColors.PAUSED_GREY, WorkoutColors.zone(null))
        assertEquals(WorkoutColors.PAUSED_GREY, WorkoutColors.zone(5))
        assertEquals(WorkoutColors.PAUSED_GREY, WorkoutColors.zone(-1))
    }
}
