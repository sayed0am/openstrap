package org.openstrap.app.ui.glow.pictures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.presentation.StageSpan
import java.io.File
import java.time.LocalTime

/** The line pictures' geometry and where each card's rim lights (spec 2.5, 2.7). */
class LinePicturesTest {
    private val eps = 1e-4f

    @Test fun cardFractionsKeepInsideThePictureArea() {
        assertEquals(SMALL_INSET_X, lineCardX(0f, PictureSize.Small), eps)
        assertEquals(1f - SMALL_INSET_X, lineCardX(1f, PictureSize.Small), eps)
        assertEquals(1f - WIDE_INSET_X, lineCardX(2f, PictureSize.Wide), eps)
        assertEquals(PICTURE_TOP_Y, lineCardY(0f), eps)
    }

    @Test fun lineScaleTakesInTheBandAndPads() {
        val s = lineScale(listOf(60f, null, 70f), 50f..58f)!!
        assertTrue(s.lo < 50f && s.hi > 70f)
        assertEquals(0f, s.down(1000f), eps)
        assertEquals(1f, s.down(-1000f), eps)
        assertTrue(s.down(70f) < s.down(60f))
        assertNull(lineScale(listOf(null, null), 50f..60f))
    }

    @Test fun aFlatLineSitsMid() {
        val s = lineScale(listOf(60f, 60f), null)!!
        assertEquals(0.5f, s.down(60f), eps)
    }

    @Test fun dayLineRimSitsAtTheLinesEnd() {
        val values = listOf(60f, 62f, null, 80f, null, null)
        assertEquals(3, lastReading(values))
        val high = dayLineRim(values, high = true, PictureSize.Small)!!
        assertEquals(lineCardX(3 / 5f, PictureSize.Small), high.x, eps)
        assertEquals(0f, high.y, eps)
        assertEquals(1f, dayLineRim(values, high = false, PictureSize.Wide)!!.y, eps)
        assertEquals(lineCardX(1f, PictureSize.Small), dayLineRim(listOf(1f, 2f), true, PictureSize.Small)!!.x, eps)
        assertNull(dayLineRim(listOf(null, null), true, PictureSize.Small))
    }

    @Test fun stressStrengthRunsCalmToHigh() {
        assertEquals(0.15f, stressAlpha(0f), eps)
        assertEquals(1f, stressAlpha(1f), eps)
        assertEquals(1f, stressAlpha(3f), eps)
    }

    @Test fun stripLeavesOutGapsAndHoursToCome() {
        val s = stripStretches(listOf(0.2f, null, 0.8f), slots = 6)
        assertEquals(2, s.size)
        assertEquals(2 / 6f, s[1].x0, eps)
        assertEquals(3 / 6f, s[1].x1, eps)
        val rim = dayStripRim(listOf(0.2f, null, 0.8f), PictureSize.Wide, slots = 6)!!
        assertEquals(lineCardX(0.5f, PictureSize.Wide), rim.x, eps)
        assertNull(dayStripRim(listOf(null), PictureSize.Small))
    }

    @Test fun dialPutsYourNormalAtTheTop() {
        assertEquals(90f, dialAngle(0f), eps)
        assertEquals(180f, dialAngle(-1.5f), eps)
        assertEquals(0f, dialAngle(1.5f), eps)
        assertEquals(0f, dialAngle(4f), eps)
        assertEquals(45f, dialAngle(0.75f), eps)
    }

    @Test fun dialFitsItsBoxAndPointsAlongTheArc() {
        val fit = dialFit(200f, 60f, 5f)
        assertEquals(50f, fit.radius, eps)
        assertTrue(fit.center.y - fit.radius >= 5f - eps)
        assertTrue(fit.center.y <= 60f - 5f + eps)
        val top = dialPoint(fit, 90f)
        assertEquals(fit.center.x, top.x, 1e-3f)
        assertEquals(fit.center.y - fit.radius, top.y, 1e-3f)
        val right = dialPoint(fit, 0f)
        assertEquals(fit.center.x + fit.radius, right.x, 1e-3f)
        // A square box: the width bounds it.
        assertEquals(45f, dialFit(100f, 100f, 5f).radius, eps)
    }

    @Test fun dialRimAtTheTopWhenAboveAndTheFootWhenBelow() {
        val up = tempDialRim(high = true, PictureSize.Wide)
        assertEquals(WIDE_DIAL_CENTER_X, up.x, eps)
        assertEquals(0f, up.y, eps)
        val down = tempDialRim(high = false, PictureSize.Small)
        assertEquals(0.5f, down.x, eps)
        assertEquals(1f, down.y, eps)
        assertEquals(0.6f, tempDialRim(true, PictureSize.Wide, centerX = 0.6f).x, eps)
    }

    @Test fun nightsNeverWrapAtMidnight() {
        assertEquals(11 * 60 + 30 to 19 * 60, sinceNoon(BedWake(LocalTime.of(23, 30), LocalTime.of(7, 0))))
        assertEquals(12 * 60 + 30 to 18 * 60, sinceNoon(BedWake(LocalTime.of(0, 30), LocalTime.of(6, 0))))
        // A late wake past noon still sits after bed.
        val (b, w) = sinceNoon(BedWake(LocalTime.of(4, 0), LocalTime.of(12, 30)))
        assertTrue(w > b)
    }

    private val usual = BedWake(LocalTime.of(23, 0), LocalTime.of(7, 0))
    private fun night(bh: Int, bm: Int, wh: Int, wm: Int) = BedWake(LocalTime.of(bh, bm), LocalTime.of(wh, wm))

    @Test fun offNightsAreOutsideTheBandEitherWay() {
        val week = listOf(
            night(23, 10, 7, 5), night(22, 0, 7, 0), null, night(23, 0, 8, 0), night(0, 30, 8, 30), night(23, 44, 7, 44),
        )
        assertEquals(listOf(false, true, false, true, true, false), offNights(week, usual))
        assertEquals(listOf(false, false), offNights(listOf(night(1, 0, 9, 0), null), null))
    }

    @Test fun bedWakeSpanCoversNightsAndBands() {
        val span = bedWakeSpan(listOf(night(0, 30, 8, 30)), usual)
        assertTrue(span.first <= sinceNoon(usual).first - USUAL_BAND_MINUTES)
        assertTrue(span.last >= sinceNoon(night(0, 30, 8, 30)).second)
    }

    @Test fun bedWakeRimSitsByTheLateNights() {
        val week = listOf(night(23, 0, 7, 0), night(23, 0, 7, 0), night(23, 0, 7, 0), night(23, 0, 7, 0), night(23, 0, 7, 0), night(1, 0, 8, 30), night(1, 30, 9, 0))
        val rim = bedWakeRim(week, usual, PictureSize.Wide)!!
        assertEquals(lineCardX((nightX(5, 7) + nightX(6, 7)) / 2, PictureSize.Wide), rim.x, eps)
        assertEquals(1f, rim.y, eps)
        val early = bedWakeRim(listOf(night(21, 30, 5, 30), night(23, 0, 7, 0)), usual, PictureSize.Small)!!
        assertEquals(0f, early.y, eps)
        assertNull(bedWakeRim(week.take(5), usual, PictureSize.Small))
        assertNull(bedWakeRim(week, null, PictureSize.Small))
    }

    @Test fun napsLandAtTheirTimeAndShortOnesStillShow() {
        val day = 0L
        val end = 16 * 3600L
        val nap = Nap(8 * 3600L, 9 * 3600L, listOf(StageSpan(8 * 3600L, 8 * 3600L + 1800, 1), StageSpan(8 * 3600L + 1800, 9 * 3600L, 2)))
        val p = napPlaces(listOf(nap), day, end, least = 0.01f).single()
        assertEquals(0.5f, p.x0, eps)
        assertEquals(9 / 16f, p.x1, eps)
        assertEquals(listOf(1, 2), p.parts.map { it.stage })
        assertEquals((p.x0 + p.x1) / 2, p.parts[0].x1, eps)

        val short = napPlaces(listOf(Nap(8 * 3600L, 8 * 3600L + 60)), day, end, least = 0.1f).single()
        assertEquals(0.1f, short.x1 - short.x0, eps)
        assertNull(short.parts.single().stage)
        // Out of the day, not drawn.
        assertTrue(napPlaces(listOf(Nap(20 * 3600L, 21 * 3600L)), day, end, 0.1f).isEmpty())
        // Pinned to the end, never past it.
        val late = napPlaces(listOf(Nap(end - 60, end)), day, end, 0.1f).single()
        assertEquals(1f, late.x1, eps)
    }

    @Test fun nothingIsDashedOrDotted() {
        val src = File("src/main/kotlin/org/openstrap/app/ui/glow/pictures/LinePictures.kt")
        assertNotNull(src)
        if (src.exists()) assertFalse(src.readText().contains("dashPathEffect"))
    }
}
