package org.openstrap.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.SleepStage

class SummariesTest {
    private fun minute(t: Long, steps: Int, hr: Int? = null) = ActivityMinute(t, steps, 0, 0, hr)

    @Test
    fun hourlyStepsBucketsByHour() {
        val day = 1_000_000L / 86_400 * 86_400
        val buckets = Summaries.hourlySteps(
            listOf(minute(day, 10), minute(day + 59 * 60, 5), minute(day + 3600, 7), minute(day + 25 * 3600, 99)),
            day,
            java.time.ZoneOffset.UTC,
        )
        assertEquals(24, buckets.size)
        assertEquals(15f, buckets[0])
        assertEquals(7f, buckets[1])
        assertEquals(22, Summaries.totalSteps(listOf(minute(day, 10), minute(day + 60, 12))))
    }

    @Test
    fun heartRateSeriesPrefersDedicatedSamplesAndDropsJunk() {
        val series = Summaries.heartRateSeries(
            hr = listOf(HeartRateSample(60, 70)),
            minutes = listOf(minute(0, 0, 65), minute(60, 0, 80), minute(120, 0, 255)),
        )
        assertEquals(listOf(0L to 65, 60L to 70), series)
    }

    @Test
    fun restingHrIsLowestSustainedAverage() {
        val series = (0 until 120).map { i -> i * 60L to if (i in 40..80) 52 else 75 }
        assertEquals(52, Summaries.estimateRestingHr(series))
        assertNull(Summaries.estimateRestingHr(listOf(0L to 60, 60L to 61)))
    }

    @Test
    fun sleepBreakdownSeparatesAwake() {
        val stages = listOf(
            SleepStage(0, 600, 0, SleepStage.LIGHT),
            SleepStage(600, 900, 0, SleepStage.DEEP),
            SleepStage(900, 1000, 0, SleepStage.AWAKE),
        )
        val b = Summaries.sleepBreakdown(stages)
        assertEquals(900L, b.asleepSeconds)
        assertEquals(100L, b.awakeSeconds)
        assertEquals(300L, b.byStage[SleepStage.DEEP])
    }
}

class BucketTest {
    @Test
    fun averagesIntoFiveMinuteBuckets() {
        val s = listOf(0L to 60, 60L to 70, 299L to 80, 300L to 90)
        assertEquals(listOf(0L to 70, 300L to 90), Summaries.bucketAverage(s))
    }
}
