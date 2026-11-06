package org.openstrap.app.data.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.openstrap.app.R
import org.openstrap.app.data.DayKey
import org.openstrap.app.i18n.Strings
import java.time.LocalDate
import java.time.ZoneId

private val Context.dataStore by preferencesDataStore(name = "settings")

data class PairedDevice(val address: String, val authKeyHex: String, val name: String)

/**
 * The user's own Zepp credentials, used only for firmware update checks. Stored on this phone only.
 * [loginToken] is the long-lived token that buys a new app token (A3, codemap 08 §1.4); null when the sign-in didn't
 * give one or the token was typed in. [appTokenAtMs] is when [appToken] was obtained (Zepp's `lu_app_ttl`) and
 * [appTtlSeconds] its lifetime, when the server said.
 */
data class ZeppAccount(
    val appToken: String,
    val userId: String,
    val apiBase: String,
    val china: Boolean,
    val loginToken: String? = null,
    val appTokenAtMs: Long? = null,
    val appTtlSeconds: Long? = null,
) {
    override fun toString() =
        "ZeppAccount(userId=$userId, apiBase=$apiBase, china=$china, appToken=…, loginToken=${if (loginToken == null) "none" else "…"}, " +
            "appTokenAtMs=$appTokenAtMs, appTtlSeconds=$appTtlSeconds)"

    companion object {
        const val DEFAULT_API_BASE = "https://api-mifit.zepp.com/"
    }
}

/** What the user told us about themselves; every field is optional. Used by on-phone calculations and sent to the strap. */
data class UserProfile(
    val birthYear: Int? = null,
    val sex: Sex? = null,
    val weightKg: Float? = null,
    val heightCm: Int? = null,
    /** Show pounds and feet/inches; null follows the phone's region. Values are always stored metric. */
    val imperial: Boolean? = null,
    /** The user's own sleep need, 420-540 min; null uses the default for their age. */
    val sleepNeedMinutes: Int? = null,
    /** A maximum heart rate from a test; overrides the age estimate. */
    val maxHeartRate: Int? = null,
    /** Usual physical activity, level 1-5 as index 0-4 (Jurca 2005's question), and when it was last answered. */
    val activityLevel: Int? = null,
    val activityAnsweredAt: Long? = null,
    /** When weight and height were last confirmed (epoch seconds); the VO2max estimate wants them under 90 days old. */
    val bodyMeasuredAt: Long? = null,
    /** Resting heart rate doesn't reflect fitness on beta-blockers or in pregnancy, so VO2max is withheld. */
    val betaBlocker: Boolean = false,
    val pregnant: Boolean = false,
) {
    enum class Sex { Female, Male }

    val usesImperial: Boolean
        get() = imperial ?: (java.util.Locale.getDefault().country in IMPERIAL_REGIONS)

    companion object {
        /** Regions that use pounds and feet day to day. */
        val IMPERIAL_REGIONS = setOf("US", "LR", "MM")
        const val KG_PER_LB = 0.45359237f
        const val CM_PER_INCH = 2.54f
    }

    fun age(today: java.time.LocalDate = java.time.LocalDate.now()): Int? = birthYear?.let { today.year - it }
    val isEmpty get() = birthYear == null && sex == null && weightKg == null && heightCm == null
}

/**
 * The strap as last seen, kept for widgets: [battery] and [charging] from the last connection (null battery before
 * one), [lastSyncDone] when a history sync last finished (epoch seconds), null before one.
 */
/**
 * Automatic backup ([org.openstrap.app.export.AutoBackup]): the [folder] chosen (a tree URI) and its [folderName], or
 * null while off. [lastDone] is when a backup last finished (epoch seconds); [failed] the last run's failure and
 * [failedAt] when, null once a backup succeeds.
 */
data class BackupState(
    val folder: String?,
    val folderName: String?,
    val lastDone: Long?,
    val failed: BackupFailure?,
    val failedAt: Long?,
) {
    val on get() = folder != null
}

data class StrapSnapshot(val paired: Boolean, val battery: Int?, val charging: Boolean, val lastSyncDone: Long?)

/**
 * Auto sync to Health Connect: [on] or off (off by default), when OpenStrap last wrote there ([lastSynced], epoch
 * seconds, auto or by hand), and why auto sync turned itself off ([stopped]), until the user turns it on again.
 */
data class HealthConnectAuto(val on: Boolean = false, val lastSynced: Long? = null, val stopped: Stopped? = null) {
    enum class Stopped { PERMISSIONS, UNAVAILABLE }
}

/** Notification buzz patterns: alternating vibrate and pause milliseconds for the strap's motor. */
enum class BuzzPattern(@StringRes private val labelId: Int, val millis: List<Int>) {
    ONE_SHORT(R.string.core_settings_buzz_single, listOf(200)),
    TWO_SHORT(R.string.core_settings_buzz_double, listOf(150, 150, 150)),
    THREE_SHORT(R.string.core_settings_buzz_triple, listOf(120, 120, 120, 120, 120)),
    ONE_LONG(R.string.core_settings_buzz_long, listOf(600)),
    ;

    val label: String get() = Strings.app.get(labelId)
}

/** How the strap buzzes while a call rings: the find-device buzz without a break, or 600 ms motor pulses once a second. */
enum class CallBuzz(@StringRes private val labelId: Int) {
    STEADY(R.string.core_settings_call_steady),
    PULSED(R.string.core_settings_call_pulsed),
    ;

    val label: String get() = Strings.app.get(labelId)
}

/** How long the strap buzzes for a ringing call: [millis], or until it's answered or ends when null. 10 s by default. */
// The lengths are the choices themselves.
@Suppress("MagicNumber")
enum class CallBuzzLength(@StringRes private val labelId: Int, val millis: Long?) {
    FIVE(R.string.core_settings_call_5s, 5_000L),
    TEN(R.string.core_settings_call_10s, 10_000L),
    FIFTEEN(R.string.core_settings_call_15s, 15_000L),
    UNTIL_ANSWERED(R.string.core_settings_call_until_answered, null),
    ;

    val label: String get() = Strings.app.get(labelId)
}

/**
 * Hours the strap stays still for notifications, such as the night: from [from] to [to] in minutes after midnight,
 * running past midnight when [to] comes first. Calls still ring unless [calls] mutes them too.
 */
data class QuietHours(val on: Boolean = false, val from: Int = 22 * 60, val to: Int = 7 * 60, val calls: Boolean = false) {
    /** True when [minute] (after midnight) falls inside the hours. A window whose ends match is empty. */
    fun covers(minute: Int): Boolean = on && when {
        from == to -> false
        from < to -> minute in from until to
        else -> minute >= from || minute < to
    }
}

/** The app's settings. Tests keep them in a store of their own ([of]). */
class SettingsStore private constructor(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    /** [f] of the settings, only when it changes: every write re-emits the whole store, whatever key it touched. */
    private fun <T> read(f: (Preferences) -> T): Flow<T> = store.data.map(f).distinctUntilChanged()

    private val address = stringPreferencesKey(SettingsKeys.DEVICE_ADDRESS)
    private val authKey = stringPreferencesKey(SettingsKeys.DEVICE_AUTH_KEY)
    private val name = stringPreferencesKey(SettingsKeys.DEVICE_NAME)

    val pairedDevice: Flow<PairedDevice?> = read { prefs ->
        val a = prefs[address] ?: return@read null
        val k = prefs[authKey] ?: return@read null
        PairedDevice(a, k, prefs[name] ?: "Helio Strap")
    }

    suspend fun savePairedDevice(device: PairedDevice) {
        store.edit {
            it[address] = device.address
            it[authKey] = device.authKeyHex
            it[name] = device.name
        }
    }

    private val zeppToken = stringPreferencesKey(SettingsKeys.ZEPP_APP_TOKEN)
    private val zeppUser = stringPreferencesKey(SettingsKeys.ZEPP_USER_ID)
    private val zeppApi = stringPreferencesKey(SettingsKeys.ZEPP_API_BASE)
    private val zeppChina = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.ZEPP_CHINA)
    private val zeppLoginToken = stringPreferencesKey(SettingsKeys.ZEPP_LOGIN_TOKEN)
    private val zeppAppTokenAt = androidx.datastore.preferences.core.longPreferencesKey(SettingsKeys.ZEPP_APP_TOKEN_AT)
    private val zeppAppTtl = androidx.datastore.preferences.core.longPreferencesKey(SettingsKeys.ZEPP_APP_TTL)

    val zeppAccount: Flow<ZeppAccount?> = read { prefs ->
        val t = prefs[zeppToken] ?: return@read null
        ZeppAccount(
            t, prefs[zeppUser].orEmpty(), prefs[zeppApi] ?: ZeppAccount.DEFAULT_API_BASE, prefs[zeppChina] == true,
            prefs[zeppLoginToken], prefs[zeppAppTokenAt], prefs[zeppAppTtl],
        )
    }

    /** Replaces the whole sign-in, so a typed-in token drops the login token of an earlier sign-in. */
    suspend fun saveZeppAccount(account: ZeppAccount) {
        store.edit {
            it[zeppToken] = account.appToken
            it[zeppUser] = account.userId
            it[zeppApi] = account.apiBase
            it[zeppChina] = account.china
            account.loginToken?.let { v -> it[zeppLoginToken] = v } ?: it.remove(zeppLoginToken)
            account.appTokenAtMs?.let { v -> it[zeppAppTokenAt] = v } ?: it.remove(zeppAppTokenAt)
            account.appTtlSeconds?.let { v -> it[zeppAppTtl] = v } ?: it.remove(zeppAppTtl)
        }
    }

    suspend fun clearZeppAccount() {
        store.edit {
            it.remove(zeppToken); it.remove(zeppUser); it.remove(zeppApi); it.remove(zeppChina)
            it.remove(zeppLoginToken); it.remove(zeppAppTokenAt); it.remove(zeppAppTtl)
        }
    }

    private val liveHr = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.LIVE_HR)

    /** Whether live heart rate starts automatically on connect. On by default. */
    val liveHeartRate: Flow<Boolean> = read { it[liveHr] ?: true }

    suspend fun setLiveHeartRate(enabled: Boolean) {
        store.edit { it[liveHr] = enabled }
    }

    private val autoStartKey = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.AUTO_START_WORKOUTS)

    /** Notice a workout as it starts and offer to record it from where it began ([org.openstrap.app.data.AutoStart]). On by default. */
    val autoStartWorkouts: Flow<Boolean> = read { it[autoStartKey] ?: true }

    suspend fun setAutoStartWorkouts(enabled: Boolean) {
        store.edit { it[autoStartKey] = enabled }
    }

    private val wearReminderKey = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.WEAR_REMINDER)

    /**
     * Off-body reminder, as Zepp has for the strap (codemap 07 §4 N5): turns on the strap's wear reminder (GENERAL
     * key 44) on connect and shows a notification while the strap reports it is off-body. On by default.
     */
    val wearReminder: Flow<Boolean> = read { it[wearReminderKey] ?: true }

    suspend fun setWearReminder(enabled: Boolean) {
        store.edit { it[wearReminderKey] = enabled }
    }

    private val strapSessionsKey = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.STRAP_SESSIONS)

    /**
     * On by default: a phone workout also runs a session on the strap (0x0019), so the strap keeps its own record of
     * it, whose heart rate and effect the workout then takes (verified on the strap 2026-10-30, codemap 05 §5.11). A
     * debug intent turns it off, to compare the live stream without one.
     */
    val strapSessions: Flow<Boolean> = read { it[strapSessionsKey] ?: true }

    suspend fun setStrapSessions(enabled: Boolean) {
        store.edit { it[strapSessionsKey] = enabled }
    }

    private val workoutLiveViewKey = androidx.datastore.preferences.core.intPreferencesKey(SettingsKeys.WORKOUT_LIVE_VIEW)

    /** The live view a GPS workout was left on, Heart (0) or Map (1); the next one opens on it. Heart by default. */
    val workoutLiveView: Flow<Int> = read { it[workoutLiveViewKey] ?: 0 }

    suspend fun setWorkoutLiveView(view: Int) {
        store.edit { it[workoutLiveViewKey] = view }
    }

    private val workoutSidesKey = stringPreferencesKey(SettingsKeys.WORKOUT_SIDES)

    /**
     * The side of Ready's Outdoor or Indoor switch you last chose, per sport (spec 6.3), by the sport's key
     * ([org.openstrap.app.data.Workouts.Sides.outdoor]), true for indoor. Sports never switched aren't in it.
     */
    val workoutSides: Flow<Map<Int, Boolean>> = read { org.openstrap.app.data.Workouts.parseSides(it[workoutSidesKey]) }

    suspend fun setWorkoutSide(sport: Int, indoor: Boolean) {
        store.edit {
            val sides = org.openstrap.app.data.Workouts.parseSides(it[workoutSidesKey]) + (sport to indoor)
            it[workoutSidesKey] = org.openstrap.app.data.Workouts.formatSides(sides)
        }
    }

    /**
     * What Ready was set to the last time each sport started (workouts spec 4), by the sport's tile
     * ([org.openstrap.app.data.Workouts.tileType]). Sports never started aren't in it.
     */
    val sportSettings: Flow<Map<Int, org.openstrap.app.data.SportSettings>> = read { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            val prefix = SettingsKeys.SPORT_SETTINGS_PREFIX
            val sport = key.name.removePrefix(prefix).takeIf { key.name.startsWith(prefix) }?.toIntOrNull()
            if (sport == null || value !is String) null else sport to org.openstrap.app.data.SportSettings.parse(value)
        }.toMap()
    }

    suspend fun setSportSettings(sport: Int, settings: org.openstrap.app.data.SportSettings) {
        val key = stringPreferencesKey(SettingsKeys.SPORT_SETTINGS_PREFIX + org.openstrap.app.data.Workouts.tileType(sport))
        store.edit { it[key] = settings.encode() }
    }

    private val birthYear = androidx.datastore.preferences.core.intPreferencesKey(SettingsKeys.PROFILE_BIRTH_YEAR)
    private val sex = stringPreferencesKey(SettingsKeys.PROFILE_SEX)
    private val weight = androidx.datastore.preferences.core.floatPreferencesKey(SettingsKeys.PROFILE_WEIGHT_KG)
    private val height = androidx.datastore.preferences.core.intPreferencesKey(SettingsKeys.PROFILE_HEIGHT_CM)
    private val imperial = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.PROFILE_IMPERIAL)
    private val sleepNeed = androidx.datastore.preferences.core.intPreferencesKey(SettingsKeys.PROFILE_SLEEP_NEED_MINUTES)
    private val maxHr = androidx.datastore.preferences.core.intPreferencesKey(SettingsKeys.PROFILE_MAX_HR)
    private val activityLevel = androidx.datastore.preferences.core.intPreferencesKey(SettingsKeys.PROFILE_ACTIVITY_LEVEL)
    private val activityAt = longPreferencesKey(SettingsKeys.PROFILE_ACTIVITY_ANSWERED_AT)
    private val bodyAt = longPreferencesKey(SettingsKeys.PROFILE_BODY_MEASURED_AT)
    private val betaBlocker = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.PROFILE_BETA_BLOCKER)
    private val pregnant = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.PROFILE_PREGNANT)

    val profile: Flow<UserProfile> = read { prefs ->
        UserProfile(
            prefs[birthYear],
            prefs[sex]?.let { v -> UserProfile.Sex.entries.firstOrNull { it.name == v } },
            prefs[weight],
            prefs[height],
            prefs[imperial],
            prefs[sleepNeed],
            prefs[maxHr],
            prefs[activityLevel],
            prefs[activityAt],
            prefs[bodyAt],
            prefs[betaBlocker] == true,
            prefs[pregnant] == true,
        )
    }

    suspend fun saveProfile(p: UserProfile) {
        store.edit {
            if (p.birthYear != null) it[birthYear] = p.birthYear else it.remove(birthYear)
            if (p.sex != null) it[sex] = p.sex.name else it.remove(sex)
            if (p.weightKg != null) it[weight] = p.weightKg else it.remove(weight)
            if (p.heightCm != null) it[height] = p.heightCm else it.remove(height)
            if (p.imperial != null) it[imperial] = p.imperial else it.remove(imperial)
            if (p.sleepNeedMinutes != null) it[sleepNeed] = p.sleepNeedMinutes else it.remove(sleepNeed)
            if (p.maxHeartRate != null) it[maxHr] = p.maxHeartRate else it.remove(maxHr)
            if (p.activityLevel != null) it[activityLevel] = p.activityLevel else it.remove(activityLevel)
            if (p.activityAnsweredAt != null) it[activityAt] = p.activityAnsweredAt else it.remove(activityAt)
            if (p.bodyMeasuredAt != null) it[bodyAt] = p.bodyMeasuredAt else it.remove(bodyAt)
            it[betaBlocker] = p.betaBlocker
            it[pregnant] = p.pregnant
        }
    }

    /** Sets only your max heart rate ([UserProfile.maxHeartRate]), from the Heart rate zones page; null learns it again. */
    suspend fun setMaxHeartRate(bpm: Int?) {
        store.edit { if (bpm != null) it[maxHr] = bpm else it.remove(maxHr) }
    }

    private val heartZonesKey = stringPreferencesKey(SettingsKeys.PROFILE_HR_ZONES)

    /** Your heart rate zones, one set for every sport; [HeartZones.STANDARD] until moved. Not part of [saveProfile]. */
    val heartZones: Flow<org.openstrap.app.data.HeartZones> = read { prefs ->
        org.openstrap.app.data.HeartZones.parse(prefs[heartZonesKey]) ?: org.openstrap.app.data.HeartZones.STANDARD
    }

    /**
     * Stores [zones] ([HeartZones.STANDARD] clears them). What reads zones follows [heartZones], so they're in use once
     * stored, and a write that fails leaves the ones in use as they are on disk.
     */
    suspend fun setHeartZones(zones: org.openstrap.app.data.HeartZones) {
        store.edit { if (zones.standard) it.remove(heartZonesKey) else it[heartZonesKey] = zones.encode() }
    }

    private val stepGoal = androidx.datastore.preferences.core.intPreferencesKey(SettingsKeys.GOAL_STEPS)
    private val sleepGoal = androidx.datastore.preferences.core.intPreferencesKey(SettingsKeys.GOAL_SLEEP_MINUTES)
    private val activeGoal = androidx.datastore.preferences.core.intPreferencesKey(SettingsKeys.GOAL_ACTIVE_HOURS)
    private val calorieGoal = androidx.datastore.preferences.core.intPreferencesKey(SettingsKeys.GOAL_CALORIES)
    private val workoutGoal = androidx.datastore.preferences.core.intPreferencesKey(SettingsKeys.GOAL_WORKOUTS_WEEK)

    val goals: Flow<Goals> = read { prefs ->
        Goals(
            prefs[stepGoal] ?: Goals.DEFAULT_STEPS,
            prefs[sleepGoal] ?: Goals.DEFAULT_SLEEP_MINUTES,
            prefs[activeGoal] ?: Goals.DEFAULT_ACTIVE_HOURS,
            prefs[calorieGoal] ?: Goals.DEFAULT_CALORIES,
            prefs[workoutGoal] ?: org.openstrap.app.data.Rewards.DEFAULT_GOAL,
        )
    }

    suspend fun saveGoals(g: Goals) {
        store.edit {
            it[stepGoal] = g.steps
            it[sleepGoal] = g.sleepMinutes
            it[activeGoal] = g.activeHours
            it[calorieGoal] = g.calories
            it[workoutGoal] = g.workoutsPerWeek
        }
    }

    private val todayHidden = stringPreferencesKey(SettingsKeys.TODAY_HIDDEN)
    private val todayPinned = stringPreferencesKey(SettingsKeys.TODAY_PINNED)

    /** Edit Today's choices: the cards hidden and the ones pinned under the hero, by name. Null until first edited. */
    val todayHiddenNames: Flow<List<String>?> = read { prefs -> prefs[todayHidden]?.split(',')?.filter { it.isNotEmpty() } }
    val todayPinnedNames: Flow<List<String>?> = read { prefs -> prefs[todayPinned]?.split(',')?.filter { it.isNotEmpty() } }

    private val goalMomentKey = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.CELEBRATE_GOAL)
    private val weeklyRecapKey = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.WEEKLY_RECAP)
    private val goalMomentWeekKey = longPreferencesKey(SettingsKeys.GOAL_MOMENT_WEEK)
    private val recapWeekKey = longPreferencesKey(SettingsKeys.RECAP_WEEK)
    private val rewardsStartedKey = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.REWARDS_STARTED)
    private val historyBadgesKey = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.HISTORY_BADGES_WAITING)

    /** Celebrations (workouts spec 3.4): the full screen goal moment, once a week. On by default. */
    val goalMoment: Flow<Boolean> = read { it[goalMomentKey] ?: true }

    suspend fun setGoalMoment(on: Boolean) {
        store.edit { it[goalMomentKey] = on }
    }

    /** Celebrations: the Monday morning recap notification. On by default. */
    val weeklyRecap: Flow<Boolean> = read { it[weeklyRecapKey] ?: true }

    suspend fun setWeeklyRecap(on: Boolean) {
        store.edit { it[weeklyRecapKey] = on }
    }

    /** The week (epoch day of its first day) the goal moment last showed for, so it shows once a week; null before. */
    val goalMomentWeek: Flow<Long?> = read { it[goalMomentWeekKey] }

    suspend fun setGoalMomentWeek(week: Long) {
        store.edit { it[goalMomentWeekKey] = week }
    }

    /** The week (epoch day of its first day) the last recap was about, so each week gets one; null before. */
    val recapWeek: Flow<Long?> = read { it[recapWeekKey] }

    suspend fun setRecapWeek(week: Long) {
        store.edit { it[recapWeekKey] = week }
    }

    /** Rewards were worked out once already: later badges are new, not past history. */
    val rewardsStarted: Flow<Boolean> = read { it[rewardsStartedKey] ?: false }

    /** The badges earned for past history on first launch wait to be shown once, all on one screen. */
    val historyBadgesWaiting: Flow<Boolean> = read { it[historyBadgesKey] ?: false }

    suspend fun startRewards(historyBadges: Boolean) {
        store.edit {
            it[rewardsStartedKey] = true
            it[historyBadgesKey] = historyBadges
        }
    }

    suspend fun historyBadgesShown() {
        store.edit { it[historyBadgesKey] = false }
    }

    private val habitAgeSeen = longPreferencesKey(SettingsKeys.HABIT_AGE_SEEN)
    private val habitAgeSeenDay = longPreferencesKey(SettingsKeys.HABIT_AGE_SEEN_DAY)

    /**
     * The Sunday of the last Habit Age estimate opened, so Today stops offering it, or null before any. Kept as its epoch
     * day, so a change of zone doesn't offer it again. Builds before that kept its local midnight, read as its date here.
     */
    val habitAgeSeenAsOf: Flow<LocalDate?> = read { p ->
        p[habitAgeSeenDay]?.let(LocalDate::ofEpochDay) ?: p[habitAgeSeen]?.let { DayKey.fromLegacy(it, ZoneId.systemDefault()) }
    }

    suspend fun setHabitAgeSeen(sunday: LocalDate) {
        store.edit { it[habitAgeSeenDay] = sunday.toEpochDay() }
    }

    private val firstScore = longPreferencesKey(SettingsKeys.FIRST_SCORE_DAY)

    /**
     * The day (epoch day) of the first real Recovery score, so it's marked on that day only; null before one. Scores
     * from before this was kept are stored as a day long past (`BEFORE_FIRST_SCORE`).
     */
    val firstScoreDay: Flow<Long?> = read { it[firstScore] }

    suspend fun setFirstScoreDay(epochDay: Long) {
        store.edit { it[firstScore] = epochDay }
    }

    suspend fun saveTodayEdits(hidden: List<String>, pinned: List<String>) {
        store.edit {
            it[todayHidden] = hidden.joinToString(",")
            it[todayPinned] = pinned.joinToString(",")
            // What changed's tile choice, which Edit Today replaced.
            it.remove(stringPreferencesKey("today_tiles"))
        }
    }

    private val companionId = androidx.datastore.preferences.core.intPreferencesKey(SettingsKeys.COMPANION_ASSOCIATION_ID)
    private val companionNo = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.COMPANION_DECLINED)

    /** The strap's companion association id ([org.openstrap.app.device.CompanionLink.LEGACY_ID] before Android 13), or null. */
    val companionAssociation: Flow<Int?> = read { it[companionId] }

    suspend fun setCompanionAssociation(id: Int) {
        store.edit { it[companionId] = id }
    }

    /** True once the user turned down Android's association prompt; the strap page then offers battery settings instead. */
    val companionDeclined: Flow<Boolean> = read { it[companionNo] == true }

    suspend fun setCompanionDeclined(declined: Boolean) {
        store.edit { it[companionNo] = declined }
    }

    private val heldOff = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.HELD_DISCONNECTED)

    /** True after the user tapped Disconnect, until they connect again; background reconnects leave the strap alone. */
    val heldDisconnected: Flow<Boolean> = read { it[heldOff] == true }

    suspend fun setHeldDisconnected(held: Boolean) {
        store.edit { it[heldOff] = held }
    }

    /**
     * Forgets the strap and the Zepp sign-in, removing only the keys about them ([STRAP_KEYS] and the history cursors),
     * so every other setting stays, a key added later included.
     */
    suspend fun forgetDevice() {
        store.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name in STRAP_KEYS || it.name.startsWith(SettingsKeys.HISTORY_CURSOR_PREFIX) }
                .forEach { prefs.remove(it) }
        }
    }

    private val hcAuto = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.HEALTH_CONNECT_AUTO)
    private val hcSynced = longPreferencesKey(SettingsKeys.HEALTH_CONNECT_SYNCED)
    private val hcStopped = stringPreferencesKey(SettingsKeys.HEALTH_CONNECT_AUTO_STOPPED)

    /** Auto sync to Health Connect, kept when the strap is forgotten: it's about the data, not the strap. */
    val healthConnectAuto: Flow<HealthConnectAuto> = read { prefs ->
        HealthConnectAuto(
            prefs[hcAuto] == true,
            prefs[hcSynced],
            prefs[hcStopped]?.let { v -> HealthConnectAuto.Stopped.entries.firstOrNull { it.name == v } },
        )
    }

    /** The user's switch; either way clears why it last stopped. */
    suspend fun setHealthConnectAuto(enabled: Boolean) {
        store.edit {
            it[hcAuto] = enabled
            it.remove(hcStopped)
        }
    }

    /** Auto sync turns itself off, and the You page says [why]. */
    suspend fun stopHealthConnectAuto(why: HealthConnectAuto.Stopped) {
        store.edit {
            it[hcAuto] = false
            it[hcStopped] = why.name
        }
    }

    /** OpenStrap wrote to Health Connect at [epochSeconds]. */
    suspend fun setHealthConnectSynced(epochSeconds: Long) {
        store.edit { it[hcSynced] = epochSeconds }
    }

    private val buzzCalls = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.BUZZ_CALLS)
    private val buzzNotifications = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.BUZZ_NOTIFICATIONS)

    /** Buzz the strap while a phone call rings. Off by default; it needs the phone-state permission. */
    val buzzOnCalls: Flow<Boolean> = read { it[buzzCalls] == true }

    /** Buzz the strap once for each app notification that would make a sound. Off by default; it needs notification access. */
    val buzzOnNotifications: Flow<Boolean> = read { it[buzzNotifications] == true }

    private val buzzPattern = stringPreferencesKey(SettingsKeys.BUZZ_PATTERN)

    /** How the strap buzzes for a notification; two short buzzes by default. */
    val notificationPattern: Flow<BuzzPattern> = read { prefs ->
        BuzzPattern.entries.firstOrNull { it.name == prefs[buzzPattern] } ?: BuzzPattern.TWO_SHORT
    }

    suspend fun setNotificationPattern(pattern: BuzzPattern) {
        store.edit { it[buzzPattern] = pattern.name }
    }

    private val callBuzz = stringPreferencesKey(SettingsKeys.CALL_BUZZ)

    /** How the strap buzzes for a call; steady by default. */
    val callBuzzStyle: Flow<CallBuzz> = read { prefs ->
        CallBuzz.entries.firstOrNull { it.name == prefs[callBuzz] } ?: CallBuzz.STEADY
    }

    suspend fun setCallBuzzStyle(style: CallBuzz) {
        store.edit { it[callBuzz] = style.name }
    }

    private val callBuzzLengthKey = stringPreferencesKey(SettingsKeys.CALL_BUZZ_LENGTH)

    /** How long the strap buzzes for a call; 10 s by default, for those who had calls on before too. */
    val callBuzzLength: Flow<CallBuzzLength> = read { prefs ->
        CallBuzzLength.entries.firstOrNull { it.name == prefs[callBuzzLengthKey] } ?: CallBuzzLength.TEN
    }

    suspend fun setCallBuzzLength(length: CallBuzzLength) {
        store.edit { it[callBuzzLengthKey] = length.name }
    }

    suspend fun setBuzzOnCalls(enabled: Boolean) {
        store.edit { it[buzzCalls] = enabled }
    }

    suspend fun setBuzzOnNotifications(enabled: Boolean) {
        store.edit { it[buzzNotifications] = enabled }
    }

    private val quietOn = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.QUIET_HOURS)
    private val quietFrom = androidx.datastore.preferences.core.intPreferencesKey(SettingsKeys.QUIET_FROM_MINUTES)
    private val quietTo = androidx.datastore.preferences.core.intPreferencesKey(SettingsKeys.QUIET_TO_MINUTES)
    private val quietCalls = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.QUIET_CALLS)

    /** When the strap doesn't buzz for notifications; off by default, 22:00 to 07:00 once turned on. */
    val quietHours: Flow<QuietHours> = read { prefs ->
        val d = QuietHours()
        QuietHours(prefs[quietOn] == true, prefs[quietFrom] ?: d.from, prefs[quietTo] ?: d.to, prefs[quietCalls] == true)
    }

    suspend fun setQuietHours(q: QuietHours) {
        store.edit {
            it[quietOn] = q.on
            it[quietFrom] = q.from.mod(24 * 60)
            it[quietTo] = q.to.mod(24 * 60)
            it[quietCalls] = q.calls
        }
    }

    private val strapBattery = androidx.datastore.preferences.core.intPreferencesKey(SettingsKeys.STRAP_BATTERY)
    private val strapCharging = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.STRAP_CHARGING)
    private val syncDone = longPreferencesKey(SettingsKeys.STRAP_LAST_SYNC_DONE)

    /**
     * What a widget's strap status line reads with the strap away (widgets spec 5): pairing, the battery and charging
     * as last connected, and when a sync last finished.
     */
    val strapSnapshot: Flow<StrapSnapshot> = read { prefs ->
        StrapSnapshot(prefs[address] != null && prefs[authKey] != null, prefs[strapBattery], prefs[strapCharging] == true, prefs[syncDone])
    }

    /** The connected strap's battery and charging, written when either changes. */
    suspend fun setStrapBattery(percent: Int, charging: Boolean) {
        store.edit {
            it[strapBattery] = percent
            it[strapCharging] = charging
        }
    }

    /** When a history sync last finished (epoch seconds). Per type cursors are [lastSync]. */
    suspend fun setLastSyncDone(epochSeconds: Long) {
        store.edit { it[syncDone] = epochSeconds }
    }

    /** Last synced timestamp (epoch seconds) for a history data type. */
    suspend fun lastSync(type: String): Long? =
        store.data.first()[longPreferencesKey(SettingsKeys.LAST_SYNC_PREFIX + type)]

    suspend fun setLastSync(type: String, epochSeconds: Long) {
        store.edit { it[longPreferencesKey(SettingsKeys.LAST_SYNC_PREFIX + type)] = epochSeconds }
    }

    private val backupFolder = stringPreferencesKey(SettingsKeys.BACKUP_FOLDER)
    private val backupFolderName = stringPreferencesKey(SettingsKeys.BACKUP_FOLDER_NAME)
    private val backupDone = longPreferencesKey(SettingsKeys.BACKUP_LAST_DONE)
    private val backupFailed = stringPreferencesKey(SettingsKeys.BACKUP_FAILED)
    private val backupFailedAt = longPreferencesKey(SettingsKeys.BACKUP_FAILED_AT)

    val backup: Flow<BackupState> = read { prefs ->
        BackupState(
            prefs[backupFolder], prefs[backupFolderName], prefs[backupDone],
            prefs[backupFailed]?.let { v -> BackupFailure.entries.firstOrNull { it.name == v } },
            prefs[backupFailedAt],
        )
    }

    /** Turns automatic backup on, or moves it to another folder; the last run's result goes with the old folder. */
    suspend fun setBackupFolder(uri: String, name: String?) {
        store.edit {
            it[backupFolder] = uri
            if (name != null) it[backupFolderName] = name else it.remove(backupFolderName)
            it.remove(backupDone); it.remove(backupFailed); it.remove(backupFailedAt)
        }
    }

    suspend fun clearBackup() {
        store.edit {
            it.remove(backupFolder); it.remove(backupFolderName); it.remove(backupDone); it.remove(backupFailed); it.remove(backupFailedAt)
        }
    }

    suspend fun setBackupDone(epochSeconds: Long) {
        store.edit {
            it[backupDone] = epochSeconds
            it.remove(backupFailed); it.remove(backupFailedAt)
        }
    }

    suspend fun setBackupFailed(failure: BackupFailure, epochSeconds: Long) {
        store.edit {
            it[backupFailed] = failure.name
            it[backupFailedAt] = epochSeconds
        }
    }

    private val updateAutoKey = androidx.datastore.preferences.core.booleanPreferencesKey(SettingsKeys.UPDATE_AUTO)
    private val updateCheckedKey = longPreferencesKey(SettingsKeys.UPDATE_CHECKED_AT)
    private val updateNotifiedKey = stringPreferencesKey(SettingsKeys.UPDATE_NOTIFIED_TAG)
    private val updateInstallingKey = stringPreferencesKey(SettingsKeys.UPDATE_INSTALLING_TAG)
    private val updateSeenKey = stringPreferencesKey(SettingsKeys.UPDATE_SEEN_TAG)

    /** The daily check for a new OpenStrap release on GitHub ([org.openstrap.app.update.AppUpdater]). On unless turned off. */
    val updateAuto: Flow<Boolean> = read { it[updateAutoKey] ?: true }

    suspend fun setUpdateAuto(on: Boolean) {
        store.edit { it[updateAutoKey] = on }
    }

    /** When the release list was last read from GitHub, epoch seconds. */
    val updateChecked: Flow<Long?> = read { it[updateCheckedKey] }

    suspend fun setUpdateChecked(epochSeconds: Long) {
        store.edit { it[updateCheckedKey] = epochSeconds }
    }

    /** The tag of the last release the daily check notified about, so each one is said once. */
    suspend fun updateNotified(): String? = store.data.first()[updateNotifiedKey]

    suspend fun setUpdateNotified(tag: String) {
        store.edit { it[updateNotifiedKey] = tag }
    }

    /** The tag of the newest release the Updates page has shown, so its dot on the avatar goes once it's been seen. */
    val updateSeen: Flow<String?> = read { it[updateSeenKey] }

    suspend fun setUpdateSeen(tag: String) {
        store.edit { it[updateSeenKey] = tag }
    }

    /** The tag of the release handed to Android to install, until the new version starts; null when none. */
    suspend fun updateInstalling(): String? = store.data.first()[updateInstallingKey]

    suspend fun setUpdateInstalling(tag: String?) {
        store.edit { if (tag == null) it.remove(updateInstallingKey) else it[updateInstallingKey] = tag }
    }

    /**
     * The settings a backup [carries], by key name, as stored. The backup passes its own rule in, so the store names
     * nothing in export.
     */
    suspend fun backupValues(carries: (String) -> Boolean): Map<String, Any> =
        store.data.first().asMap().mapKeys { it.key.name }.filterKeys(carries)

    /**
     * Restores [values] from a backup, only those this phone has never set, so restoring twice or onto a phone already
     * set up changes nothing the user chose here: [missing] picks them from [values] and the keys already set, the
     * backup's own rule passed in. Returns how many were written.
     */
    suspend fun restoreValues(values: Map<String, Any>, missing: (Map<String, Any>, Set<String>) -> Map<String, Any>): Int {
        var written = 0
        store.edit { prefs ->
            val present = prefs.asMap().keys.mapTo(HashSet()) { it.name }
            for ((name, v) in missing(values, present)) {
                when (v) {
                    is Int -> prefs[androidx.datastore.preferences.core.intPreferencesKey(name)] = v
                    is Long -> prefs[longPreferencesKey(name)] = v
                    is Float -> prefs[androidx.datastore.preferences.core.floatPreferencesKey(name)] = v
                    is Boolean -> prefs[androidx.datastore.preferences.core.booleanPreferencesKey(name)] = v
                    is String -> prefs[stringPreferencesKey(name)] = v
                    else -> continue
                }
                written++
            }
        }
        return written
    }

    companion object {
        /**
         * What [forgetDevice] removes: the paired strap (address, auth key, name), its companion association and the
         * answer to Android's association prompt, the Disconnect hold, the battery and sync time widgets show, and the
         * Zepp sign-in, which belongs to that strap's account. The next strap starts clean. The history cursors go too.
         */
        val STRAP_KEYS: Set<String> = with(SettingsKeys) {
            setOf(
                DEVICE_ADDRESS, DEVICE_AUTH_KEY, DEVICE_NAME,
                COMPANION_ASSOCIATION_ID, COMPANION_DECLINED, HELD_DISCONNECTED,
                STRAP_BATTERY, STRAP_CHARGING, STRAP_LAST_SYNC_DONE,
                ZEPP_APP_TOKEN, ZEPP_USER_ID, ZEPP_API_BASE, ZEPP_CHINA, ZEPP_LOGIN_TOKEN, ZEPP_APP_TOKEN_AT, ZEPP_APP_TTL,
            )
        }

        /** Settings kept in [store] rather than the app's own file, for tests. */
        internal fun of(store: DataStore<Preferences>) = SettingsStore(store)
    }
}

/**
 * The DataStore name of every setting [SettingsStore] keeps, shared with the backup's settings file so a
 * backup names the same keys. A key added here is kept when the strap is forgotten unless [SettingsStore.forgetDevice]
 * names it.
 */
object SettingsKeys {
    const val DEVICE_ADDRESS = "device_address"
    const val DEVICE_AUTH_KEY = "device_auth_key"
    const val DEVICE_NAME = "device_name"
    const val ZEPP_APP_TOKEN = "zepp_app_token"
    const val ZEPP_USER_ID = "zepp_user_id"
    const val ZEPP_API_BASE = "zepp_api_base"
    const val ZEPP_CHINA = "zepp_china"
    const val ZEPP_LOGIN_TOKEN = "zepp_login_token"
    const val ZEPP_APP_TOKEN_AT = "zepp_app_token_at"
    const val ZEPP_APP_TTL = "zepp_app_ttl"
    const val LIVE_HR = "live_hr"
    const val AUTO_START_WORKOUTS = "auto_start_workouts"
    const val WEAR_REMINDER = "wear_reminder"
    const val STRAP_SESSIONS = "strap_sessions"
    const val WORKOUT_LIVE_VIEW = "workout_live_view"
    const val WORKOUT_SIDES = "workout_sides"
    const val PROFILE_BIRTH_YEAR = "profile_birth_year"
    const val PROFILE_SEX = "profile_sex"
    const val PROFILE_WEIGHT_KG = "profile_weight_kg"
    const val PROFILE_HEIGHT_CM = "profile_height_cm"
    const val PROFILE_IMPERIAL = "profile_imperial"
    const val PROFILE_SLEEP_NEED_MINUTES = "profile_sleep_need_minutes"
    const val PROFILE_MAX_HR = "profile_max_hr"
    const val PROFILE_ACTIVITY_LEVEL = "profile_activity_level"
    const val PROFILE_ACTIVITY_ANSWERED_AT = "profile_activity_answered_at"
    const val PROFILE_BODY_MEASURED_AT = "profile_body_measured_at"
    const val PROFILE_BETA_BLOCKER = "profile_beta_blocker"
    const val PROFILE_PREGNANT = "profile_pregnant"
    const val PROFILE_HR_ZONES = "profile_hr_zones"
    const val GOAL_STEPS = "goal_steps"
    const val GOAL_SLEEP_MINUTES = "goal_sleep_minutes"
    const val GOAL_ACTIVE_HOURS = "goal_active_hours"
    const val GOAL_CALORIES = "goal_calories"
    const val GOAL_WORKOUTS_WEEK = "goal_workouts_week"
    const val TODAY_HIDDEN = "today_hidden"
    const val TODAY_PINNED = "today_pinned"
    const val CELEBRATE_GOAL = "celebrate_goal"
    const val WEEKLY_RECAP = "weekly_recap"
    const val GOAL_MOMENT_WEEK = "goal_moment_week"
    const val RECAP_WEEK = "recap_week"
    const val REWARDS_STARTED = "rewards_started"
    const val HISTORY_BADGES_WAITING = "history_badges_waiting"
    /** The local midnight of the Habit Age Sunday last opened, before it was kept as [HABIT_AGE_SEEN_DAY]. Read only. */
    const val HABIT_AGE_SEEN = "habit_age_seen"
    const val HABIT_AGE_SEEN_DAY = "habit_age_seen_day"
    const val FIRST_SCORE_DAY = "first_score_day"
    const val COMPANION_ASSOCIATION_ID = "companion_association_id"
    const val COMPANION_DECLINED = "companion_declined"
    const val HELD_DISCONNECTED = "held_disconnected"
    const val HEALTH_CONNECT_AUTO = "health_connect_auto"
    const val HEALTH_CONNECT_SYNCED = "health_connect_synced"
    const val HEALTH_CONNECT_AUTO_STOPPED = "health_connect_auto_stopped"
    const val BUZZ_CALLS = "buzz_calls"
    const val BUZZ_NOTIFICATIONS = "buzz_notifications"
    const val BUZZ_PATTERN = "buzz_pattern"
    const val CALL_BUZZ = "call_buzz"
    const val CALL_BUZZ_LENGTH = "call_buzz_length"
    const val QUIET_HOURS = "quiet_hours"
    const val QUIET_FROM_MINUTES = "quiet_from_minutes"
    const val QUIET_TO_MINUTES = "quiet_to_minutes"
    const val QUIET_CALLS = "quiet_calls"
    const val STRAP_BATTERY = "strap_battery"
    const val STRAP_CHARGING = "strap_charging"
    const val STRAP_LAST_SYNC_DONE = "strap_last_sync_done"
    const val BACKUP_FOLDER = "backup_folder"
    const val BACKUP_FOLDER_NAME = "backup_folder_name"
    const val BACKUP_LAST_DONE = "backup_last_done"
    const val BACKUP_FAILED = "backup_failed"
    const val BACKUP_FAILED_AT = "backup_failed_at"
    const val UPDATE_AUTO = "update_auto"
    const val UPDATE_CHECKED_AT = "update_checked_at"
    const val UPDATE_NOTIFIED_TAG = "update_notified_tag"
    const val UPDATE_INSTALLING_TAG = "update_installing_tag"
    const val UPDATE_SEEN_TAG = "update_seen_tag"

    /** Before a type, the keys of [SettingsStore.lastSync]. */
    const val LAST_SYNC_PREFIX = "last_sync_"

    /** Before a GDSP type's code, the strap's history cursors ([org.openstrap.app.device.HistorySync]). */
    const val HISTORY_CURSOR_PREFIX = LAST_SYNC_PREFIX + "gdsp_"

    /** The key each sport's [SportSettings][org.openstrap.app.data.SportSettings] is stored under, before the sport's id. */
    const val SPORT_SETTINGS_PREFIX = "sport_settings_"
}
