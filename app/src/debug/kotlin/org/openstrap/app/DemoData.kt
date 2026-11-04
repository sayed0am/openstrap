package org.openstrap.app

import org.openstrap.app.data.db.ActivityExtendedMinute
import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.DailyMaxHr
import org.openstrap.app.data.db.HrvSdnnSample
import org.openstrap.app.data.db.OsaResultRow
import org.openstrap.app.data.db.RecoveryHeartRateRow
import org.openstrap.app.data.db.SleepSpo2Sample
import org.openstrap.app.data.db.SportEffectRow
import org.openstrap.app.data.db.Workout
import org.openstrap.app.data.db.WorkoutHeartRate
import org.openstrap.app.data.db.BreathRate
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.HeartRateSample
import org.openstrap.app.data.db.HrvSample
import org.openstrap.app.data.db.PaiSample
import org.openstrap.app.data.db.RestingHeartRate
import org.openstrap.app.data.db.SkinTemperature
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.data.db.Spo2Sample
import org.openstrap.app.data.db.StressSample
import org.openstrap.app.data.db.AppWorkout
import org.openstrap.app.data.db.AppWorkoutHeartRate
import org.openstrap.app.data.db.AppWorkoutRoutePoint
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Debug builds only: fills an empty database (an emulator, never a real strap's) with eight days of
 * plausible data (three weeks, so readiness has a settled baseline) so screens can be checked without wearing the strap for a week.
 */
object DemoData {
    suspend fun fill(dao: HealthDao) {
        val zone = ZoneId.systemDefault()
        val rnd = Random(7)
        for (back in 20 downTo 0) {
            val date = LocalDate.now().minusDays(back.toLong())
            val midnight = date.atStartOfDay(zone).toEpochSecond()
            // Night: 23:20 the evening before until about 07:05.
            val sleepStart = midnight - 40 * 60
            val sleepEnd = midnight + 7 * 3600 + 5 * 60 + rnd.nextInt(-20, 20) * 60
            val stages = buildList {
                var t = sleepStart
                val cycle = listOf(SleepStage.LIGHT to 25, SleepStage.DEEP to 35, SleepStage.LIGHT to 20, SleepStage.REM to 18, SleepStage.AWAKE to 3)
                while (t < sleepEnd) for ((stage, minutes) in cycle) {
                    if (t >= sleepEnd) break
                    val end = minOf(sleepEnd, t + (minutes + rnd.nextInt(-5, 6)) * 60L)
                    add(SleepStage(t, end, sleepStart, stage))
                    t = end
                }
            }
            fun mins(s: Int) = (stages.filter { it.stage == s }.sumOf { it.end - it.start } / 60).toInt()
            val day = org.openstrap.app.data.DayKey.of(date)
            dao.insertSleepSessions(listOf(SleepSession(
                sleepStart, sleepEnd, day, nap = false, score = 72 + rnd.nextInt(0, 18), averageHeartRate = 56 + rnd.nextInt(0, 5),
                deepMinutes = mins(SleepStage.DEEP), lightMinutes = mins(SleepStage.LIGHT), remMinutes = mins(SleepStage.REM),
                awakeMinutes = mins(SleepStage.AWAKE), wakeCount = stages.count { it.stage == SleepStage.AWAKE },
            )))
            dao.insertSleepStages(stages)
            dao.insertRestingHr(listOf(RestingHeartRate(date, 56 + rnd.nextInt(0, 6))))
            dao.insertBreathRate((0 until 14).map { BreathRate(sleepStart + it * 1800L, 13 + rnd.nextInt(0, 3)) })
            dao.insertHrv((0 until 8).map { HrvSample(sleepStart + it * 3600L, (if (back % 2 == 0) 40 else 48) + rnd.nextInt(0, 12)) })

            val endOfData = if (back == 0) System.currentTimeMillis() / 1000 else midnight + 24 * 3600
            val minutes = (midnight until endOfData step 60L).toList()
            // Every other evening a 50-minute run at ~150 bpm (this morning too, so Today has one); the night
            // after it shows a little in HRV.
            val runStart = if (back == 0) midnight + 7 * 3600 + 30 * 60 else midnight + 18 * 3600
            val runEnd = runStart + 50 * 60
            val workout = (back % 2 == 1 || back == 0) && runEnd <= endOfData
            dao.insertHeartRate(minutes.map {
                val hour = (it - midnight) / 3600.0
                val asleep = it < sleepEnd
                val base = if (asleep) 52.0 + 4 * sin((it - sleepStart) / 3600.0) else 66 + 10 * sin((hour - 9) / 24 * 2 * Math.PI)
                val run = workout && it in runStart until runEnd
                HeartRateSample(it, (if (run) 148.0 + rnd.nextInt(-6, 7) else base + rnd.nextInt(-3, 4)).toInt().coerceAtLeast(45))
            })
            // Steps: a commute, a lunch walk and pottering about while awake; the run is ~160 steps a minute.
            val awake = minutes.filter { it >= sleepEnd }
            val steps = awake.map {
                val hour = (it - midnight) / 3600.0
                val run = workout && it in runStart until runEnd
                val walk = hour in 8.5..9.0 || hour in 12.5..13.0 || hour in 17.5..18.0
                when {
                    run -> 155 + rnd.nextInt(0, 12)
                    walk -> 95 + rnd.nextInt(0, 20)
                    hour < 22 && rnd.nextInt(0, 3) == 0 -> rnd.nextInt(10, 60)
                    else -> 0
                }
            }
            dao.insertActivity(awake.zip(steps) { t, n ->
                ActivityMinute(t, n, intensity = (n / 2).coerceAtMost(100), kind = 0, heartRate = null)
            })
            // Distance (~75 cm a step) and active calories come from the extended per-minute record.
            dao.insertActivityExtended(awake.zip(steps) { t, n ->
                val running = n > 140
                ActivityExtendedMinute(
                    t, floors = 0, run = if (running) 1 else 0, fatBurn = 0, stand = if (n in 1..140) 1 else 0,
                    walk = if (n in 60..140) 1 else 0, sit = if (n == 0) 1 else 0, steps = n,
                    calories = when { running -> 12; n > 60 -> 4; n > 0 -> 1; else -> 0 }, distanceCm = n * (if (running) 110 else 75),
                )
            })
            if (workout) {
                val hr = (runStart until runEnd step 5L).mapIndexed { i, t ->
                    val warm = (i / 60.0).coerceAtMost(1.0)
                    WorkoutHeartRate(runStart, t, (100 + 50 * warm + 6 * sin(i / 40.0) + rnd.nextInt(-3, 4)).toInt())
                }
                dao.insertWorkouts(listOf(Workout(
                    runStart, runEnd, sportType = 1, exerciseSeconds = 50 * 60, calories = 540 + rnd.nextInt(0, 60),
                    averageHeartRate = hr.map { it.bpm }.average().toInt(), maxHeartRate = hr.maxOf { it.bpm }, minHeartRate = hr.minOf { it.bpm },
                    aerobicTrainingEffect = 3.1f + rnd.nextFloat(), anaerobicTrainingEffect = 1.2f + rnd.nextFloat(),
                    trainingLoad = 95 + rnd.nextInt(0, 40), vo2max = 48, steps = 7800 + rnd.nextInt(0, 400),
                )))
                dao.insertWorkoutHeartRate(hr)
                dao.insertSportEffects(listOf(SportEffectRow(
                    runEnd, recoveryMinutes = 20 * 60 + rnd.nextInt(0, 600), vo2max = 48f, epoc = 60f + rnd.nextInt(0, 30),
                    weeklyLoad = 380f + (20 - back) * 6, monthlyLoad = 1400f, optimalLoadMin = 300, optimalLoadMax = 600, maximalHeartRate = 192,
                )))
                dao.insertRecoveryHeartRate(listOf(RecoveryHeartRateRow(runStart, ByteArray(180) { (150 - it / 3).toByte() })))
                dao.insertDailyMaxHr(listOf(DailyMaxHr(date, hr.maxOf { it.bpm })))
            }
            // Through the night: blood oxygen every five minutes and HRV (SDNN) every couple of hours, day and night.
            dao.insertSleepSpo2((sleepStart until sleepEnd step 300L).map { SleepSpo2Sample(it, 94 + rnd.nextInt(0, 5)) })
            dao.insertHrvSdnn((sleepStart until endOfData step 7200L).map { HrvSdnnSample(it, 45f + rnd.nextInt(0, 25)) })
            dao.insertOsaResults(listOf(OsaResultRow(midnight, 7 * 3600, 0, 95, 0, eventCount = rnd.nextInt(0, 3), version = 1)))
            // Stress: calm mornings and evenings, busier working hours.
            dao.insertStress(minutes.filter { it >= sleepEnd }.filterIndexed { i, _ -> i % 5 == 0 }.map {
                val hour = ((it - midnight) / 3600).toInt()
                StressSample(it, (if (hour in 9..16) 38 + rnd.nextInt(0, 35) else 15 + rnd.nextInt(0, 20)))
            })
            dao.insertSkinTemperature(((sleepStart until sleepEnd step 60L) + minutes.filterIndexed { i, _ -> i % 10 == 0 }).map {
                SkinTemperature(it, (if (it < sleepEnd) 34.4f else 33.2f) + 0.3f * rnd.nextFloat())
            })
            dao.insertSpo2(listOf(Spo2Sample(midnight + 3 * 3600, 96 + rnd.nextInt(0, 4))))
            dao.insertPai(listOf(PaiSample(endOfData - 60, rnd.nextFloat() * 20, 40f + back, 58, 192)))
        }
        phoneRun(dao, LocalDate.now().minusDays(1).atStartOfDay(zone).toEpochSecond() + 7 * 3600, rnd)
    }

    /**
     * A run recorded on the phone with GPS, its route kept: once round Hyde Park in London, a public place, so the
     * workout page has a map to show without anyone's real location.
     */
    private suspend fun phoneRun(dao: HealthDao, start: Long, rnd: Random) {
        // The park's paths, corner to corner, rounded off by cutting each corner twice.
        var path = listOf(51.5108 to -0.1747, 51.5126 to -0.1600, 51.5100 to -0.1585, 51.5029 to -0.1528,
            51.5024 to -0.1610, 51.5030 to -0.1745, 51.5108 to -0.1747)
        repeat(3) {
            path = path.zipWithNext { (a, b), (c, d) -> listOf((0.75 * a + 0.25 * c) to (0.75 * b + 0.25 * d), (0.25 * a + 0.75 * c) to (0.25 * b + 0.75 * d)) }
                .flatten().let { it + it.first() }
        }
        fun metres(p: Pair<Double, Double>, q: Pair<Double, Double>) =
            hypot((q.first - p.first) * 111_320, (q.second - p.second) * 111_320 * cos(Math.toRadians(p.first)))
        val legs = path.zipWithNext { p, q -> metres(p, q) }
        val total = legs.sum()
        // About 5:20 a kilometre, a fix every 3 seconds, a little faster in the middle.
        val seconds = (total / 1000 * 320).toLong()
        var leg = 0
        var done = 0.0
        val points = (0..seconds step 3L).map { s ->
            val along = total * s / seconds
            while (leg < legs.lastIndex && done + legs[leg] < along) done += legs[leg++]
            val f = ((along - done) / legs[leg]).coerceIn(0.0, 1.0)
            val (a, b) = path[leg]
            val (c, d) = path[leg + 1]
            AppWorkoutRoutePoint(start, start + s, a + (c - a) * f + rnd.nextDouble(-4e-6, 4e-6), b + (d - b) * f + rnd.nextDouble(-6e-6, 6e-6),
                accuracy = 4f + rnd.nextFloat() * 3, speed = (3.1 + 0.25 * sin(s * Math.PI / seconds)).toFloat())
        }
        val hr = (0..seconds step 5L).map { s ->
            AppWorkoutHeartRate(start, start + s, (118 + 34 * (s / 300.0).coerceAtMost(1.0) + 6 * sin(s / 200.0) + rnd.nextInt(-2, 3)).toInt())
        }
        dao.insertAppWorkouts(listOf(AppWorkout(
            start, start + seconds, sportType = 1, averageHeartRate = hr.map { it.bpm }.average().toInt(), maxHeartRate = hr.maxOf { it.bpm },
            useGps = true, keepRoute = true, distanceMeters = total.toFloat(), laps = (1..total.toInt() / 1000).joinToString(",") { "${start + it * 320L}" },
            epoc = 82f, aerobicTrainingEffect = 3.4f, recoveryMinutes = 16 * 60, vo2max = 48f,
        )))
        dao.insertAppWorkoutHeartRate(hr)
        dao.insertRoutePoints(points)
    }
}
