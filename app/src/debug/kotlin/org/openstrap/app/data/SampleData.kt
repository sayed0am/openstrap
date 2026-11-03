package org.openstrap.app.data

import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.HrvSample
import org.openstrap.app.data.db.RestingHeartRate
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.data.db.Spo2Sample
import org.openstrap.app.data.db.StressSample
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Debug builds only: fills the database with a plausible week so the UI can be previewed without a strap. */
object SampleData {
    const val AVAILABLE = true

    suspend fun load(dao: HealthDao) {
        val zone = ZoneId.systemDefault()
        val rnd = Random(42)
        val today = LocalDate.now()
        val nowEpoch = System.currentTimeMillis() / 1000
        for (d in 6 downTo 0) {
            val date = today.minusDays(d.toLong())
            val dayStart = date.atStartOfDay(zone).toEpochSecond()
            val resting = 52 + rnd.nextInt(-3, 4)

            // Sleep: ~23:15 the previous night to ~07:00, cycling light/deep/REM with brief wakes.
            val sleepStart = dayStart - 45 * 60 + rnd.nextInt(-20, 20) * 60
            val sleepEnd = dayStart + 7 * 3600 + rnd.nextInt(-30, 30) * 60
            val stages = mutableListOf<SleepStage>()
            var t = sleepStart
            var cycle = 0
            while (t < sleepEnd) {
                val plan = listOf(
                    SleepStage.LIGHT to rnd.nextInt(15, 30),
                    SleepStage.DEEP to if (cycle < 3) rnd.nextInt(20, 45) else rnd.nextInt(5, 15),
                    SleepStage.LIGHT to rnd.nextInt(10, 20),
                    SleepStage.REM to if (cycle < 2) rnd.nextInt(10, 20) else rnd.nextInt(20, 35),
                ) + if (rnd.nextInt(3) == 0) listOf(SleepStage.AWAKE to rnd.nextInt(2, 8)) else emptyList()
                for ((stage, minutes) in plan) {
                    val end = minOf(t + minutes * 60, sleepEnd)
                    if (end > t) stages += SleepStage(t, end, sleepStart, stage)
                    t = end
                }
                cycle++
            }
            dao.insertSleepSessions(listOf(SleepSession(sleepStart, sleepEnd)))
            dao.insertSleepStages(stages)

            val minutes = mutableListOf<ActivityMinute>()
            val hr = mutableListOf<HeartRateSample>()
            val stress = mutableListOf<StressSample>()
            val spo2 = mutableListOf<Spo2Sample>()
            val workoutHour = 17 + rnd.nextInt(2)
            for (m in 0 until 24 * 60) {
                val ts = dayStart + m * 60
                if (ts > nowEpoch) break
                val hour = m / 60
                val asleep = ts < sleepEnd
                val workout = hour == workoutHour && m % 60 < 45
                val walking = !asleep && !workout && rnd.nextInt(100) < (if (hour in 8..19) 18 else 4)
                val steps = when {
                    workout -> rnd.nextInt(130, 170)
                    walking -> rnd.nextInt(40, 110)
                    else -> 0
                }
                val base = if (asleep) resting - 3 + 3 * sin(m / 90.0 * PI) else resting + 14 + 6 * sin(m / 240.0 * PI)
                val bpm = when {
                    workout -> 135 + rnd.nextInt(-10, 25)
                    walking -> base + 20 + rnd.nextInt(10)
                    else -> base + rnd.nextInt(-3, 4)
                }.toInt()
                minutes += ActivityMinute(ts, steps, if (workout) 90 else if (walking) 45 else 5, if (workout) 2 else if (walking) 1 else 0, bpm)
                if (m % 5 == 0) hr += HeartRateSample(ts, bpm)
                if (!asleep && m % 30 == 0) stress += StressSample(ts, (25 + 20 * sin((hour - 8) / 10.0 * PI) + rnd.nextInt(-8, 8)).toInt().coerceIn(5, 95))
                if (asleep && m % 20 == 0) spo2 += Spo2Sample(ts, 95 + rnd.nextInt(0, 5))
            }
            dao.insertActivity(minutes)
            dao.insertHeartRate(hr)
            dao.insertStress(stress)
            dao.insertSpo2(spo2)
            dao.insertHrv((0 until 6).map { HrvSample(sleepStart + it * 3600L + 1800, 48 + rnd.nextInt(-12, 15)) })
            dao.insertRestingHr(listOf(RestingHeartRate(date, resting)))
        }
    }
}
