package org.openstrap.app.export

import org.json.JSONObject

/**
 * The settings a backup carries, as settings.json beside the CSV files: what the user chose about themselves and the
 * app. Left out on purpose: the strap's address and auth key, the Zepp sign-in, the companion association, the strap's
 * battery and sync cursors (a restored cursor would skip history), call and notification buzzing (each needs a
 * permission granted on this phone), the experimental strap sessions, and the backup folder itself (its permission
 * belongs to one phone). Keys are the DataStore names in [org.openstrap.app.data.settings.SettingsStore].
 */
object SettingsBackup {
    const val FILE = "settings.json"
    private const val VERSION = 1

    enum class Type { INT, LONG, FLOAT, BOOLEAN, STRING }

    val KEYS: Map<String, Type> = linkedMapOf(
        "profile_birth_year" to Type.INT,
        "profile_sex" to Type.STRING,
        "profile_weight_kg" to Type.FLOAT,
        "profile_height_cm" to Type.INT,
        "profile_imperial" to Type.BOOLEAN,
        "profile_sleep_need_minutes" to Type.INT,
        "profile_max_hr" to Type.INT,
        "profile_hr_zones" to Type.STRING,
        "profile_activity_level" to Type.INT,
        "profile_activity_answered_at" to Type.LONG,
        "profile_body_measured_at" to Type.LONG,
        "profile_beta_blocker" to Type.BOOLEAN,
        "profile_pregnant" to Type.BOOLEAN,
        "goal_steps" to Type.INT,
        "goal_sleep_minutes" to Type.INT,
        "goal_active_hours" to Type.INT,
        "today_hidden" to Type.STRING,
        "today_pinned" to Type.STRING,
        "first_score_day" to Type.LONG,
        "live_hr" to Type.BOOLEAN,
        "auto_start_workouts" to Type.BOOLEAN,
        "wear_reminder" to Type.BOOLEAN,
        "workout_live_view" to Type.INT,
        "workout_sides" to Type.STRING,
        "buzz_pattern" to Type.STRING,
        "call_buzz" to Type.STRING,
        "update_auto" to Type.BOOLEAN,
        "quiet_hours" to Type.BOOLEAN,
        "quiet_from_minutes" to Type.INT,
        "quiet_to_minutes" to Type.INT,
        "quiet_calls" to Type.BOOLEAN,
    )

    /** The backed-up keys of [values] as JSON; anything else in it is left out. */
    fun toJson(values: Map<String, Any>): String {
        val settings = JSONObject()
        for ((key, type) in KEYS) {
            val v = values[key] ?: continue
            if (convert(v, type) != null) settings.put(key, v)
        }
        return JSONObject().put("version", VERSION).put("settings", settings).toString(2)
    }

    /**
     * The settings in [json], each as its key's type. Keys this version doesn't back up, and values of the wrong
     * type, are skipped, so a newer or damaged file restores what it can.
     */
    fun fromJson(json: String): Map<String, Any> {
        val settings = JSONObject(json).optJSONObject("settings") ?: return emptyMap()
        return buildMap {
            for ((key, type) in KEYS) {
                if (!settings.has(key)) continue
                convert(settings.get(key), type)?.let { put(key, it) }
            }
        }
    }

    /** What a restore writes: the settings this phone has never set ([present] are the keys it has). */
    fun missing(backup: Map<String, Any>, present: Set<String>): Map<String, Any> = backup.filterKeys { it !in present }

    private fun convert(v: Any, type: Type): Any? = when (type) {
        Type.INT -> (v as? Number)?.takeIf { it.toDouble() == it.toInt().toDouble() }?.toInt()
        Type.LONG -> (v as? Number)?.takeIf { it.toDouble() == it.toLong().toDouble() }?.toLong()
        Type.FLOAT -> (v as? Number)?.toFloat()
        Type.BOOLEAN -> v as? Boolean
        Type.STRING -> v as? String
    }
}
