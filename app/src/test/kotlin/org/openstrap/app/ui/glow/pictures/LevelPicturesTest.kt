package org.openstrap.app.ui.glow.pictures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The level and count pictures' geometry and where their rims light. */
class LevelPicturesTest {
    private val eps = 1e-4f

    @Test fun cardFractionsStayInsideTheInset() {
        assertEquals(insetShare(PictureSize.Small), cardX(0f, PictureSize.Small), eps)
        assertEquals(1f - insetShare(PictureSize.Wide), cardX(1f, PictureSize.Wide), eps)
        assertEquals(PICTURE_TOP, cardY(0f), eps)
        assertEquals(PICTURE_BOTTOM, cardY(2f), eps)
    }

    // Week of dots

    @Test fun weekDotsSpreadLeftToRightWithTheLatestAtTheEnd() {
        val g = weekDotsGeometry(listOf(60f, 70f, 65f, 62f, 68f, 66f, 71f), 60f..74f)
        assertEquals(7, g.xs.size)
        assertTrue(g.xs.zipWithNext().all { (a, b) -> b > a })
        assertTrue(g.xs.first() > 0f && g.xs.last() < 1f)
    }

    @Test fun higherValuesSitHigherAndTheBandHoldsItsValues() {
        val g = weekDotsGeometry(listOf(50f, 80f), 60f..70f)
        assertTrue(g.ys[1]!! < g.ys[0]!!)
        assertTrue(g.bandTop!! < g.bandBottom!!)
        assertTrue(g.ys[1]!! < g.bandTop && g.ys[0]!! > g.bandBottom)
        assertTrue(g.ys.all { it!! in 0f..1f })
    }

    @Test fun aNightWithNoReadingHasNoDotAndNoBandMeansNone() {
        val g = weekDotsGeometry(listOf(5f, null, 6f), null)
        assertNull(g.ys[1])
        assertNull(g.bandTop)
        assertNull(weekOfDotsRim(listOf(5f, null, 40f), null))
    }

    @Test fun flatWeekStillLaysOut() {
        val g = weekDotsGeometry(listOf(5f, 5f, 5f), null)
        assertTrue(g.ys.all { it!! in 0f..1f })
    }

    @Test fun weekDotsRimLightsAtTheLatestDotOnlyWhenItIsOff() {
        assertNull(weekOfDotsRim(listOf(64f, 70f, 71f), 60f..74f))
        assertNull(weekOfDotsRim(listOf(30f, 90f, null), 60f..74f))
        val low = weekOfDotsRim(listOf(64f, 70f, 41f), 60f..74f)!!
        val high = weekOfDotsRim(listOf(64f, 70f, 95f), 60f..74f)!!
        assertEquals(1f, low.x, eps)
        assertTrue("a low latest lights lower than a high one", low.y > high.y)
        assertTrue(low.y in PICTURE_TOP..PICTURE_BOTTOM)
    }

    // Level fill

    @Test fun levelFillPlacesTheBandPastTheMiddle() {
        val g = levelFillGeometry(420f, 315f..525f)
        assertEquals(315f / (525f * 1.6f), g.bandStart!!, eps)
        assertEquals(1f / 1.6f, g.bandEnd!!, eps)
        assertTrue(g.value in g.bandStart..g.bandEnd)
    }

    @Test fun aValuePastTheScaleStretchesIt() {
        val g = levelFillGeometry(2000f, 315f..525f)
        assertEquals(1f / 1.1f, g.value, eps)
        assertTrue(g.bandEnd!! < g.value)
    }

    @Test fun levelFillWithNothingDoesNotDivideByZero() {
        val g = levelFillGeometry(0f, null)
        assertEquals(0f, g.value, eps)
        assertNull(g.bandStart)
    }

    @Test fun levelFillRimSitsAtTheValuesEnd() {
        assertNull(levelFillRim(420f, 315f..525f))
        assertNull(levelFillRim(420f, null))
        val above = levelFillRim(700f, 315f..525f, PictureSize.Wide)!!
        val below = levelFillRim(100f, 315f..525f, PictureSize.Wide)!!
        assertEquals(cardX(levelFillGeometry(700f, 315f..525f).value, PictureSize.Wide), above.x, eps)
        assertTrue(below.x < above.x)
    }

    // Countdown ring

    @Test fun countdownSweepIsClamped() {
        assertEquals(0f, countdownSweep(-0.2f), eps)
        assertEquals(180f, countdownSweep(0.5f), eps)
        assertEquals(360f, countdownSweep(1.4f), eps)
    }

    // Blocks

    @Test fun blocksCountWholeStretches() {
        assertEquals(30, blockCount(150, 5))
        assertEquals(15, blockCount(150, 10))
        assertEquals(16, blockCount(155, 10))
        assertEquals(0, blockCount(150, 0))
        assertEquals(22, blocksFilled(110, 150, 5))
        assertEquals(0, blocksFilled(4, 150, 5))
        assertEquals(30, blocksFilled(400, 150, 5))
        assertEquals(0, blocksFilled(-10, 150, 5))
    }

    @Test fun blockRowsFitTheSize() {
        assertEquals(3, blockRows(30, PictureSize.Small))
        assertEquals(2, blockRows(30, PictureSize.Wide))
        assertEquals(1, blockRows(1, PictureSize.Small))
    }

    // Day bars

    @Test fun dayBarsScaleToTheTallestWithRoomSpare() {
        val g = dayBarsGeometry(listOf(10f, 20f, null, 40f), 30f)
        assertEquals(1f / 1.1f, g.heights[3], eps)
        assertEquals(0f, g.heights[2], eps)
        assertEquals(30f / 44f, g.target!!, eps)
        assertNull(dayBarsGeometry(listOf(1f), null).target)
    }

    @Test fun aTargetAboveEveryDayStillFits() {
        val g = dayBarsGeometry(listOf(5f, 6f), 50f)
        assertEquals(1f / 1.1f, g.target!!, eps)
        assertTrue(g.heights.all { it < g.target })
    }

    @Test fun emptyDayBarsDoNotDivideByZero() {
        assertEquals(listOf(0f, 0f), dayBarsGeometry(listOf(null, 0f), null).heights)
    }

    // Range fill

    @Test fun rangeFillIsOnAHundred() {
        val g = rangeFillGeometry(34f, 72f)
        assertEquals(0.34f, g.start, eps)
        assertEquals(0.72f, g.end, eps)
        assertTrue(g.gain)
        assertTrue(!rangeFillGeometry(58f, 50f).gain)
        assertEquals(1f, rangeFillGeometry(10f, 140f).end, eps)
    }

    // Split bar

    @Test fun splitBarKeepsOrderGapsAndRanksBySize() {
        val parts = splitBarParts(listOf(14f, 8f, 6f), width = 116f, gap = 2f)
        assertEquals(listOf(0, 1, 2), parts.map { it.index })
        assertEquals(listOf(0, 1, 2), parts.map { it.rank })
        assertEquals(0f, parts.first().start, eps)
        assertEquals(116f, parts.last().end, eps)
        parts.zipWithNext().forEach { (a, b) -> assertEquals(2f, b.start - a.end, eps) }
        assertEquals(112f * 14f / 28f, parts[0].end - parts[0].start, eps)
    }

    @Test fun splitBarDropsEmptyPartsAndRanksTheBiggestFirst() {
        val parts = splitBarParts(listOf(0f, 9f, 14f), width = 100f, gap = 2f)
        assertEquals(listOf(1, 2), parts.map { it.index })
        assertEquals(listOf(1, 0), parts.map { it.rank })
        assertEquals(1f, splitAlpha(0), eps)
        assertTrue(splitAlpha(1) > splitAlpha(2))
        assertTrue(splitBarParts(listOf(0f, -1f), 100f, 2f).isEmpty())
    }

    // Week rings

    @Test fun weekRingsFitTheirSlots() {
        val g = weekRingsGeometry(width = 324f, height = 70f, stroke = 4f)
        assertEquals(7, g.centres.size)
        assertEquals(324f / 14f, g.centres.first(), eps)
        val slot = 324f / 7f
        assertTrue(2 * g.radius + 4f <= slot)
        assertTrue(2 * g.radius + 4f <= 70f + eps)
        val small = weekRingsGeometry(width = 136f, height = 60f, stroke = 3f)
        assertTrue(2 * small.radius + 3f <= 136f / 7f)
    }

    // Age scale

    @Test fun ageScaleSpansYourAgeEitherWayAndTheRange() {
        assertEquals(23f..37f, ageScaleSpan(27f, 31f, 30f))
        assertEquals(23f..45f, ageScaleSpan(38f, 43f, 30f))
        assertEquals(0.5f, ageScaleX(30f, 27f, 31f, 30f), eps)
        assertTrue(ageScaleX(27f, 27f, 31f, 30f) < ageScaleX(31f, 27f, 31f, 30f))
    }

    @Test fun ageScaleRimLightsTheBandsEndPastYourAge() {
        assertNull(ageScaleRim(27f, 31f, 30f))
        val older = ageScaleRim(32f, 36f, 31f)
        val younger = ageScaleRim(24f, 28f, 31f)
        assertNotNull(older)
        assertNotNull(younger)
        assertEquals(cardX(ageScaleX(36f, 32f, 36f, 31f), PictureSize.Wide), older!!.x, eps)
        assertEquals(cardX(ageScaleX(24f, 24f, 28f, 31f), PictureSize.Wide), younger!!.x, eps)
        assertTrue(older.x > 0.5f && younger.x < 0.5f)
        assertEquals(1f, older.y, eps)
    }
}
