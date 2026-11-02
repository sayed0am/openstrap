package org.openstrap.app.data

/**
 * The routes an `open_metric` intent names a detail by, for code below the screens: the widgets build their taps from
 * these, and the app reads them back into its details.
 */
object DetailRoutes {
    const val STEPS = "Steps"
    const val RECOVERY = "Recovery"
    const val SLEEP = "Sleep"
    const val SLEEP_SCORE = "SleepScore"
}
