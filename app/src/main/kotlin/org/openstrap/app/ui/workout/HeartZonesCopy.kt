package org.openstrap.app.ui.workout

import org.openstrap.app.R
import org.openstrap.app.data.HeartZones
import org.openstrap.app.data.MaxHeartRate
import org.openstrap.app.i18n.Strings
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the Heart rate zones page says (board SettingsHeartZones). Pure, so it's tested without the page. The text is in
 * res/values/strings_workout_zones.xml.
 */
object HeartZonesCopy {
    val TITLE: String get() = Strings.app.get(R.string.workout_zones_title)
    val MAX_TITLE: String get() = Strings.app.get(R.string.workout_zones_max_title)
    val SET_IT: String get() = Strings.app.get(R.string.workout_zones_set_it)
    val LEARN_IT: String get() = Strings.app.get(R.string.workout_zones_learn_it)

    /** Under the list: one set, what reads them, and how to change them. */
    val NOTE: String get() = Strings.app.get(R.string.workout_zones_note)

    /** The You page's row: "Max 192 bpm, the same zones for every sport". */
    fun youLine(maxHr: Int?, strings: Strings = Strings.app): String =
        if (maxHr != null) strings.format(R.string.workout_zones_you_line_max, maxHr) else strings.get(R.string.workout_zones_you_line)

    /** The Profile page's row for your max, which opens the Heart rate zones page. */
    val MAX_ROW: String get() = Strings.app.get(R.string.workout_zones_max_row)
    fun profileLine(set: Int?, strings: Strings = Strings.app): String =
        if (set != null) strings.format(R.string.workout_zones_profile_set, set) else strings.get(R.string.workout_zones_profile_worked_out)

    /** Where your max comes from, under it: "Learned from your hardest efforts, 3 Oct". */
    fun source(m: MaxHeartRate, locale: Locale = Locale.getDefault(), strings: Strings = Strings.app): String = when (m.source) {
        MaxHeartRate.Source.Set -> strings.get(R.string.workout_zones_source_set)
        MaxHeartRate.Source.Learned -> m.learnedOn?.let {
            strings.format(R.string.workout_zones_source_learned_on, it.format(DateTimeFormatter.ofPattern("d MMM", locale)))
        } ?: strings.get(R.string.workout_zones_source_learned)
        MaxHeartRate.Source.Age -> strings.get(R.string.workout_zones_source_age)
        MaxHeartRate.Source.Strap -> strings.get(R.string.workout_zones_source_strap)
        MaxHeartRate.Source.Guess -> strings.get(R.string.workout_zones_source_guess)
    }

    /** A zone's name on the list: "Zone 2". */
    fun zoneName(zone: Int, strings: Strings = Strings.app): String = strings.format(R.string.workout_zones_zone_name, zone + 1)

    /** A zone's heart rates on the list: "115 to 133", and "172 and up" for zone 5. */
    fun zoneBpm(zones: HeartZones, zone: Int, maxHr: Int, strings: Strings = Strings.app): String {
        val from = zones.startBpm(zone, maxHr)
        return zones.endBpm(zone, maxHr)?.let { strings.format(R.string.workout_zones_bpm_range, from, it) }
            ?: strings.format(R.string.workout_zones_bpm_up, from)
    }

    /** The sheet for moving a zone's edges: its steppers' names and what they do. */
    val STARTS_AT: String get() = Strings.app.get(R.string.workout_zones_starts_at)
    val ENDS_AT: String get() = Strings.app.get(R.string.workout_zones_ends_at)
    fun edgesNote(zone: Int, strings: Strings = Strings.app): String = strings.get(
        when (zone) {
            0 -> R.string.workout_zones_edges_first
            HeartZones.STANDARD.starts.lastIndex -> R.string.workout_zones_edges_last
            else -> R.string.workout_zones_edges_other
        },
    )

    val RESET: String get() = Strings.app.get(R.string.workout_zones_reset)

    /** The sheet for setting your max yourself. */
    val SET_TITLE: String get() = Strings.app.get(R.string.workout_zones_set_title)
    val SET_NOTE: String get() = Strings.app.get(R.string.workout_zones_set_note)
    val SET_FIELD: String get() = Strings.app.get(R.string.workout_zones_set_field)
    val SAVE: String get() = Strings.app.get(R.string.workout_zones_save)

    /** A typed max: 120 to 230 bpm, as the profile has always taken it. */
    fun parseMax(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in org.openstrap.app.data.Recovery.TESTED_MAX_RANGE }
}
