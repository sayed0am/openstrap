package org.openstrap.app.data.settings

import org.openstrap.app.R
import org.openstrap.app.tr
import org.openstrap.app.trRaw

/** Your daily targets. The defaults are the app's old fixed goals, and the strap's 300 for calories. */
data class Goals(
    val steps: Int = DEFAULT_STEPS,
    val sleepMinutes: Int = DEFAULT_SLEEP_MINUTES,
    val activeHours: Int = DEFAULT_ACTIVE_HOURS,
    /** Active kcal a day, sent to the strap as its calorie target (HEALTH key 0x53). */
    val calories: Int = DEFAULT_CALORIES,
) {
    val sleepSeconds: Long get() = sleepMinutes * 60L

    /** "8,000 steps, 300 active kcal, 8 h sleep, 10 active hours". */
    fun summary(): String {
        val h = sleepMinutes / 60
        val m = sleepMinutes % 60
        val sleep = if (m == 0) tr(R.string.goals_hours_only, h) else tr(R.string.goals_h_min, h, m)
        return trRaw(R.string.goals_steps_active_kcal_sleep).format(steps, calories, sleep, activeHours)
    }

    companion object {
        const val DEFAULT_STEPS = 8000
        const val DEFAULT_SLEEP_MINUTES = 8 * 60
        const val DEFAULT_ACTIVE_HOURS = 10
        /** Zepp's default and the fallback suggestion. */
        const val DEFAULT_CALORIES = 300
        val STEPS = 1000..50000
        val SLEEP_MINUTES = 4 * 60..12 * 60
        val ACTIVE_HOURS = 1..18
        /** The strap's own range for its calorie target. */
        val CALORIES = 200..3000
        /** What the minus and plus buttons move the calorie goal by. */
        const val CALORIES_STEP = 10
    }
}

/**
 * The calorie goal's rules (calories spec 3.4 and 4.6): the buttons' steps, a typed value, and the goal suggested from
 * the last four weeks. Pure, so it's tested without the sheet.
 */
object CalorieGoal {
    /** Said under the field when a typed value is outside [Goals.CALORIES]. */
    const val OUT_OF_RANGE = "Pick a number from 200 to 3,000"

    /** Fewer full days than this and there is no suggestion: a few days say nothing about your usual. */
    const val MIN_FULL_DAYS = 7

    /** Full days the suggestion reads, the last four weeks. */
    const val DAYS = 28

    /** [value] moved by [by] (a button's ±[Goals.CALORIES_STEP]), held in [Goals.CALORIES]. */
    fun step(value: Int, by: Int): Int = (value + by).coerceIn(Goals.CALORIES)

    /** A typed goal: digits, commas and spaces allowed, in [Goals.CALORIES]; null otherwise, and the old goal stays. */
    fun parse(text: String): Int? = text.filter { it != ',' && it != ' ' && it != ' ' }.toIntOrNull()?.takeIf { it in Goals.CALORIES }

    /**
     * The goal suggested from [fullDays], the active kcal of the full days in the last [DAYS]: their median plus 10 %,
     * rounded to 10, held in [Goals.CALORIES]. Null under [MIN_FULL_DAYS], so the sheet never says "from your last
     * 4 weeks" over two days.
     */
    fun suggested(fullDays: List<Float>): Int? {
        if (fullDays.size < MIN_FULL_DAYS) return null
        val sorted = fullDays.sorted()
        val n = sorted.size
        val median = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
        return (Math.round(median * 1.1f / 10f) * 10).coerceIn(Goals.CALORIES)
    }
}
