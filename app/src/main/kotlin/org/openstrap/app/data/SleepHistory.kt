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
object SleepHistory {
    /** Nights the Sleep screen's week looks back over. */
    const val WEEK_NIGHTS = 7

    /**
     * The night a sleep ending at [end] belongs to: the day it ended on, the rule recovery counts by
     * ([RecoveryEngine.morningOf]).
     */
    fun nightOf(end: Long, zone: ZoneId): LocalDate = Days.date(end, zone)

    /** The night [s] belongs to: the day the strap gave it, else the day it ended on ([RecoveryEngine.morningOf]). */
    fun nightOf(s: SleepSession, zone: ZoneId): LocalDate = Days.date(RecoveryEngine.morningOf(s, zone), zone)

    /** The main sleep of each night: the longest session that isn't a nap, as recovery picks it ([RecoveryEngine.mainsByMorning]). */
    fun mainSleeps(sessions: List<SleepSession>, zone: ZoneId): Map<LocalDate, SleepSession> =
        RecoveryEngine.mainsByMorning(sessions, zone).mapKeys { (morning, _) -> Days.date(morning, zone) }

    /**
     * The main sleep of the night in [night] (a [DayWindow.nightEndingOn]): the longest session that isn't a nap and
     * ended in it. What the Sleep tab's hero and the Sleep widget show.
     */
    fun mainSleep(sessions: List<SleepSession>, night: DayWindow): SleepSession? =
        sessions.filter { !it.nap && it.end in night.from..night.to }.maxByOrNull { it.end - it.start }

    /**
     * Time asleep on the week's other nights, for the longest or shortest rule ([org.openstrap.app.ui.dashboard.TabCopy.night]):
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
