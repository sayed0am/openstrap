package org.openstrap.app.widget

import org.openstrap.app.data.HeartZones
import org.openstrap.app.data.MomentInputs
import org.openstrap.app.data.Recovery
import org.openstrap.app.data.Workouts
import org.openstrap.app.data.db.SleepSession
import org.openstrap.app.data.db.SleepStage
import org.openstrap.app.data.db.Workout
import org.openstrap.app.presentation.ChartPoint
import org.openstrap.app.presentation.TabCopy
import org.openstrap.app.presentation.TodayCopy
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import org.openstrap.app.data.Weeks

/**
 * The mockups' sample day as each widget's model, made by the same factories the widgets load through, so the
 * picker's generated previews (spec 6.4, Android 15 and later) show the real look, and the tests fit the same values:
 * Recovery 78 with 52 bpm, 64 ms and a sleep of 82; 8,432 steps of 10,000; a night of 22:58 to 06:56 scored 82;
 * outdoor runs as the usual; midday with 62 energy left.
 */
object WidgetSamples {
    /** Recovery this morning: 78, Recovered, its week. */
    fun recovery(today: LocalDate, locale: Locale = Locale.getDefault()): RecoveryWidgetModel {
        val week = (6 downTo 0).map { today.minusDays(it.toLong()) }.zip(listOf(65, 82, 55, 72, 88, 60, 78))
        val day = RecoveryDay(
            date = today,
            shown = TodayCopy.recoveryShown(78, false, null, null, TabCopy.RECOVERY_WAITING),
            sentence = TodayCopy.recoveryMeaning(78, false),
            firstNights = false,
            restingHr = 52f, hrv = 64.4f, sleep = 82,
            week = week,
        )
        return RecoveryWidgetModel.from(day, null, locale)
    }

    /** 8,432 steps by 14:00 against 10,000, the week before today with two days met. */
    fun steps(today: LocalDate, locale: Locale = Locale.getDefault()): StepsWidgetModel {
        val weekStart = Weeks.start(today, Weeks.firstDay(locale))
        val past = listOf(7_800, 11_200, 5_900, 10_400, 6_300, 9_100).mapIndexedNotNull { i, s ->
            weekStart.plusDays(i.toLong()).takeIf { it < today }?.let { it to s }
        }.toMap()
        val hourly = List(24) { h -> if (h in 7..14) 1_054f else 0f }
        return StepsWidgetModel.from(today, 8_432, hourly, 14, 10_000, past, locale)
    }

    /** The night ending on [today]: 22:58 to 06:56, score 82, woke 3 times, its stages, three shorter nights before. */
    fun sleepNight(today: LocalDate, zone: ZoneId): SleepWidgetNight {
        fun at(date: LocalDate, h: Int, m: Int) = date.atTime(h, m).atZone(zone).toEpochSecond()
        val start = at(today.minusDays(1), 22, 58)
        val session = SleepSession(start, at(today, 6, 56), score = 82, wakeCount = 3, deepMinutes = 92, remMinutes = 101, lightMinutes = 267, awakeMinutes = 18)
        val plan = listOf(
            SleepStage.LIGHT to 60, SleepStage.DEEP to 60, SleepStage.LIGHT to 90, SleepStage.REM to 50, SleepStage.AWAKE to 10,
            SleepStage.LIGHT to 117, SleepStage.DEEP to 32, SleepStage.REM to 51, SleepStage.AWAKE to 8,
        )
        var t = start
        val stages = plan.map { (stage, min) -> SleepStage(t, t + min * 60L, start, stage).also { t += min * 60L } }
        val week = listOf(1 to 370, 2 to 420, 3 to 440).associate { (ago, min) ->
            val d = today.minusDays(ago.toLong())
            val e = at(d, 7, 0)
            d to SleepSession(e - min * 60L, e)
        }
        return SleepWidgetNight.of(today, session, stages, week + (today to session))
    }

    fun sleep(today: LocalDate, zone: ZoneId): SleepWidgetModel = SleepWidgetModel.from(sleepNight(today, zone), null, firstNight = false, napsToday = false, zone = zone)

    /**
     * Outdoor runs the most (one of 5.2 km this week), then walks, rides, a treadmill run and strength, at [now].
     */
    fun start(now: Instant, zone: ZoneId, locale: Locale = Locale.getDefault()): StartWidgetModel {
        val today = now.atZone(zone).toLocalDate()
        fun workout(daysAgo: Int, hour: Int, type: Int, minutes: Int, meters: Float? = null, sets: Int? = null) = run {
            val start = today.minusDays(daysAgo.toLong()).atTime(hour, 0).atZone(zone).toEpochSecond()
            val w = Workout(start, start + minutes * 60L, type, minutes * 60, null, null, null, null, null, null, null, null, null)
            Workouts.analyse(w, emptyList(), emptyList(), 190, HeartZones.STANDARD).copy(distanceMeters = meters, sets = sets)
        }
        val history = listOf(
            workout(4, 18, 1, 60, meters = 5_200f), workout(11, 7, 1, 45), workout(18, 7, 1, 45),
            workout(8, 8, 3, 30), workout(12, 8, 3, 30), workout(9, 17, 4, 50), workout(15, 17, 4, 50),
            workout(1, 19, 2, 35), workout(6, 10, 52, 40, sets = 12),
        )
        return StartWidgetModel.from(history, emptyMap(), true, null, now, zone, locale)
    }

    /** Midday on [today]: 62 energy left from 84 at waking, drained 60 and recharged 38, the day's heart so far. */
    fun moment(today: LocalDate, zone: ZoneId): MomentWidgetModel {
        val night = sleepNight(today, zone)
        val wake = night.session.end
        val now = today.atTime(13, 0).atZone(zone).toInstant()
        val n = ((now.epochSecond - wake) / 900).toInt().coerceAtLeast(1)
        val points = (0..n).map { i -> Recovery.ChargePoint(wake + i * 900L, 84f + (62 - 84f) * i / n, Recovery.State.Activity) }
        val midnight = today.atStartOfDay(zone).toEpochSecond()
        val heart = (0..((now.epochSecond - midnight) / 300).toInt()).map { i ->
            ChartPoint(midnight + i * 300L, 85f + 25f * kotlin.math.sin(i / 12.0).toFloat())
        }
        val recovery = TodayCopy.recoveryShown(78, false, null, null, TabCopy.RECOVERY_WAITING)
        val input = MomentWidgetInput(
            inputs = MomentInputs(today, false, null, wake, LocalTime.of(22, 40), emptyList()),
            placed = emptySet(),
            recovery = recovery,
            score = recovery.value,
            alert = false,
            noScore = null,
            firstNights = false,
            energy = MomentEnergy(62, 84, 38, 60f, true, points),
            load = 42.3f,
            loadStatus = TodayCopy.LoadStatus.SoFar,
            night = night,
            usualSport = 1,
            lastWorkout = null,
            recording = null,
            dayWorkouts = emptyList(),
            heartRate = heart,
            restingHr = 52,
            steps = 8_432,
            stepGoal = 10_000,
        )
        return MomentWidgetModel.from(input, now, zone)
    }
}
