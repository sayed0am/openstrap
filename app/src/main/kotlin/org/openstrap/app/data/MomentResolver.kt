package org.openstrap.app.data

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The part of the day Today is shaped for (spec 4.1), highest priority first. */
enum class Moment { During, After, WorkoutWindow, Waking, Night, Evening, Midday }

/**
 * Which [Moment] it is now (spec 4.1). It only reads what the app already has (a recording, workouts, the night's
 * sleep and your usual bedtime) and works out nothing about the body. When moments overlap the first in [Moment]
 * wins.
 */
object MomentResolver {
    /** How long after waking the morning lasts, with Recovery as the Today hero; Charge leads after it. */
    const val WAKING_HOURS = 3
    private const val WAKING_SECONDS = WAKING_HOURS * 3600L

    /** How long a finished workout stays the moment. */
    const val AFTER_SECONDS = 2 * 3600L

    /** The workout window runs this long either side of your usual workout time. */
    const val WINDOW_MINUTES = 45

    /** Night starts this long before your usual bedtime, and evening [EVENING_MINUTES] before it. */
    const val NIGHT_LEAD_MINUTES = 45
    const val EVENING_MINUTES = 180

    /** Night ends here, the morning after. */
    val NIGHT_ENDS: LocalTime = LocalTime.of(4, 0)

    /** The morning starts here when no main sleep ended today. */
    val WAKE_FALLBACK: LocalTime = LocalTime.of(5, 0)

    /** Bedtime when none is known yet: three hours after it, evening starts at 19:00 as the spec asks. */
    val BEDTIME_FALLBACK: LocalTime = LocalTime.of(22, 0)

    /** Usual workout time looks back this many days and needs at least [USUAL_WORKOUT_MIN] workouts. */
    const val USUAL_WORKOUT_DAYS = 28
    const val USUAL_WORKOUT_MIN = 3

    /** True once [now] is [WAKING_HOURS] past [wake] (epoch seconds). */
    fun wakingOver(now: Long, wake: Long): Boolean = now - wake >= WAKING_SECONDS

    /**
     * The moment at [now] for [day]. [lastWorkoutEnd] and [sleepEnd] (today's main sleep) are epoch seconds,
     * [workoutStarts] the start of every workout, phone or strap, for [usualWorkoutTime]. A past [day] is always
     * [Moment.Waking]: it shows how that day began.
     */
    fun resolve(
        now: Instant,
        zone: ZoneId,
        day: LocalDate,
        recording: Boolean,
        lastWorkoutEnd: Long?,
        sleepEnd: Long?,
        usualBedtime: LocalTime?,
        workoutStarts: List<Long>,
    ): Moment {
        val local = now.atZone(zone)
        if (day < local.toLocalDate()) return Moment.Waking
        val t = now.epochSecond
        val clock = local.toLocalTime()
        if (recording) return Moment.During
        if (lastWorkoutEnd != null && t >= lastWorkoutEnd && t - lastWorkoutEnd < AFTER_SECONDS) return Moment.After
        val usualWorkout = usualWorkoutTime(workoutStarts, now, zone)
        if (usualWorkout != null && inWindow(clock, usualWorkout)) return Moment.WorkoutWindow
        val wake = sleepEnd ?: local.toLocalDate().atTime(WAKE_FALLBACK).atZone(zone).toEpochSecond()
        if (t >= wake && !wakingOver(t, wake)) return Moment.Waking
        // Evening and night are read on a clock that starts at noon, so night runs on past midnight to 04:00.
        val bed = afterNoon(usualBedtime ?: BEDTIME_FALLBACK)
        val nightEnds = afterNoon(NIGHT_ENDS)
        val nightStarts = (bed - NIGHT_LEAD_MINUTES).coerceAtLeast(0)
        val m = afterNoon(clock)
        if (m in nightStarts until nightEnds) return Moment.Night
        if (m in (bed - EVENING_MINUTES).coerceAtLeast(0) until minOf(nightStarts, nightEnds)) return Moment.Evening
        return Moment.Midday
    }

    /** How far ahead [nextChange] looks. Without a recording some moment always ends within a day. */
    const val NEXT_CHANGE_HORIZON_SECONDS = 2 * 86_400L

    /**
     * When the moment [resolve] gives at [now] next changes, from the same inputs and rules, for the Moment widget's
     * alarm (widgets spec 7). Null while [recording] (a recording has no known end, and its end updates the widget
     * anyway), for a past [day] (always [Moment.Waking]), and when nothing changes within
     * [NEXT_CHANGE_HORIZON_SECONDS].
     *
     * Past local midnight it reads the new day as Today does once the clock moves on: the day is the new date and
     * [sleepEnd], today's main sleep, no longer applies, so the morning starts at [WAKE_FALLBACK] until a sync brings
     * the night in (which updates the widgets on its own).
     *
     * Every instant where any rule can flip is a candidate, and the first candidate where [resolve] gives another
     * moment is the answer, so the two can't disagree: just before it the moment is still the one at [now].
     */
    fun nextChange(
        now: Instant,
        zone: ZoneId,
        day: LocalDate,
        recording: Boolean,
        lastWorkoutEnd: Long?,
        sleepEnd: Long?,
        usualBedtime: LocalTime?,
        workoutStarts: List<Long>,
    ): Instant? {
        val today = now.atZone(zone).toLocalDate()
        if (recording || day < today) return null
        val t = now.epochSecond
        val until = t + NEXT_CHANGE_HORIZON_SECONDS
        fun at(s: Long): Moment {
            val date = Instant.ofEpochSecond(s).atZone(zone).toLocalDate()
            val later = date > day
            return resolve(
                Instant.ofEpochSecond(s), zone, if (later) date else day, false, lastWorkoutEnd,
                if (later) null else sleepEnd, usualBedtime, workoutStarts,
            )
        }
        val current = at(t)
        return changeCandidates(t, until, zone, lastWorkoutEnd, sleepEnd, usualBedtime, workoutStarts)
            .firstOrNull { at(it) != current }?.let(Instant::ofEpochSecond)
    }

    /** Every instant in (from, until] where a rule in [resolve] can start or stop holding, in order. */
    private fun changeCandidates(
        from: Long, until: Long, zone: ZoneId, lastWorkoutEnd: Long?, sleepEnd: Long?, usualBedtime: LocalTime?,
        workoutStarts: List<Long>,
    ): List<Long> {
        val out = mutableListOf<Long>()
        // After a workout, and the morning after today's main sleep.
        lastWorkoutEnd?.let { out += it; out += it + AFTER_SECONDS }
        sleepEnd?.let { out += it; out += it + WAKING_SECONDS }
        // A workout start leaving the usual time's look-back changes the usual time, and so the window.
        val dropouts = workoutStarts.map { it + USUAL_WORKOUT_DAYS * 86_400L + 1 }.filter { it in from + 1..until }
        out += dropouts
        val first = Instant.ofEpochSecond(from).atZone(zone).toLocalDate().minusDays(1)
        val last = Instant.ofEpochSecond(until).atZone(zone).toLocalDate()
        val days = generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.toList()
        val midnights = days.map { it.atStartOfDay(zone).toEpochSecond() }
        // The usual workout time follows the weekday, so it can move at each midnight and at each dropout.
        val usuals = (listOf(from) + midnights + dropouts)
            .mapNotNull { usualWorkoutTime(workoutStarts, Instant.ofEpochSecond(it), zone) }.toSet()
        // Clock minutes where evening, night, the window and the fallback morning start or end.
        val bed = afterNoon(usualBedtime ?: BEDTIME_FALLBACK)
        val minutes = buildSet {
            listOf(0, bed - EVENING_MINUTES, bed - NIGHT_LEAD_MINUTES, afterNoon(NIGHT_ENDS))
                .forEach { add(Math.floorMod(it.coerceAtLeast(0) + 720, 1440)) }
            usuals.forEach { u ->
                val m = u.toSecondOfDay() / 60
                add(Math.floorMod(m - WINDOW_MINUTES, 1440)); add(Math.floorMod(m + WINDOW_MINUTES, 1440))
            }
        }
        out += midnights
        days.forEach { d ->
            val fallback = d.atTime(WAKE_FALLBACK).atZone(zone).toEpochSecond()
            out += fallback; out += fallback + WAKING_SECONDS
            minutes.forEach { m ->
                // A clock time the clocks going back pass twice is a boundary both times.
                val local = d.atTime(LocalTime.ofSecondOfDay(m * 60L)).atZone(zone)
                out += local.withEarlierOffsetAtOverlap().toEpochSecond(); out += local.withLaterOffsetAtOverlap().toEpochSecond()
            }
        }
        // The clocks going forward skip clock times: a boundary inside the gap passes at the jump.
        generateSequence(zone.rules.nextTransition(Instant.ofEpochSecond(from))) { zone.rules.nextTransition(it.instant) }
            .takeWhile { it.instant.epochSecond <= until }.forEach { out += it.instant.epochSecond }
        return out.filter { it in from + 1..until }.distinct().sorted()
    }

    /**
     * Your usual workout time at [now]: the median start, as a clock time, of the workouts in the last
     * [USUAL_WORKOUT_DAYS] days on [now]'s weekday when there are [USUAL_WORKOUT_MIN] or more, else of all of them when
     * there are that many, else null (no workout window yet).
     */
    fun usualWorkoutTime(starts: List<Long>, now: Instant, zone: ZoneId): LocalTime? {
        val from = now.epochSecond - USUAL_WORKOUT_DAYS * 86_400L
        val recent = starts.filter { it in from until now.epochSecond }.map { Instant.ofEpochSecond(it).atZone(zone) }
        val weekday: DayOfWeek = now.atZone(zone).dayOfWeek
        val sameDay = recent.filter { it.dayOfWeek == weekday }
        val pick = when {
            sameDay.size >= USUAL_WORKOUT_MIN -> sameDay
            recent.size >= USUAL_WORKOUT_MIN -> recent
            else -> return null
        }
        return Stats.median(pick.map { it.toLocalTime().toSecondOfDay() / 60 })?.let { LocalTime.ofSecondOfDay(it.toInt() * 60L) }
    }

    /** Within [WINDOW_MINUTES] either side of [usual], the end minute left out, across midnight too. */
    private fun inWindow(clock: LocalTime, usual: LocalTime): Boolean {
        val d = Math.floorMod(clock.toSecondOfDay() / 60 - usual.toSecondOfDay() / 60 + 720, 1440) - 720
        return d in -WINDOW_MINUTES until WINDOW_MINUTES
    }

    /** Minutes since noon: 12:00 is 0, 04:00 the next morning is 960. */
    private fun afterNoon(t: LocalTime): Int = Math.floorMod(t.toSecondOfDay() / 60 - 720, 1440)
}

/**
 * What [MomentResolver] reads, kept together for the Moment widget: it renders from these and schedules its next
 * update from the same ones ([org.openstrap.app.device.WidgetUpdater.scheduleMoment]).
 */
data class MomentInputs(
    val day: LocalDate,
    val recording: Boolean,
    val lastWorkoutEnd: Long?,
    val sleepEnd: Long?,
    val usualBedtime: LocalTime?,
    val workoutStarts: List<Long>,
) {
    fun resolve(now: Instant, zone: ZoneId): Moment =
        MomentResolver.resolve(now, zone, day, recording, lastWorkoutEnd, sleepEnd, usualBedtime, workoutStarts)

    fun nextChange(now: Instant, zone: ZoneId): Instant? =
        MomentResolver.nextChange(now, zone, day, recording, lastWorkoutEnd, sleepEnd, usualBedtime, workoutStarts)
}
