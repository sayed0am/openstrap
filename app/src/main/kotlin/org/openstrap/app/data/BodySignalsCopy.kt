package org.openstrap.app.data

import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * Words for the body-signal alert, written to the FDA's General Wellness guidance as revised on 6 January 2026
 * (docs/algorithms/evidence-review.md, "Wording"). Never name a disease, never call a value abnormal, never show a
 * diagnostic threshold, and never prompt clinical action beyond saying professional evaluation "may be helpful".
 */
object BodySignalsCopy {
    val TITLE: String get() = tr(R.string.body_signals_copy_some_body_signals_are)
    val SHORT: String get() = tr(R.string.body_signals_copy_some_signals_are_off)

    /** The tail every alert text ends with: likely everyday causes, a gentle suggestion, and the wellness disclaimer. */
    val CAUSES: String get() = tr(R.string.body_signals_copy_this_can_happen_with)
    val ADVICE: String get() = tr(R.string.body_signals_copy_an_easier_day_may)
    val DISCLAIMER: String get() = tr(R.string.body_signals_copy_this_is_a_wellness)

    /**
     * The push text, as the evidence review gives it: "Several of your overnight signals (resting heart rate, skin
     * temperature) are outside your usual range for the second night. …". The bracket names the signals that are off;
     * "for the second night" is said only when they have been off for two nights or more, else "last night".
     */
    fun pushText(signals: List<String>, nights: Int): String {
        val names = signals.ifEmpty { listOf(tr(R.string.common_resting_heart_rate)) }.joinToString(", ")
        val lead = if (signals.size > 1) tr(R.string.body_signals_copy_several_of_your_overnight, names) else tr(R.string.body_signals_copy_your_overnight_is, names)
        val time = if (nights >= 2) tr(R.string.body_signals_copy_for_the_second_night) else tr(R.string.common_last_night)
        return tr(R.string.body_signals_copy_outside_your_usual_range, lead, time, CAUSES, ADVICE, DISCLAIMER)
    }
}
