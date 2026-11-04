package org.openstrap.app.data

import org.openstrap.app.R
import org.openstrap.app.i18n.Strings

/**
 * Words for the body-signal alert, written to the FDA's General Wellness guidance as revised on 6 January 2026
 * (docs/algorithms/evidence-review.md, "Wording"). Never name a disease, never call a value abnormal, never show a
 * diagnostic threshold, and never prompt clinical action beyond saying professional evaluation "may be helpful".
 */
object BodySignalsCopy {
    val TITLE: String get() = Strings.app.get(R.string.core_body_title)
    val SHORT: String get() = Strings.app.get(R.string.core_body_short)

    /** The tail every alert text ends with: likely everyday causes, a gentle suggestion, and the wellness disclaimer. */
    val CAUSES: String get() = Strings.app.get(R.string.core_body_causes)
    val ADVICE: String get() = Strings.app.get(R.string.core_body_advice)
    val DISCLAIMER: String get() = Strings.app.get(R.string.core_body_disclaimer)

    /**
     * The push text, as the evidence review gives it: "Several of your overnight signals (resting heart rate, skin
     * temperature) are outside your usual range for the second night. …". The bracket names the signals that are off,
     * and "for the second night" is said only when they have been off for two nights or more, else "last night".
     */
    fun pushText(signals: List<String>, nights: Int, strings: Strings = Strings.app): String {
        val names = signals.ifEmpty { listOf(strings.get(R.string.core_body_family_cardiac)) }.joinToString(", ")
        val again = nights >= 2
        val first = when {
            signals.size > 1 && again -> strings.format(R.string.core_body_push_several_again, names)
            signals.size > 1 -> strings.format(R.string.core_body_push_several_once, names)
            again -> strings.format(R.string.core_body_push_one_again, names)
            else -> strings.format(R.string.core_body_push_one_once, names)
        }
        val tail = listOf(R.string.core_body_causes, R.string.core_body_advice, R.string.core_body_disclaimer).map(strings::get)
        return (listOf(first) + tail).joinToString(" ")
    }
}
