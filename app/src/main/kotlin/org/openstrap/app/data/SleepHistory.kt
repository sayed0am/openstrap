package org.openstrap.app.data

import org.openstrap.app.data.db.SleepSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Night-by-night sleep: which sleep belongs to which night, and the week's usual sleep times. How regular the week was
 * is the Sleep Regularity Index ([SleepRegularity]), which compares the nights minute by minute rather than only their
 * bedtimes and wake times.
 */
// The one day rule for sleep and every pick that goes by it, kept together so no screen counts a night another way.
@Suppress("TooManyFunctions")
object SleepHistory {
    /** Nights the Sleep screen's week looks back over. */
    const val WEEK_NIGHTS = 7

    /** Nights of sleep loaded for the week view and the in-sleep vitals' usual values. */
    const val NIGHTS = 14

    /** Where [readingsOf] starts, on the evening before. */
    private const val READINGS_FROM_HOUR = 18

    /**
     * The day a sleep ending at [end] belongs to: the day it ended on, the day you woke up (user, 2026-11-04). The one
     * rule for every screen, widget and model, naps and Health Connect imports included, by the times shown, so a night
     * edited to end after midnight moves to the new day. It wins over the strap's own day for the session
     * ([strapDay]), which only keys the strap's nightly rows.
     */
    fun nightOf(end: Long, zone: ZoneId): LocalDate = Days.date(end, zone)

    /** The day [s] belongs to, by its end as shown ([nightOf]). */
    fun nightOf(s: SleepSession, zone: ZoneId): LocalDate = nightOf(s.end, zone)

    /** Where the end of every sleep that belongs to [date] ([nightOf]) falls: that day, midnight to midnight. */
    fun endingOn(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DayWindow = DayWindow.of(date, zone)

    /**
     * The span the night readings of [date]'s sleep are read over (heart rate, breathing, blood oxygen): from
     * [READINGS_FROM_HOUR] the evening before, by the clock, to the end of [date], so a night that began the evening
     * before is read whole and one that ended late that evening too. Readings outside the sleep are for the screens to
     * leave out.
     */
    fun readingsOf(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): DayWindow =
        // By the clock, not hours back from midnight, which is an hour off when the clocks change.
        DayWindow(date.minusDays(1).atTime(READINGS_FROM_HOUR, 0).atZone(zone).toEpochSecond(), endingOn(date, zone).to)

    /**
     * The strap's own day for [s] (its local midnight), kept through an edit: what the strap's nightly rows (its readiness
     * with the night's HRV and resting heart rate, and the sleep-apnea summary) are keyed by, so they go with the night
     * from that strap session whichever day it ends on. A session from before v2 has none and takes the day it ended on.
     */
    fun strapDay(s: SleepSession, zone: ZoneId): Long = if (s.day > 0) s.day else Days.start(s.end, zone)

    /** The main sleep of each day ([nightOf]): the longest session that ended on it and isn't a nap. */
    fun mainSleeps(sessions: List<SleepSession>, zone: ZoneId): Map<LocalDate, SleepSession> =
        sessions.filter { !it.nap }.groupBy { nightOf(it, zone) }.mapValues { (_, v) -> v.maxBy { it.end - it.start } }

    /**
     * The main sleep of the day whose [endingOn] window is [night]: the longest session that isn't a nap and ended in
     * it. What the Sleep tab's hero and the Sleep widget show.
     */
    fun mainSleep(sessions: List<SleepSession>, night: DayWindow): SleepSession? =
        sessions.filter { !it.nap && it.end in night.from..night.to }.maxByOrNull { it.end - it.start }

    /**
     * [mainSleep] over the strap's sessions with their edits, by the times shown, so Today and the widgets pick the
     * same night. The deleted ones are left out, unless [deleted] asks for the main sleep among them instead, which is
     * what the No sleep recorded card offers to restore.
     */
    fun mainSleep(resolved: List<SleepEdits.Resolved>, night: DayWindow, deleted: Boolean = false): SleepEdits.Resolved? =
        resolved.filter { it.deleted == deleted && !it.strap.nap && it.end in night.from..night.to }.maxByOrNull { it.end - it.start }

    /**
     * Time asleep on the week's other nights, for the longest or shortest rule (`TabCopy.night`):
     * the [WEEK_NIGHTS] nights ending on [date] ([nights] by [mainSleeps]) without [date] itself, those with a sleep.
     */
    fun weekOthers(nights: Map<LocalDate, SleepSession>, date: LocalDate): List<Long> =
        (WEEK_NIGHTS - 1 downTo 1).mapNotNull { nights[date.minusDays(it.toLong())] }.map(::asleepSeconds)

    /**
     * Time asleep from the strap's stage totals, or the time in bed less awake when it didn't send them: the rule
     * recovery counts by ([RecoveryEngine.asleepMinutes]).
     */
    fun asleepSeconds(s: SleepSession): Long = RecoveryEngine.asleepMinutes(s) * 60L

    /**
     * The average fall-asleep and wake-up time over the nights up to [end]. [bedtime] and [wake] are average minutes
     * past midnight (bedtime can be past 24 times 60 when it's after midnight, and [clockMinutes] prints it).
     */
    data class UsualTimes(val bedtime: Int, val wake: Int, val nights: Int)

    /**
     * Usual times over the [WEEK_NIGHTS] nights ending on [end], or null when none of them has sleep. Bedtime is
     * averaged from noon and wake time from midnight, so neither wraps around in a normal night.
     */
    fun usualTimes(nights: Map<LocalDate, SleepSession>, end: LocalDate, zone: ZoneId): UsualTimes? {
        val window = (0 until WEEK_NIGHTS).mapNotNull { nights[end.minusDays(it.toLong())] }
        if (window.isEmpty()) return null
        val bed = window.map { minutesFrom(it.start, zone, noon = true) }
        val wake = window.map { minutesFrom(it.end, zone, noon = false) }
        return UsualTimes(bed.average().roundToInt() + 12 * 60, wake.average().roundToInt(), window.size)
    }

    /** [minutes] past midnight as a time of day, wrapped into 0 until 24 h. */
    fun clockMinutes(minutes: Int): Int = Math.floorMod(minutes, 24 * 60)

    private fun minutesFrom(epochSeconds: Long, zone: ZoneId, noon: Boolean): Double {
        val t = Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalTime()
        val m = t.hour * 60 + t.minute
        return (if (noon) Math.floorMod(m - 12 * 60, 24 * 60) else m).toDouble()
    }
}
