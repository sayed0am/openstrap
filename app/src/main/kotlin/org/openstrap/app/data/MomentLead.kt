package org.openstrap.app.data

/** What the Moment widget leads with (widgets spec 3.1): its number and word, or what stands in for them. */
enum class MomentLead {
    /** Recovery's score and status word, the waking lead. */
    Recovery,

    /** Last night's sleep score and word, "Slept 7 h 40": waking with a Recovery widget placed. */
    LastNight,

    /** Energy left. */
    Energy,

    /** The Start button: the workout window's point. */
    Start,

    /** The sport and "Recording since 17:42". */
    Workout,

    /** Minutes since the workout. */
    AfterWorkout,

    /** The moment's sentence (evening and night). */
    Sentence,
}

/**
 * What Moment leads with at [moment] when the widgets in [placed] are on the home screen (widgets spec 3.1, the
 * duplicate rule): never a metric that has its own widget placed, but the moment's next lead instead. Only waking
 * has one to give way: Recovery falls back to last night, and with Sleep placed too, to Energy, which has no widget.
 */
fun momentLead(moment: Moment, placed: Set<WidgetKind>): MomentLead = when (moment) {
    Moment.Waking -> when {
        WidgetKind.Recovery !in placed -> MomentLead.Recovery
        WidgetKind.Sleep !in placed -> MomentLead.LastNight
        else -> MomentLead.Energy
    }
    Moment.Midday -> MomentLead.Energy
    Moment.WorkoutWindow -> MomentLead.Start
    Moment.During -> MomentLead.Workout
    Moment.After -> MomentLead.AfterWorkout
    Moment.Evening, Moment.Night -> MomentLead.Sentence
}
