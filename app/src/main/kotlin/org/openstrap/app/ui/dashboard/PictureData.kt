package org.openstrap.app.ui.dashboard

import org.openstrap.app.presentation.ChartPoint
import java.time.DayOfWeek
import java.time.LocalDate

/*
 * The series the picture cards draw that no screen worked out before (picture cards plan, Data audit): the stress day
 * strip, the goal facts of Steps and the week of steps. Pure, so they're tested without a screen.
 */

/** Stress readings run 0 to 100. */
const val STRESS_MAX = 100f

/** The stress strip's stretch: an hour. */
const val STRESS_STRETCH_SECONDS = 3600L

/**
 * The day from [dayStart] to [dayEnd] (epoch seconds, [dayEnd] its last second) as stretches of [stretchSeconds]: each
 * one's mean stress from [samples] as 0..1 of [STRESS_MAX], null where it has no reading, the hours to come included.
 * A day with a clock change has 23 or 25 hourly stretches.
 */
fun stressStrip(
    samples: List<ChartPoint>, dayStart: Long, dayEnd: Long, stretchSeconds: Long = STRESS_STRETCH_SECONDS,
): List<Float?> {
    if (dayEnd <= dayStart || stretchSeconds <= 0) return emptyList()
    val count = ((dayEnd - dayStart + stretchSeconds) / stretchSeconds).toInt()
    val sums = FloatArray(count)
    val counts = IntArray(count)
    for (p in samples) {
        if (p.x < dayStart || p.x > dayEnd) continue
        val i = ((p.x - dayStart) / stretchSeconds).toInt().coerceAtMost(count - 1)
        sums[i] += p.y
        counts[i]++
    }
    return List(count) { i -> if (counts[i] == 0) null else (sums[i] / counts[i] / STRESS_MAX).coerceIn(0f, 1f) }
}

/** Days Steps' best of the month looks back over, before the day. */
const val STEPS_MONTH_DAYS = 30

/**
 * Steps' goal facts for [date] ([goalTag] with [GoalMetric.Steps]): [value] steps against [goal], the best day ever
 * before [date] ([bestBefore], raised to the month's best when that's higher, since a stored best can lag), the best
 * of the [STEPS_MONTH_DAYS] days before [date] from [steps], and the days running at or above the goal, [date]
 * included (0 when [date] isn't). [steps] is each day's steps by date, and [date]'s own entry gives way to [value].
 */
fun stepsGoalFacts(date: LocalDate, value: Int, goal: Int, steps: Map<LocalDate, Int>, bestBefore: Int?): GoalFacts {
    val monthBest = (1..STEPS_MONTH_DAYS).mapNotNull { steps[date.minusDays(it.toLong())] }.filter { it > 0 }.maxOrNull()
    val best = listOfNotNull(bestBefore, monthBest).maxOrNull()
    var streak = 0
    if (goal > 0 && value >= goal) {
        streak = 1
        while ((steps[date.minusDays(streak.toLong())] ?: 0) >= goal) streak++
    }
    return GoalFacts(value, goal, best, monthBest, streak)
}

/** One week of steps a day, first day to last: null for days to come. */
data class StepsWeek(val days: List<Pair<LocalDate, Int?>>, val goal: Int)

/**
 * The week of [date] (spec 4.2, Your week: Monday to Sunday by default, [firstDay] otherwise), each day's steps from
 * [steps], [date]'s own as [value] (today's live count), 0 for a past day without steps, null for days after [today].
 */
fun stepsWeek(
    date: LocalDate, value: Int, goal: Int, steps: Map<LocalDate, Int>, today: LocalDate = date,
    firstDay: DayOfWeek = DayOfWeek.MONDAY,
): StepsWeek {
    val start = org.openstrap.app.data.Weeks.start(date, firstDay)
    val days = (0 until org.openstrap.app.data.Weeks.DAYS).map { k ->
        val d = start.plusDays(k.toLong())
        d to when {
            d > today -> null
            d == date -> value
            else -> steps[d] ?: 0
        }
    }
    return StepsWeek(days, goal)
}
