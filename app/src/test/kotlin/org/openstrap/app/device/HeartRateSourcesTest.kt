package org.openstrap.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.device.HeartRateSources.Decision
import org.openstrap.app.device.HeartRateSources.Source

class HeartRateSourcesTest {
    private val s = HeartRateSources()

    @Test
    fun liveStreamAloneIsShown() {
        assertEquals(Decision.Reading(72), s.offer(Source.CHANNEL, 72, false, 0))
    }

    @Test
    fun sessionFramesWinOverTheLiveStream() {
        assertEquals(Decision.Reading(150), s.offer(Source.SESSION, 150, false, 1_000))
        // The stuck live stream the user saw: ignored while session frames come.
        assertEquals(Decision.Ignore, s.offer(Source.CHANNEL, 80, false, 1_200))
        assertEquals(Decision.Ignore, s.offer(Source.MEASUREMENT, 80, false, 1_300))
        assertEquals(Decision.Reading(152), s.offer(Source.SESSION, 152, false, 2_000))
    }

    @Test
    fun sessionNoReadingShowsNoneAndHoldsTheLiveStreamOff() {
        s.offer(Source.SESSION, 150, false, 0)
        assertEquals(Decision.NoReading, s.offer(Source.SESSION, null, true, 1_000))
        assertEquals(Decision.Ignore, s.offer(Source.CHANNEL, 80, false, 1_500))
    }

    @Test
    fun liveStreamTakesOverWhenSessionFramesStop() {
        s.offer(Source.SESSION, 150, false, 0)
        assertEquals(Decision.Ignore, s.offer(Source.CHANNEL, 148, false, HeartRateSources.SESSION_FRESH_MS - 1))
        assertEquals(Decision.Reading(148), s.offer(Source.CHANNEL, 148, false, HeartRateSources.SESSION_FRESH_MS))
    }

    @Test
    fun measurementOnlyWithoutTheChannel() {
        s.offer(Source.CHANNEL, 72, false, 0)
        assertEquals(Decision.Ignore, s.offer(Source.MEASUREMENT, 73, false, 500))
        assertEquals(Decision.Reading(73), s.offer(Source.MEASUREMENT, 73, false, HeartRateSources.CHANNEL_FRESH_MS))
        assertEquals(Decision.NoReading, s.offer(Source.MEASUREMENT, null, true, HeartRateSources.CHANNEL_FRESH_MS + 1_000))
    }

    @Test
    fun frameWithoutHeartRateKeepsTheSessionInCharge() {
        assertEquals(Decision.Ignore, s.offer(Source.SESSION, null, false, 0))
        assertEquals(Decision.Ignore, s.offer(Source.CHANNEL, 80, false, 500))
    }

    @Test
    fun aReadingGoesStaleAfterTenSeconds() {
        assertFalse(s.isStale(0))
        s.offer(Source.CHANNEL, 72, false, 0)
        assertFalse(s.isStale(HeartRateSources.STALE_MS - 1))
        assertTrue(s.isStale(HeartRateSources.STALE_MS))
        s.cleared()
        assertFalse(s.isStale(HeartRateSources.STALE_MS * 2))
    }

    @Test
    fun resetForgetsTheSession() {
        s.offer(Source.SESSION, 150, false, 0)
        s.reset()
        assertEquals(Decision.Reading(80), s.offer(Source.CHANNEL, 80, false, 100))
    }
}
