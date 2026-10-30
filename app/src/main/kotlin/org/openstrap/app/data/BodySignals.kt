package org.openstrap.app.data

import kotlin.math.max
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * "Body signals off baseline": a night-by-night state machine over overnight resting HR, skin temperature and
 * breathing rate (docs/algorithms/evidence-review.md, "Body-signal alerts"). Resting HR is the best-validated early
 * signal (NightSignal, Alavi et al. 2022); persistence is what controls false alarms (Natarajan et al. 2021); and only
 * different signal families vote, so HRV only ever supports resting HR. Wording lives in [BodySignalsCopy].
 *
 * Pure Kotlin, like [Recovery]; the constants are referenced by name in recovery.md.
 */
object BodySignals {

    /** Independent signal families: only flags from different families count as separate votes. */
    enum class Family(val label: String) { Cardiac(tr(R.string.common_resting_heart_rate)), Thermal(tr(R.string.body_signals_skin_temperature)), Respiratory(tr(R.string.body_signals_breathing_rate)) }

    enum class Flag(val family: Family?, val core: Boolean) {
        /** rhr − c ≥ max([RHR_FLAG_BPM], [FLAG_SCALES]·s). */
        RhrFlag(Family.Cardiac, core = true),
        /** rhr − c ≥ [RHR_WATCH_BPM]: watch only. */
        RhrWatch(Family.Cardiac, core = false),
        /** temp − c ≥ [TEMP_FLAG_C] and ≥ [FLAG_SCALES]·s. */
        TempFlag(Family.Thermal, core = true),
        /** rr − c ≥ [RR_FLAG_PER_MIN] and ≥ [FLAG_SCALES]·s. */
        RrFlag(Family.Respiratory, core = true),
        /** z(hrv_n) ≤ [HRV_SUPPORT_Z]: support for resting HR, never a core vote. */
        HrvSupport(null, core = false),
    }

    /** Normal; Watch is shown in the app only; Alert pushes once and caps Readiness at [Recovery.ALERT_CAP]. */
    enum class State { Normal, Watch, Alert }

    data class Day(
        /** Local midnight of the morning the night ended. */
        val day: Long,
        val state: State,
        val flags: Set<Flag>,
        /** The signals that are off, in plain words ("resting heart rate up 6 bpm"). */
        val signals: List<String>,
        /** The families with a core flag tonight, as plain names for the push text. */
        val families: List<String>,
        /** Consecutive nights (bridging one missing night) with a core flag, ending tonight. */
        val nightsOff: Int,
        /** Likely causes the user noted or the load shows; annotated, never used to suppress. */
        val causes: List<String>,
        /** Valid nights in the resting-HR baseline; no alerts below [MIN_ALERT_NIGHTS]. */
        val baselineNights: Int,
    )

    /** Baseline per signal: the last [ALERT_BASELINE_NIGHTS] valid nights, excluding the [RECENT_EXCLUDED] most recent. */
    const val ALERT_BASELINE_NIGHTS = 28
    const val RECENT_EXCLUDED = 2
    /** No alerts before this many valid nights; a 28-night window spans a whole menstrual cycle. */
    const val MIN_ALERT_NIGHTS = 14
    const val RHR_FLAG_BPM = 4f
    const val RHR_WATCH_BPM = 3f
    /** +0.5 °C: the luteal-phase rise is about +0.3 °C at the finger, so this is the lowest that won't fire every cycle. */
    const val TEMP_FLAG_C = 0.5f
    const val RR_FLAG_PER_MIN = 2f
    /** Breathing rate this far up is an alert on its own (36.4 % of symptomatic cases vs 4.3 % of healthy people). */
    const val RR_ALERT_PER_MIN = 3f
    const val FLAG_SCALES = 2f
    const val HRV_SUPPORT_Z = -2f
    /** Alert when any core flag shows on this many of the last [PERSISTENCE_WINDOW] nights. */
    const val PERSISTENCE_NIGHTS = 3
    const val PERSISTENCE_WINDOW = 7
    /** An alert clears after this many consecutive nights without a core flag. */
    const val CLEAR_NIGHTS = 2

    private const val DAY = 86_400L

    /** Whole nights between two local midnights, tolerant of a DST hour. */
    private fun nightsBetween(a: Long, b: Long): Int = Math.round((b - a).toDouble() / DAY).toInt()

    /** The overnight values the alert rule uses: our own on a valid night, else the strap's fallback. */
    private fun rhrOf(n: Recovery.Night) = n.rhrMeanSleep?.takeIf { n.valid } ?: n.strapRestingHr
    private fun hrvOf(n: Recovery.Night) = n.hrvLn ?: n.strapHrv?.takeIf { it > 0 }?.let { kotlin.math.ln(it) }

    /**
     * Runs the state machine over [nights] (any order; one per morning) and returns one [Day] per night, oldest first.
     * [causes] gives the likely causes noted for a morning (tags, a hard previous day).
     */
    fun evaluate(nights: List<Recovery.Night>, causes: (Long) -> List<String> = { emptyList() }): List<Day> {
        val sorted = nights.sortedBy { it.day }
        val out = ArrayList<Day>(sorted.size)
        for ((i, n) in sorted.withIndex()) {
            // Baseline: earlier nights, not the 2 most recent, not in Watch or Alert, valid; the last 28 of those.
            val pool = (0 until i).filter { j ->
                nightsBetween(sorted[j].day, n.day) > RECENT_EXCLUDED && out[j].state == State.Normal
            }.map { sorted[it] }
            val rhrBase = Recovery.baseline(pool.filter { it.valid }.mapNotNull { it.rhrMeanSleep }.takeLast(ALERT_BASELINE_NIGHTS), Recovery.RHR_SD_FLOOR, MIN_ALERT_NIGHTS)
            val baselineNights = pool.count { it.valid && it.rhrMeanSleep != null }.coerceAtMost(ALERT_BASELINE_NIGHTS)
            val flags = mutableSetOf<Flag>()
            val signals = mutableListOf<String>()
            var rrBig = false
            if (rhrBase != null) {
                rhrOf(n)?.let { rhr ->
                    val d = rhr - rhrBase.centre
                    if (d >= max(RHR_FLAG_BPM, FLAG_SCALES * rhrBase.scale)) flags += Flag.RhrFlag
                    if (d >= RHR_WATCH_BPM) flags += Flag.RhrWatch
                    if (d >= RHR_WATCH_BPM) signals += tr(R.string.body_signals_resting_heart_rate_up, d.toInt())
                }
                val tempBase = Recovery.baseline(pool.mapNotNull { it.skinTemperature }.takeLast(ALERT_BASELINE_NIGHTS), Recovery.TEMP_SD_FLOOR, MIN_ALERT_NIGHTS)
                if (tempBase != null) n.skinTemperature?.let { t ->
                    val d = t - tempBase.centre
                    if (d >= TEMP_FLAG_C && d >= FLAG_SCALES * tempBase.scale) { flags += Flag.TempFlag; signals += tr(R.string.body_signals_skin_temperature_up_from) }
                }
                val rrBase = Recovery.baseline(pool.mapNotNull { it.breathRate }.takeLast(ALERT_BASELINE_NIGHTS), Recovery.BREATH_SD_FLOOR, MIN_ALERT_NIGHTS)
                if (rrBase != null) n.breathRate?.let { rr ->
                    val d = rr - rrBase.centre
                    if (d >= RR_FLAG_PER_MIN && d >= FLAG_SCALES * rrBase.scale) { flags += Flag.RrFlag; signals += tr(R.string.body_signals_breathing_rate_up_min, "%.1f".format(d)) }
                    if (d >= RR_ALERT_PER_MIN && d >= FLAG_SCALES * rrBase.scale) rrBig = true
                }
                val hrvBase = Recovery.baseline(pool.filter { it.valid }.mapNotNull { it.hrvLn }.takeLast(ALERT_BASELINE_NIGHTS), Recovery.HRV_LN_SD_FLOOR, MIN_ALERT_NIGHTS)
                if (hrvBase != null) hrvOf(n)?.let { if (hrvBase.z(it) <= HRV_SUPPORT_Z) { flags += Flag.HrvSupport; signals += tr(R.string.body_signals_hrv_well_below_your) } }
            }
            val core = flags.filter { it.core }
            val families = core.mapNotNull { it.family }.distinct()

            // Persistence, bridging one missing night: two or more missing nights reset it.
            val prev = out.lastOrNull()
            val gap = prev?.let { nightsBetween(it.day, n.day) - 1 } ?: Int.MAX_VALUE
            val linked = prev != null && gap <= 1
            val nightsOff = if (core.isEmpty()) 0 else if (linked && prev.flags.any { it.core }) prev.nightsOff + 1 else 1
            val rhrTwice = Flag.RhrFlag in flags && linked && Flag.RhrFlag in prev.flags
            // Core flags over the last 7 nights, counting only nights after the latest reset.
            var since = i
            while (since > 0 && nightsBetween(sorted[since - 1].day, sorted[since].day) - 1 <= 1) since--
            val recent = (since..i).filter { nightsBetween(sorted[it].day, n.day) < PERSISTENCE_WINDOW }
                .count { j -> if (j == i) core.isNotEmpty() else out[j].flags.any { it.core } }

            val alertNow = rhrTwice || families.size >= 2 || (core.isNotEmpty() && recent >= PERSISTENCE_NIGHTS) || rrBig
            // An alert holds until CLEAR_NIGHTS consecutive nights without a core flag.
            var clearRun = 0
            if (linked && prev.state == State.Alert && core.isEmpty()) {
                clearRun = 1
                var k = i - 1
                while (k > 0 && out[k].flags.none { it.core } && out[k].state == State.Alert && nightsBetween(sorted[k - 1].day, sorted[k].day) - 1 <= 1) { clearRun++; k-- }
            }
            val state = when {
                alertNow -> State.Alert
                linked && prev.state == State.Alert && clearRun < CLEAR_NIGHTS -> State.Alert
                core.isNotEmpty() || (Flag.RhrWatch in flags && Flag.HrvSupport in flags) -> State.Watch
                else -> State.Normal
            }
            val noted = if (state == State.Normal) emptyList() else causes(n.day)
            out += Day(n.day, state, flags, signals, families.map { it.label }, nightsOff, noted, baselineNights)
        }
        return out
    }
}
