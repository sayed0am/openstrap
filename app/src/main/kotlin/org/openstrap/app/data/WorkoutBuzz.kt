package org.openstrap.app.data

/**
 * Every workout buzz on the strap by name (workouts spec 5), so each kind of workout says the same thing the same
 * way. A pattern is the motor's on and off times in milliseconds, alternating, starting on.
 *
 * Needs a hardware check that they can be told apart on the wrist, with the phone in a bag.
 */
object WorkoutBuzz {
    /** Rest over, work starts: two short. */
    val WORK_STARTS = listOf(150, 150, 150)

    /** Work over, rest starts: one short. */
    val REST_STARTS = listOf(150)

    /** Halfway, or a period over: one long. */
    val LONG = listOf(700)

    /** Goal reached, or the workout's length over: three short. */
    val GOAL = listOf(150, 150, 150, 150, 150)

    /** Mind and body's end: one soft, the shortest the strap does. */
    val SOFT = listOf(60)

    /** The buzz for a switch between phases. */
    fun of(s: Switch): List<Int> = when (s) {
        Switch.WorkStarts -> WORK_STARTS
        Switch.RestStarts -> REST_STARTS
        Switch.Finished -> GOAL
    }
}
