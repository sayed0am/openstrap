package org.openstrap.app.data

import org.openstrap.app.data.db.ActivityMinute
import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.db.SleepSession
import org.openstrap.protocol.history.ActivityMode
import java.time.LocalDate
import java.time.ZoneId

/**
 * A night worked out from the strap's per-minute sleep signal, for days where the strap's own sleep result (type 72)
 * is missing or wrong. On firmware 3.27.0.1 the strap still marks each minute asleep (activity mode 8) as before, but
 * the result it builds from them came out empty, misplaced or a fraction of the night (analysis/native/10-sleep.md and
 * the 2026-11-07 notes). Only asleep and awake: the minute signal has no deep, light or REM, and heart-rate rules for
 * those did worse than guessing the usual shares, so these nights have no stages and no strap score.
 *
 * The rules follow Zepp's phone algorithm where it is known (an 18:00 to 18:00 window, at least an hour asleep) and
 * were checked against five nights the strap scored correctly: start and end mostly within 15 minutes of the strap's.
 * Lying awake but perfectly still before sleep can't be told apart from sleep and is counted as asleep.
 */
object MinuteSleep {
    /** The night ending on a date is looked for from 18:00 the evening before to 18:00 on the date. */
    const val WINDOW_BEFORE_MIDNIGHT = 6 * 3600L
    const val WINDOW_AFTER_MIDNIGHT = 18 * 3600L

    /** Asleep stretches this close are one night, the minutes between them awake. */
    const val JOIN_GAP_MINUTES = 30

    /**
     * The night's edges move in until [SETTLE_MINUTES] in a row have at most [RESTLESS_ALLOWED] minutes that are not
     * asleep or move more than [RESTLESS_INTENSITY]: the strap starts its own sleep once the turning over stops.
     */
    const val SETTLE_MINUTES = 15
    const val RESTLESS_INTENSITY = 5
    const val RESTLESS_ALLOWED = 2

    /** Less than this asleep is not a night. */
    const val MIN_ASLEEP_MINUTES = 120

    /** Awake stretches this long or longer count as waking up. */
    const val WAKE_MINUTES = 3

    /** A night is only made once the strap has written minutes this long after its end, so it isn't cut short. */
    const val SETTLED_AFTER_SECONDS = 30 * 60L

    /** The strap's sleep is replaced when less than this share of it is asleep by its own minutes (Nov 5: none). */
    const val MIN_STRAP_COVERAGE = 0.5

    /**
     * Or when the minutes find at least [MISSING_MINUTES] more sleep and the strap's span covers less than
     * [MIN_STRAP_OVERLAP] of the night (Nov 7: 91 minutes of a five-hour night).
     */
    const val MISSING_MINUTES = 90
    const val MIN_STRAP_OVERLAP = 0.6

    /** Days looked at after each sync. */
    const val DAYS_BACK = 14L

    private val ASLEEP_MODES = setOf(ActivityMode.REM, ActivityMode.NREM, ActivityMode.DREAM, ActivityMode.USER_SLEEP)

    data class Night(
        val start: Long,
        val end: Long,
        val asleepMinutes: Int,
        val awakeMinutes: Int,
        val wakeCount: Int,
        val averageHeartRate: Int?,
    )

    fun isAsleep(m: ActivityMinute?): Boolean = m != null && m.kind in ASLEEP_MODES

    private const val MINUTE = 60L

    /** Every minute from [from] to [to], [to] left out. */
    private fun minutesIn(from: Long, to: Long): LongProgression = from until to step MINUTE

    /**
     * The night in [windowStart, windowEnd) from [minutes], or null when there is none yet. [latest] is the newest
     * minute the strap has written: a night that may still be going on is left for a later sync.
     */
    fun night(minutes: List<ActivityMinute>, windowStart: Long, windowEnd: Long, latest: Long?): Night? {
        val byTime = minutes.associateBy { it.timestamp }
        fun asleep(t: Long) = isAsleep(byTime[t])
        fun asleepIn(from: Long, to: Long): Int = minutesIn(from, to).count { asleep(it) }
        val block = blocks(Math.floorDiv(windowStart, MINUTE) * MINUTE, windowEnd, ::asleep).maxByOrNull { asleepIn(it.first, it.second) }
        val edges = block?.let { trim(it.first, it.second, byTime) }
        val asleepMinutes = edges?.let { asleepIn(it.first, it.second) } ?: 0
        val finished = edges != null && latest != null && latest >= edges.second + SETTLED_AFTER_SECONDS - MINUTE
        if (edges == null || asleepMinutes < MIN_ASLEEP_MINUTES || !finished) return null
        val (start, end) = edges
        val rates = minutesIn(start, end).mapNotNull { m -> byTime[m]?.takeIf { isAsleep(it) }?.heartRate }
        return Night(
            start, end, asleepMinutes, awakeMinutes = ((end - start) / MINUTE).toInt() - asleepMinutes,
            wakeCount = wakes(minutesIn(start, end).map(::asleep)),
            averageHeartRate = rates.takeIf { it.isNotEmpty() }?.average()?.toInt(),
        )
    }

    /** Runs of asleep minutes in [from, to), joined across gaps of up to [JOIN_GAP_MINUTES], as [start, end). */
    private fun blocks(from: Long, to: Long, asleep: (Long) -> Boolean): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        for (t in minutesIn(from, to).filter(asleep)) {
            val last = out.lastOrNull()
            if (last != null && t - last.second <= MINUTE * JOIN_GAP_MINUTES) out[out.size - 1] = last.first to t + MINUTE
            else out += t to t + MINUTE
        }
        return out
    }

    /** Awake stretches of at least [WAKE_MINUTES] between asleep minutes, from one flag per minute. */
    private fun wakes(asleep: List<Boolean>): Int {
        var count = 0
        var run = 0
        for (a in asleep) {
            if (a && run >= WAKE_MINUTES) count++
            run = if (a) 0 else run + 1
        }
        return count
    }

    /** [start, end) with the restless edges taken off ([SETTLE_MINUTES]). */
    private fun trim(start: Long, end: Long, byTime: Map<Long, ActivityMinute>): Pair<Long, Long> {
        val window = SETTLE_MINUTES * MINUTE
        fun restless(m: Long) = byTime[m].let { it == null || !isAsleep(it) || it.intensity > RESTLESS_INTENSITY }
        fun settled(from: Long) = minutesIn(from, from + window).count(::restless) <= RESTLESS_ALLOWED
        var a = start
        while (a < end - window && !settled(a)) a += MINUTE
        var b = end
        while (b > a + window && !settled(b - window)) b -= MINUTE
        return a to b
    }

    /**
     * Whether [night] should stand in for the strap's main sleep [strap] for the same day: when the strap has none,
     * when its sleep mostly lies on minutes it marked awake ([MIN_STRAP_COVERAGE]), or when it found only a small part of
     * the night ([MISSING_MINUTES], [MIN_STRAP_OVERLAP]). [strapAsleepMinutes] is how many minutes inside [strap] are
     * asleep by the minute signal. A night unstrap made before is always made again.
     */
    fun replaces(strap: SleepSession?, strapAsleepMinutes: Int, night: Night): Boolean {
        if (strap == null || strap.fromMinutes) return true
        val strapMinutes = ((strap.end - strap.start) / MINUTE).toInt().coerceAtLeast(1)
        val overlap = (minOf(strap.end, night.end) - maxOf(strap.start, night.start)).coerceAtLeast(0) / MINUTE.toDouble()
        val nightMinutes = ((night.end - night.start) / MINUTE).coerceAtLeast(1)
        val onAwake = strapAsleepMinutes.toDouble() / strapMinutes < MIN_STRAP_COVERAGE
        val missing = night.asleepMinutes - RecoveryEngine.asleepMinutes(strap)
        val partOnly = missing >= MISSING_MINUTES && overlap / nightMinutes < MIN_STRAP_OVERLAP
        return onAwake || partOnly
    }

    /** [night] as the day's main sleep, marked as unstrap's. */
    fun session(night: Night, date: LocalDate): SleepSession = SleepSession(
        start = night.start, end = night.end, day = DayKey.of(date), nap = false,
        averageHeartRate = night.averageHeartRate, awakeMinutes = night.awakeMinutes, wakeCount = night.wakeCount,
        source = SleepSession.FROM_MINUTES,
    )

    /**
     * Goes over the last [DAYS_BACK] days after a sync and puts in a night from the minutes wherever [replaces] says so.
     * Returns the dates it wrote. Idempotent: the strap re-sends a day's result on later syncs, and this replaces it
     * again for as long as the strap's stays wrong, or keeps the strap's once it is right.
     */
    suspend fun apply(dao: HealthDao, zone: ZoneId, now: Long): List<LocalDate> {
        val latest = dao.latestActivity(now) ?: return emptyList()
        val today = Days.date(now, zone)
        val written = (DAYS_BACK downTo 0).map { today.minusDays(it) }.filter { date ->
            val session = replacementFor(dao, date, zone, latest)
            if (session != null) dao.replaceSleepDay(date, nap = false, listOf(session), emptyList())
            session != null
        }
        if (written.isNotEmpty()) {
            dao.deleteNapStagesInsideMainSleep()
            dao.deleteNapsInsideMainSleep()
        }
        return written
    }

    /** The night to write for [date], or null when the strap's stays or there is nothing new to write. */
    private suspend fun replacementFor(dao: HealthDao, date: LocalDate, zone: ZoneId, latest: Long): SleepSession? {
        val midnight = Days.start(date, zone)
        val from = midnight - WINDOW_BEFORE_MIDNIGHT
        val to = midnight + WINDOW_AFTER_MIDNIGHT
        val minutes = dao.activityOnce(from, to - 1)
        val night = minutes.takeIf { m -> m.any { isAsleep(it) } }?.let { night(it, from, to, latest) } ?: return null
        val strap = dao.sleepSessionsOnce(from - DayKey.SECONDS, to + DayKey.SECONDS)
            .filter { !it.nap && it.strapDate == date }
            .maxByOrNull { it.end - it.start }
        val strapAsleep = strap?.let { s ->
            val inside = dao.activityOnce(s.start, s.end - 1).associateBy { it.timestamp }
            minutesIn(s.start, s.end).count { isAsleep(inside[it]) }
        } ?: 0
        return session(night, date).takeIf { replaces(strap, strapAsleep, night) && it != strap }
    }
}
