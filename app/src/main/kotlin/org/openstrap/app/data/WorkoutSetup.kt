package org.openstrap.app.data

/**
 * What Ready was set to the last time a sport was started (workouts spec 2.2, 4): Ready opens on it, and Start on
 * Choose's usual card starts with it, skipping Ready. Kept per sport by its tile ([Workouts.tileType]), so a run and
 * a treadmill share theirs; which side was last chosen is kept apart ([Workouts.Sides]).
 *
 * Stored as one line per sport ([encode]): "g=d:5000|a=1|p=1|k=0". Anything unreadable falls back to the default.
 */
data class SportSettings(
    /** The goal ([WorkoutTarget.code]); null for none ("Just run"). */
    val goal: String? = null,
    /** "Ask to end when I stop". */
    val askToEnd: Boolean = true,
    /** GPS where it's offered; turned off behind Ready's settings. */
    val gps: Boolean = true,
    val keepRoute: Boolean = false,
    /** Strength: how rest ends ([Strength.Rest.code]) and the sets planned, null for open. */
    val rest: String? = null,
    val sets: Int? = null,
    /** Strength: a card after each set for its reps and weight. */
    val log: Boolean = false,
    /** Intervals and games: rounds or periods ([AppWorkout.structure][org.openstrap.app.data.db.AppWorkout]); null for open. */
    val structure: String? = null,
) {
    fun encode(): String = listOfNotNull(
        goal?.let { "g=$it" },
        "a=${bit(askToEnd)}",
        "p=${bit(gps)}",
        "k=${bit(keepRoute)}",
        rest?.let { "r=$it" },
        sets?.let { "s=$it" },
        "l=${bit(log)}",
        structure?.let { "x=$it" },
    ).joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = "|"
        private fun bit(b: Boolean) = if (b) "1" else "0"

        /** [text] as written by [encode]; the defaults for anything missing or unreadable, and for null. */
        fun parse(text: String?): SportSettings {
            val fields = text.orEmpty().split(SEPARATOR).mapNotNull { part ->
                val at = part.indexOf('=')
                if (at <= 0) null else part.substring(0, at) to part.substring(at + 1)
            }.toMap()
            val d = SportSettings()
            fun flag(key: String, default: Boolean) = when (fields[key]) { "1" -> true; "0" -> false; else -> default }
            return SportSettings(
                goal = fields["g"]?.takeIf { WorkoutTarget.parse(it) != null },
                askToEnd = flag("a", d.askToEnd),
                gps = flag("p", d.gps),
                keepRoute = flag("k", d.keepRoute),
                rest = fields["r"]?.takeIf { Strength.Rest.parse(it) != null },
                sets = fields["s"]?.toIntOrNull()?.takeIf { it in Strength.SETS },
                log = flag("l", d.log),
                structure = fields["x"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/**
 * A workout about to start, as Ready sets it up or as Choose's usual card takes it from last time: the sport type
 * saved (a side already picked), and what [org.openstrap.app.device.WorkoutSession.start] records with it.
 */
data class WorkoutPlan(
    val sportType: Int,
    val askToEnd: Boolean = true,
    val target: WorkoutTarget? = null,
    val useGps: Boolean = false,
    val keepRoute: Boolean = false,
    val rest: Strength.Rest? = null,
    val setsTarget: Int? = null,
)

/** Choose's sports (workouts spec 2.1): [usual] gets the big card with Start, [recent] the small ones, by tile. */
data class ChooseSports(val usual: Int?, val recent: List<Int>)

/** Ready's choices worked out from what you did before (workouts spec 2.2). Pure, so it's tested without the screen. */
object WorkoutSetup {
    /** Your usual is the sport you started most in this many days. */
    const val USUAL_DAYS = 28

    /**
     * Choose's sports from the workouts you [started] (sport, start), newest first: your usual, the sport started most
     * in the last [USUAL_DAYS] (ties to the latest), and the next two sports you did. With no usual, up to three
     * recent ones, and a new user's Run, Walk and Strength. Sports count by tile, so a treadmill is a run.
     */
    fun choose(started: List<Pair<Int, Long>>, now: Long): ChooseSports {
        val mine = started.filter { it.first != Workouts.GENERIC_ACTIVITY }.map { Workouts.tileType(it.first) to it.second }
        val usual = mine.filter { it.second >= now - USUAL_DAYS * 86_400L }.groupBy { it.first }
            .maxWithOrNull(compareBy<Map.Entry<Int, List<Pair<Int, Long>>>> { it.value.size }.thenBy { e -> e.value.maxOf { it.second } })
            ?.key
        val recent = Workouts.recentSports(mine.map { it.first }, n = 4).filter { it != usual }
        if (usual != null) return ChooseSports(usual, recent.take(2))
        return ChooseSports(null, (recent + Workouts.DEFAULT_SPORTS.filter { it !in recent }).take(3))
    }

    /**
     * Choose's Start on your usual (workouts spec 2.1): a plan from last time's [settings] for [type], skipping Ready.
     * Its side is the one you last chose ([sides]); GPS is on where it's offered, the settings say so and the phone's
     * location can be used ([location]).
     */
    fun plan(type: Int, settings: SportSettings, sides: Map<Int, Boolean>, location: Boolean): WorkoutPlan {
        val indoor = Workouts.indoorSide(type, sides)
        val sport = Workouts.sidesOf(type)?.type(indoor == true) ?: type
        val gps = Workouts.gpsOffered(type, indoor) && settings.gps && location
        val strength = sport in Strength.SPORTS
        val goal = WorkoutTarget.parse(settings.goal)?.takeIf { it !is WorkoutTarget.Distance || gps || sport in Workouts.ON_FOOT }
        return WorkoutPlan(
            sportType = sport, askToEnd = settings.askToEnd, target = goal, useGps = gps, keepRoute = gps && settings.keepRoute,
            rest = (Strength.Rest.parse(settings.rest) ?: Strength.Rest.DEFAULT).takeIf { strength },
            setsTarget = settings.sets.takeIf { strength },
        )
    }

    /** How many goal chips Ready shows between "Just go" and More. */
    const val GOAL_CHIPS = 2

    /** The goal chips before a sport has goals of its own: a distance and a time for a distance sport, two times else. */
    fun defaultGoals(family: Workouts.Family): List<WorkoutTarget> = when (family) {
        Workouts.Family.Distance -> listOf(WorkoutTarget.Distance(5000), WorkoutTarget.Time(30 * 60))
        else -> listOf(WorkoutTarget.Time(20 * 60), WorkoutTarget.Time(30 * 60))
    }

    /**
     * The goal chips for a sport of [family]: the [GOAL_CHIPS] goals used most in its past workouts ([used], goal
     * codes newest first, null for none), ties to the latest, so the chips learn; topped up with [defaultGoals].
     */
    fun goalChips(family: Workouts.Family, used: List<String?>): List<WorkoutTarget> {
        val counted = used.mapNotNull { WorkoutTarget.parse(it) }
        val learnt = counted.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<WorkoutTarget, Int>> { it.value }.thenBy { counted.indexOf(it.key) })
            .map { it.key }.take(GOAL_CHIPS)
        return (learnt + defaultGoals(family).filter { it !in learnt }).take(GOAL_CHIPS)
    }

    /** Mind and body: the length chips after Open. */
    val LENGTHS = listOf(15, 30, 45, 60).map { WorkoutTarget.Time(it * 60L) }

    /** More's goals by kind (workouts spec 2.2): distance, time, heart rate zone, and active kcal with a profile. */
    fun moreGoals(kcal: Boolean): List<Pair<String, List<WorkoutTarget>>> = listOfNotNull(
        "Distance" to listOf(1000, 3000, 5000, 10_000, 21_100).map { WorkoutTarget.Distance(it) },
        "Time" to listOf(15, 20, 30, 45, 60, 90).map { WorkoutTarget.Time(it * 60L) },
        "Heart rate zone" to listOf(WorkoutTarget.Zones(1, 1), WorkoutTarget.Zones(1, 2), WorkoutTarget.Zones(2, 2), WorkoutTarget.Zones(3, 3)),
        ("Active kcal" to listOf(300, 500, 800).map { WorkoutTarget.Kcal(it) }).takeIf { kcal },
    )

    /**
     * Whether [goal] can be aimed for now: a distance only where the workout has one, from GPS or steps
     * ([hasDistance]); kcal only with a profile to work it out from ([kcal]).
     */
    fun offered(goal: WorkoutTarget?, hasDistance: Boolean, kcal: Boolean): Boolean = when (goal) {
        is WorkoutTarget.Distance -> hasDistance
        is WorkoutTarget.Kcal -> kcal
        else -> true
    }
}
