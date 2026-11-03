package org.openstrap.app.data

import org.openstrap.app.data.db.HealthDao
import org.openstrap.app.data.settings.UserProfile
import java.time.LocalDate
import java.time.ZoneId

/**
 * Replays the engine one day at a time over a database (a phone's, pulled over adb, or a restored CSV backup) and
 * puts each day's Readiness and contributors next to the strap's own score, the morning check-in and the day's tags.
 * This is what the strap calibration review decides with (docs/algorithms/readiness-calibration.md). Each day sees
 * only the data a phone would have had that evening, because the engine only looks backwards from the day it scores.
 */
class Backtest(private val dao: HealthDao, private val zone: ZoneId = ZoneId.systemDefault()) {

    data class Row(
        val date: LocalDate,
        val result: RecoveryResult,
        val strapScore: Int?,
        val strapHrvScore: Int?,
        val strapRestingHrScore: Int?,
        val strapSkinTemperatureScore: Int?,
        val strapBreathingScore: Int?,
        val strapSleepHrv: Int?,
        val strapSleepRestingHr: Int?,
        val checkIn: Int?,
        val tags: List<String>,
    )

    /** Every day from [from] to [to] inclusive. */
    suspend fun run(from: LocalDate, to: LocalDate, profile: UserProfile): List<Row> {
        val engine = RecoveryEngine(dao)
        val a = DayWindow.of(from, zone).from
        val b = DayWindow.of(to, zone).to
        val strap = dao.strapReadinessOnce(a - 2 * 86_400L, b + 2 * 86_400L).associateBy { it.day }
        val checkIns = dao.checkInsOnce(a, b).associateBy { it.day }
        val tags = dao.dayTagsOnce(a, b).groupBy({ it.day }, { it.tag })
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { it <= to }.toList()
        return days.map { date ->
            val day = DayWindow.of(date, zone)
            // As of the end of that day, so the charge simulation covers all of it.
            val r = engine.compute(date, profile, zone, now = day.to + 1)
            // The strap's row for the night scored, by the strap's own day, which a moved night keeps.
            val s = strap[r.tonight?.strapDay ?: day.from]
            Row(
                date, r, s?.score, s?.hrvScore, s?.restingHrScore, s?.skinTemperatureScore, s?.breathingScore, s?.sleepHrv,
                s?.sleepRestingHr, checkIns[day.from]?.feeling, tags[day.from].orEmpty(),
            )
        }
    }

    companion object {
        /**
         * The rows as CSV: one line per day, a column per Readiness contributor. `readiness` is the score the app shows
         * and `readiness_soft_min` the soft-minimum fusion beside it ([Recovery.softMinimum]), both next to the strap's
         * `strap_score` for the same day. The `charge_*` columns are that day's Charge: at wake, lowest, at the end of the
         * day (or the last worn minute), and how much was recharged and drained by each cause; `body_signals` is the
         * night's body-signal state, which scales Charge in Alert; `charge_off_overnight` marks a day restarted after a night
         * with the strap off ([Recovery.ChargeDay.strapOffOvernight]).
         */
        fun csv(rows: List<Row>): String = buildString {
            val factors = Recovery.Factor.entries
            val header = listOf("date", "readiness", "readiness_soft_min", "strap_score", "baseline_nights", "calibrating", "alert", "capped") +
                factors.map { "c_" + it.name.snakeCase() } +
                listOf(
                    "hrv_n", "hrv_samples", "rhr_n", "rhr_mean_sleep", "rhr_p10", "hr_minutes", "asleep_min",
                    "strap_hrv_score", "strap_resting_hr_score", "strap_skin_temperature_score", "strap_breathing_score",
                    "strap_sleep_hrv", "strap_sleep_resting_hr", "check_in", "tags", "load", "load_workout", "load_everyday", "chronic_load", "charge_at_wake",
                    "charge_lowest", "charge_end", "charge_recharged", "charge_drained_exercise", "charge_drained_stress",
                    "charge_drained_living", "body_signals", "charge_off_overnight",
                )
            appendLine(header.joinToString(","))
            for (row in rows) {
                val r = row.result.readiness
                val n = row.result.tonight
                val c = row.result.charge
                val byFactor = r.contributors.associate { it.factor to it.score }
                val cells = listOf(row.date, r.score, r.softMinScore, row.strapScore, r.baselineNights, r.calibrating, r.strainAlert, r.capped) +
                    factors.map { byFactor[it] } +
                    listOf(
                        n?.let(NightMetrics::hrvLn)?.let { "%.4f".format(java.util.Locale.ROOT, it) }, n?.hrvSamples, n?.restingHr.f1(),
                        n?.rhrMeanSleep.f1(), n?.rhrP10.f1(), n?.hrMinutes, n?.asleepMinutes,
                        row.strapHrvScore, row.strapRestingHrScore, row.strapSkinTemperatureScore, row.strapBreathingScore,
                        row.strapSleepHrv, row.strapSleepRestingHr, row.checkIn, row.tags.joinToString(";"),
                        row.result.load.f1(), row.result.workoutLoad.f1(), row.result.everydayLoad.f1(), row.result.chronicLoad.f1(), row.result.charge.atWake.f1(),
                        c.lowest.f1(), c.latest.f1(), c.recharged.f1(), c.drainedExercise.f1(), c.drainedStress.f1(), c.drainedLiving.f1(),
                        r.bodySignals?.state, c.strapOffOvernight,
                    )
                appendLine(cells.joinToString(",") { it?.toString() ?: "" })
            }
        }

        private fun Float?.f1() = this?.let { "%.1f".format(java.util.Locale.ROOT, it) }
        private fun String.snakeCase() = replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
    }
}
