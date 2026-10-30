package org.openstrap.app.ui.workout

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import org.openstrap.app.data.Route
import org.openstrap.app.data.WorkoutRecorder
import org.openstrap.app.device.ConnectionState
import org.openstrap.app.device.WorkoutSession
import org.openstrap.app.device.WorkoutState
import org.openstrap.app.data.WorkoutTarget
import org.openstrap.app.data.Workouts
import org.openstrap.app.ui.glow.km
import org.openstrap.app.ui.glow.workoutClock
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** What a [LiveGlance] is lit in: the zone's colour, work red, rest green, yoga purple, paused grey, asking amber. */
enum class GlanceTint { Zone, Work, Rest, Mind, Paused, Asking }

/** The next thing to do from the Today hero or the floating bar, or the second one beside it. */
enum class GlanceAction { Pause, Resume, KeepGoing, End, Open, SetDone, StartSet, AddRest, Skip }

/**
 * Where an interval workout is ([GlanceInput.interval]): its [phase], round [round] of [rounds] (from 1), the seconds
 * [left] in a timed phase or the metres [metersLeft] in a run rep, the rest after this work ([nextRest]), and the
 * heart rate's [drop] since the work ended. [everyMinute] work ends on Done.
 */
data class IntervalMoment(
    val phase: org.openstrap.app.data.Phase,
    val round: Int,
    val rounds: Int,
    val left: Long? = null,
    val metersLeft: Float? = null,
    val nextRest: Long? = null,
    val drop: Int? = null,
    val everyMinute: Boolean = false,
    val reps: Boolean = false,
    /** A game's periods: each one's length, for the time in it. */
    val periodLength: Long? = null,
)

/**
 * Where a strength workout is ([GlanceInput.strength]): in set [setNumber] (or before it), its [setSeconds], the rest
 * [restLeft] of [restTotal] and the level that ends it sooner ([restBelow]), the [drop] in heart rate since the set
 * ended, and what's next ([nextLoad], "10 × 60 kg").
 */
data class StrengthMoment(
    val inSet: Boolean,
    val setNumber: Int,
    val setsTarget: Int?,
    val done: Int,
    val setSeconds: Long = 0,
    val restLeft: Long? = null,
    val restTotal: Long? = null,
    val restBelow: Int? = null,
    val drop: Int? = null,
    val exercise: String? = null,
    val nextLoad: String? = null,
)

/** The bar under the figure: how far toward the goal ([drain] false, it fills), or rest running out (it empties). */
data class GlanceProgress(val fraction: Float, val drain: Boolean = false)

/**
 * A workout at a glance while its live screen is tucked away (workouts spec 2.10): the one source for the Today hero
 * and the floating bar, so the two never disagree.
 *
 * [figure] is the one number that matters now (km, minutes, bpm) with its [unit]; [line1] and [line2] the bar's two
 * lines; [progress] the goal's bar; [tint] and [zone] its colour; [action] the next thing you'd do and [second] the one
 * beside it. The hero also shows [goal] and [toGo] under its bar and three [figures] (value, label).
 */
data class LiveGlance(
    val figure: String?,
    val unit: String?,
    val line1: String,
    val line2: String?,
    val progress: GlanceProgress?,
    val tint: GlanceTint,
    val zone: Int?,
    val action: GlanceAction,
    val actionLabel: String,
    val second: GlanceAction? = null,
    val secondLabel: String? = null,
    /** The action's name where the bar has less room ("Set 4" for "Start set 4"). */
    val shortLabel: String? = null,
    /** The hero's Next box ("Set 4, bench press 10 × 60 kg"). */
    val next: String? = null,
    val goal: String? = null,
    val toGo: String? = null,
    val figures: List<Pair<String?, String>> = emptyList(),
    /** The total time so far, "24:51". */
    val clock: String = "",
    /** A GPS workout's last whole kilometre for the hero's strip: "Km 4 in 5:55" and "Avg 146 bpm. Fastest so far was km 3." */
    val split: Pair<String, String?>? = null,
    /** The bar shows the live bpm in dots beside its action (only where the action is Pause). */
    val bpm: Int? = null,
)

/**
 * The workout as it is now, for [LiveGlance.of]: time and pauses, the strap, the distance and pace for a GPS workout,
 * the goal, and the stop question while it's asked ([stoppedSince], when the wearer last moved, epoch seconds).
 */
data class GlanceInput(
    val sportType: Int,
    val now: Long,
    val activeSeconds: Long,
    val pausedAt: Long?,
    val bpm: Int?,
    val connected: Boolean,
    val maxHr: Int,
    val gps: Boolean,
    val distanceMeters: Float?,
    val distanceFromSteps: Boolean = false,
    /** Seconds per kilometre now and over the whole workout; null with no pace yet. */
    val pace: Double? = null,
    val averagePace: Double? = null,
    val target: WorkoutTarget? = null,
    val activeKcal: Float? = null,
    val stoppedSince: Long? = null,
    val inCar: Boolean = false,
    /** The last whole kilometre, its average heart rate, and the fastest so far by index. */
    val lastSplit: Route.Split? = null,
    val lastSplitBpm: Int? = null,
    val fastestSplit: Int? = null,
    val strength: StrengthMoment? = null,
    val interval: IntervalMoment? = null,
    /** A game's effort so far, 1 to 5 ([org.openstrap.app.data.Games.effort]). */
    val effort: Int? = null,
    /** Average heart rate so far, for an indoor workout's figures. */
    val averageBpm: Int? = null,
)

/**
 * The glance of the workout [session] records, once a second and whenever what it shows changes; null while nothing
 * records. Worked out off the screen, so the Today hero and the floating bar each read it without the app around them
 * recomposing every second.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun liveGlance(
    session: WorkoutSession,
    liveHeartRate: Flow<Int?>,
    connection: Flow<ConnectionState>,
): Flow<LiveGlance?> {
    val ticks = flow {
        while (true) {
            emit(System.currentTimeMillis() / 1000)
            delay(1000 - System.currentTimeMillis() % 1000)
        }
    }
    val strap = combine(liveHeartRate, connection) { bpm, c -> bpm to (c is ConnectionState.Connected) }
    val figures = combine(session.route, session.kcal, session.stats) { route, kcal, stats -> Triple(route, kcal, stats) }
    // Nothing ticks while nothing records.
    val recording = session.state.map { it is WorkoutState.Recording }.distinctUntilChanged()
    return recording.flatMapLatest { on -> if (!on) flowOf(null) else glanceTicks(session, strap, figures, ticks) }
}

/** [liveGlance] while a workout records. */
private fun glanceTicks(
    session: WorkoutSession,
    strap: Flow<Pair<Int?, Boolean>>,
    figures: Flow<Triple<List<Route.Point>, WorkoutRecorder.Kcal?, WorkoutRecorder.Stats?>>,
    ticks: Flow<Long>,
): Flow<LiveGlance?> {
    // The splits are worked out again only when the route grows, not every second.
    var splitsOf = -1 to emptyList<Route.Split>()
    return combine(session.state, strap, figures, ticks) { st, (bpm, connected), (route, kcal, stats), now ->
        val r = st as? WorkoutState.Recording ?: return@combine null
        val w = r.workout
        val active = WorkoutRecorder.activeSeconds(w, now)
        val distance = w.distanceMeters
        if (w.useGps && splitsOf.first != route.size) splitsOf = route.size to Route.splits(route, w.pauseRanges)
        val whole = splitsOf.second.filter { it.meters >= 1000 }
        val last = whole.lastOrNull()
        val lastBpm = last?.let { s ->
            val from = whole.getOrNull(whole.size - 2)?.endT ?: route.firstOrNull()?.t ?: s.endT
            WorkoutCopy.averageOver(session.history.value, from, s.endT)
        }
        Glance.of(
            GlanceInput(
                w.sportType, now, active, w.pausedAt, bpm, connected, r.maxHeartRate, w.useGps, distance, w.distanceFromSteps,
                pace = if (w.useGps) Route.currentPace(route) else null,
                averagePace = distance?.takeIf { it >= 50 }?.let { active / (it / 1000.0) },
                target = WorkoutTarget.parse(w.target), activeKcal = kcal?.active,
                stoppedSince = r.endPrompt, inCar = r.inCar, averageBpm = stats?.average,
                lastSplit = last, lastSplitBpm = lastBpm, fastestSplit = whole.minByOrNull { it.pace }?.index,
                strength = if (r.strength) strengthMoment(r, now, bpm, session.history.value) else null,
                interval = intervalMoment(r, now, bpm, WorkoutCopy.readings(session.history.value, session.recent.value), route),
                effort = org.openstrap.app.data.Games.effort(session.zoneSeconds.value),
            ),
        )
    }.distinctUntilChanged()
}


/** Where strength workout [r] is at [now], for its glance; [history] is its live readings, for the drop since the set. */
internal fun strengthMoment(r: WorkoutState.Recording, now: Long, bpm: Int?, history: List<Pair<Long, Int>>): StrengthMoment {
    val last = r.sets.lastOrNull()
    val done = r.sets.count { it.end != null }
    val ended = last?.end
    val atEnd = ended?.let { Workouts.heartRateAt(history, it) ?: org.openstrap.app.data.Strength.peak(history, it - 5, it) }
    val load = r.exercise?.let { e ->
        r.sets.lastOrNull { it.reps != null && it.exercise.equals(e, ignoreCase = true) }
            ?.let { s -> "${s.reps} × ${org.openstrap.app.data.Strength.kgText(s.weightKg ?: 0f)} kg" }
    }
    return StrengthMoment(
        inSet = r.inSet, setNumber = if (r.inSet) r.sets.size else r.sets.size + 1, setsTarget = r.workout.setsTarget, done = done,
        setSeconds = if (r.inSet && last != null) (now - last.start).coerceAtLeast(0) else 0,
        restLeft = r.restUntil?.let { it - now }, restTotal = r.restUntil?.let { u -> ended?.let { u - it } },
        restBelow = org.openstrap.app.data.Strength.Rest.parse(r.workout.rest)?.belowBpm,
        drop = if (atEnd != null && bpm != null && r.resting) atEnd - bpm else null,
        exercise = r.exercise, nextLoad = load,
    )
}

/**
 * Where interval workout [r] is at [now], for its glance and live screen; null without rounds. [readings] are its
 * live readings (time, bpm), for the drop since the work ended; [route] its GPS fixes, for a run rep's metres to go.
 */
internal fun intervalMoment(r: WorkoutState.Recording, now: Long, bpm: Int?, readings: List<Pair<Long, Int>>, route: List<Route.Point>): IntervalMoment? {
    val s = r.structure ?: return null
    val p = r.phase(now) ?: return null
    val active = WorkoutRecorder.activeSeconds(r.workout, now)
    val rest = when (s) {
        is org.openstrap.app.data.Structure.Timed -> s.rest
        is org.openstrap.app.data.Structure.Reps -> s.rest
        is org.openstrap.app.data.Structure.EveryMinute -> null
        is org.openstrap.app.data.Structure.Periods -> s.rest
    }
    return when (p) {
        is org.openstrap.app.data.Phase.Work -> {
            val meters = (s as? org.openstrap.app.data.Structure.Reps)?.meters?.let { goal ->
                val ran = route.filter { it.t >= p.start }.takeIf { it.size > 1 }?.let { Route.distance(it, r.workout.pauseRanges).toFloat() } ?: 0f
                (goal - ran).coerceAtLeast(0f)
            }
            val minuteLeft = (s as? org.openstrap.app.data.Structure.EveryMinute)?.let {
                WorkoutRecorder.activeSeconds(r.workout, p.start) + it.period - active
            }
            IntervalMoment(
                p, p.round + 1, s.rounds, left = (p.endsAt?.let { it - active } ?: minuteLeft)?.coerceAtLeast(0), metersLeft = meters,
                nextRest = rest.takeIf { p.round + 1 < s.rounds }, everyMinute = s is org.openstrap.app.data.Structure.EveryMinute,
                periodLength = (s as? org.openstrap.app.data.Structure.Periods)?.length,
                reps = s is org.openstrap.app.data.Structure.Reps,
            )
        }
        is org.openstrap.app.data.Phase.Rest -> {
            val atEnd = Workouts.heartRateAt(readings, p.start)
            IntervalMoment(
                p, p.round + 2, s.rounds, left = (p.endsAt - active).coerceAtLeast(0),
                drop = if (atEnd != null && bpm != null) atEnd - bpm else null, reps = s is org.openstrap.app.data.Structure.Reps,
            )
        }
        org.openstrap.app.data.Phase.WarmUp -> IntervalMoment(p, 1, s.rounds)
        org.openstrap.app.data.Phase.Done -> IntervalMoment(p, s.rounds, s.rounds)
    }
}

object Glance {
    /** [i] worked out (workouts spec 2.10's table): asking first, then paused, then the sport's own moment. */
    fun of(i: GlanceInput): LiveGlance {
        val sport = Workouts.sportName(i.sportType)
        val bpm = i.bpm.takeIf { i.connected && i.pausedAt == null }
        val zone = WorkoutRecorder.liveZone(bpm, i.maxHr)
        val distance = Workouts.family(i.sportType) == Workouts.Family.Distance && i.gps
        val clock = workoutClock(i.activeSeconds)
        val figure = if (distance) i.distanceMeters?.let { kmFigure(it) } ?: "0.00" else bpm?.toString()
        val unit = if (distance) "km" else if (figure != null) "bpm" else null
        val progress = progress(i)
        val (goal, toGo) = goalLine(i)
        val figures = if (distance) listOf(
            paceClock(i.pace) to "Pace /km",
            bpm?.toString() to (zone?.let { "bpm, zone ${it + 1}" } ?: "bpm"),
            i.activeKcal?.toInt()?.toString() to "Active kcal",
        ) else listOfNotNull(
            i.distanceMeters?.takeIf { it >= 10 }?.let { kmFigure(it) to if (i.distanceFromSteps) "km from steps" else "km" },
            i.averageBpm?.toString() to "Avg bpm",
            i.activeKcal?.toInt()?.toString() to "Active kcal",
        )
        val base = LiveGlance(
            figure, unit, "", null, progress, GlanceTint.Zone, zone, GlanceAction.Pause, "Pause",
            goal = goal, toGo = toGo, figures = figures, split = splitLine(i), clock = clock,
        )
        i.stoppedSince?.let { since ->
            val minutes = ((i.now - since) / 60).coerceAtLeast(1)
            return base.copy(
                line1 = "Still going?", line2 = if (i.inCar) "Looks like you're in a car" else "No movement for $minutes min",
                tint = GlanceTint.Asking, action = GlanceAction.KeepGoing, actionLabel = "Keep going", second = GlanceAction.End, secondLabel = "End",
            )
        }
        i.pausedAt?.let { at ->
            val done = if (distance && i.distanceMeters != null) "$sport, ${kmWithUnit(i.distanceMeters)} so far" else "$sport, $clock so far"
            return base.copy(
                line1 = "Paused for ${workoutClock((i.now - at).coerceAtLeast(0))}", line2 = done,
                tint = GlanceTint.Paused, zone = null, action = GlanceAction.Resume, actionLabel = "Resume",
            )
        }
        val state = when {
            !i.connected -> "Strap reconnecting"
            bpm == null -> "Waiting for heart rate"
            else -> null
        }
        i.strength?.let { s -> return strength(base, s, bpm, i) }
        i.interval?.let { m -> return interval(base, m, bpm, i) }
        if (Workouts.family(i.sportType) == Workouts.Family.MindBody) return mind(base, i)
        if (Workouts.family(i.sportType) == Workouts.Family.Games) return game(base, null, bpm, i)
        if (distance) {
            val pace = paceClock(i.pace)
            val first = listOfNotNull(kmWithUnit(i.distanceMeters ?: 0f), pace?.let { "$it /km" }).joinToString(", ")
            val second = listOfNotNull(clock, toGoShort(i) ?: state ?: zone?.let { "zone ${it + 1}" }).joinToString(", ")
            return base.copy(line1 = first, line2 = second, bpm = bpm, actionLabel = "Pause")
        }
        val zoneLine = zone?.let { WorkoutCopy.zoneTitle(it).let { (a, b) -> "$a $b" } }
        return base.copy(
            line1 = "$sport, $clock", line2 = listOfNotNull(state ?: zoneLine, toGoShort(i)).joinToString(", ").ifEmpty { null },
            bpm = bpm,
        )
    }

    /**
     * Strength's moments (spec 2.10's table): in a set, the set's clock with Set done; resting, the rest left draining
     * in green with Start set N and +30 s, the drop in heart rate and what's next; between sets, Start set N.
     */
    private fun strength(base: LiveGlance, s: StrengthMoment, bpm: Int?, i: GlanceInput): LiveGlance {
        val of = s.setsTarget?.let { " of $it" } ?: ""
        val figures = listOf(
            bpm?.toString() to (base.zone?.let { "bpm, zone ${it + 1}" } ?: "bpm"),
            s.done.toString() to if (s.done == 1) "Set done" else "Sets done",
            i.activeKcal?.toInt()?.toString() to "Active kcal",
        )
        val next = listOfNotNull("Set ${s.setNumber}", s.exercise?.lowercase()?.let { e -> listOfNotNull(e, s.nextLoad).joinToString(" ") })
            .joinToString(", ")
        val start = base.copy(
            action = GlanceAction.StartSet, actionLabel = "Start set ${s.setNumber}", shortLabel = "Set ${s.setNumber}",
            figures = figures, progress = null, goal = null, toGo = null,
        )
        return when {
            s.inSet -> base.copy(
                figure = workoutClock(s.setSeconds), unit = "in this set",
                line1 = "Set ${s.setNumber}$of, ${workoutClock(s.setSeconds)}",
                line2 = listOfNotNull(s.exercise, bpm?.let { "$it bpm" }).joinToString(", ").ifEmpty { null },
                action = GlanceAction.SetDone, actionLabel = "Set done", figures = figures, progress = null, goal = null, toGo = null,
            )
            s.restLeft != null -> {
                val left = workoutClock(s.restLeft.coerceAtLeast(0))
                val heart = bpm?.let { b -> "$b bpm" + (s.drop?.takeIf { it > 0 }?.let { ", down $it" } ?: "") }
                start.copy(
                    figure = left, unit = "left", tint = GlanceTint.Rest,
                    line1 = "$left rest left", line2 = listOfNotNull(heart, "set ${s.setNumber} next").joinToString(", "),
                    progress = s.restTotal?.takeIf { it > 0 }?.let { GlanceProgress((s.restLeft.toFloat() / it).coerceIn(0f, 1f), drain = true) },
                    goal = listOfNotNull(heart?.let { "$it." }, s.restBelow?.let { "Ends sooner once you stay under $it." }).joinToString(" ").ifEmpty { null },
                    second = GlanceAction.AddRest, secondLabel = "+30 s", next = next,
                )
            }
            else -> start.copy(
                line1 = if (s.done == 0) "Ready for set 1" else "Rest over, set ${s.setNumber}$of next",
                line2 = listOfNotNull(s.exercise, bpm?.let { "$it bpm" }).joinToString(", ").ifEmpty { null },
                next = next,
            )
        }
    }

    /**
     * Mind and body (spec 2.10's table): the minutes of the length in violet, one line ("Yoga, 23:40 of 45 min"), a
     * quieter Pause and no heart rate in dots.
     */
    private fun mind(base: LiveGlance, i: GlanceInput): LiveGlance {
        val of = (i.target as? WorkoutTarget.Time)?.let { " of ${it.seconds / 60} min" } ?: ""
        return base.copy(
            figure = "${i.activeSeconds / 60}", unit = "min$of", line1 = "${Workouts.sportName(i.sportType)}, ${base.clock}$of", line2 = null,
            tint = GlanceTint.Mind, zone = null, bpm = null, goal = null, toGo = null, figures = emptyList(),
        )
    }

    /**
     * Games (spec 2.10's table): the period and the time in it, the zone and the effort, and Half time; in the break, its
     * time left and Start the next period. Free play has no periods: the sport and its time.
     */
    private fun game(base: LiveGlance, m: IntervalMoment?, bpm: Int?, i: GlanceInput): LiveGlance {
        val zone = base.zone?.let { "Zone ${it + 1}" }
        val effort = i.effort?.let { "effort $it" }
        val figures = listOf(
            bpm?.toString() to (base.zone?.let { "bpm, zone ${it + 1}" } ?: "bpm"),
            i.effort?.let { "$it of 5" } to "Effort",
            i.activeKcal?.toInt()?.toString() to "Active kcal",
        )
        val heart = listOfNotNull(zone, effort).joinToString(", ").ifEmpty { null }
        val game = base.copy(figures = figures, goal = null, toGo = null, progress = null, bpm = bpm, line2 = heart)
        if (m == null || m.periodLength == null) return game.copy(line1 = "${Workouts.sportName(i.sportType)}, ${base.clock}")
        return when (m.phase) {
            is org.openstrap.app.data.Phase.Work -> {
                val inPeriod = workoutClock((m.periodLength - (m.left ?: 0)).coerceAtLeast(0))
                game.copy(
                    figure = inPeriod, unit = StartCopy.periodName(m.round - 1, m.rounds).lowercase(),
                    line1 = "${StartCopy.periodName(m.round - 1, m.rounds)}, $inPeriod",
                    action = GlanceAction.Skip, actionLabel = StartCopy.breakName(m.rounds),
                )
            }
            is org.openstrap.app.data.Phase.Rest -> {
                val left = workoutClock(m.left ?: 0)
                game.copy(
                    figure = left, unit = "left", tint = GlanceTint.Rest,
                    line1 = "${StartCopy.breakName(m.rounds)}, $left left",
                    action = GlanceAction.Skip, actionLabel = "Start ${StartCopy.periodName(m.round - 1, m.rounds).lowercase()}",
                    shortLabel = "Start",
                )
            }
            else -> game.copy(line1 = "Full time, ${base.clock}")
        }
    }

    /**
     * Intervals' moments (spec 2.10's table): work in work red with the seconds left (or the metres of a run rep) and
     * Pause; rest in rest green with Skip rest and +10 s; warming up with Start rounds; all rounds done.
     */
    private fun interval(base: LiveGlance, m: IntervalMoment, bpm: Int?, i: GlanceInput): LiveGlance {
        if (m.periodLength != null) return game(base, m, bpm, i)
        val word = if (m.reps) "Rep" else "Round"
        val start = base.copy(goal = null, toGo = null, progress = null)
        return when (m.phase) {
            is org.openstrap.app.data.Phase.Work -> {
                val figure = m.metersLeft?.let { "${it.toInt()}" } ?: m.left?.toString()
                start.copy(
                    figure = figure, unit = if (m.metersLeft != null) "m to go" else if (m.everyMinute) "s left in the minute" else "s left",
                    tint = GlanceTint.Work, line1 = "Work, ${word.lowercase()} ${m.round} of ${m.rounds}",
                    line2 = listOfNotNull(m.nextRest?.let { "Then ${StartCopy.restText(it)} rest" } ?: "Last one".takeIf { m.round == m.rounds }, bpm?.let { "$it bpm" })
                        .joinToString(", ").ifEmpty { null },
                    bpm = bpm,
                    action = if (m.everyMinute) GlanceAction.Skip else GlanceAction.Pause, actionLabel = if (m.everyMinute) "Done" else "Pause",
                )
            }
            is org.openstrap.app.data.Phase.Rest -> start.copy(
                figure = m.left?.toString(), unit = "s rest", tint = GlanceTint.Rest,
                line1 = "Rest, ${word.lowercase()} ${m.round} next",
                line2 = bpm?.let { b -> "$b bpm" + (m.drop?.takeIf { it > 0 }?.let { ", down $it" } ?: "") },
                action = GlanceAction.Skip, actionLabel = "Skip rest", second = GlanceAction.AddRest, secondLabel = "+10 s",
            )
            org.openstrap.app.data.Phase.WarmUp -> start.copy(
                line1 = "Warming up, ${base.clock}", line2 = bpm?.let { "$it bpm" }, action = GlanceAction.Skip, actionLabel = "Start rounds", bpm = null,
            )
            org.openstrap.app.data.Phase.Done -> start.copy(
                line1 = "All ${m.rounds} ${word.lowercase()}s done", line2 = listOfNotNull(base.clock, bpm?.let { "$it bpm" }).joinToString(", "), bpm = bpm,
            )
        }
    }

    /** The hero's split strip: "Km 4 in 5:55", then its average and which was fastest; null before a whole kilometre. */
    fun splitLine(i: GlanceInput): Pair<String, String?>? {
        val s = i.lastSplit ?: return null
        val fastest = i.fastestSplit?.takeIf { it != s.index }?.let { "Fastest so far was km ${it + 1}." }
            ?: "Your fastest so far.".takeIf { s.index > 0 }
        val avg = i.lastSplitBpm?.let { "Avg $it bpm." }
        return "Km ${s.index + 1} in ${workoutClock(s.seconds)}" to listOfNotNull(avg, fastest).joinToString(" ").ifEmpty { null }
    }

    /** The goal's bar: how far toward a distance, time or kcal goal. A zone goal has none. */
    fun progress(i: GlanceInput): GlanceProgress? = when (val t = i.target) {
        is WorkoutTarget.Distance -> GlanceProgress(((i.distanceMeters ?: 0f) / t.meters).coerceIn(0f, 1f))
        is WorkoutTarget.Time -> GlanceProgress((i.activeSeconds.toFloat() / t.seconds).coerceIn(0f, 1f))
        is WorkoutTarget.Kcal -> GlanceProgress(((i.activeKcal ?: 0f) / t.kcal).coerceIn(0f, 1f))
        is WorkoutTarget.Zones, null -> null
    }

    /** The hero's line under its bar: "Goal 5 km" and "About 4 min to go", or the goal met. */
    fun goalLine(i: GlanceInput): Pair<String?, String?> {
        val t = i.target ?: return null to null
        val label = when (t) {
            is WorkoutTarget.Distance -> t.label
            is WorkoutTarget.Time -> t.label
            is WorkoutTarget.Kcal -> t.label
            is WorkoutTarget.Zones -> return "Target ${t.label.lowercase().replaceFirstChar { it.uppercase() }}" to null
        }
        if ((progress(i)?.fraction ?: 0f) >= 1f) return "Goal $label" to "Reached"
        val left = when (t) {
            is WorkoutTarget.Distance -> {
                val pace = i.pace ?: i.averagePace
                val meters = t.meters - (i.distanceMeters ?: 0f)
                pace?.let { minutesToGo(meters / 1000.0 * it) }?.let { "About $it" }
            }
            is WorkoutTarget.Time -> minutesToGo((t.seconds - i.activeSeconds).toDouble())
            is WorkoutTarget.Kcal -> "${(t.kcal - (i.activeKcal ?: 0f)).toInt().coerceAtLeast(0)} kcal to go"
            else -> null
        }
        return "Goal $label" to left
    }

    /** The bar's short "to go": "0.68 km to go", "5 min to go", "120 kcal to go"; null without a goal or once met. */
    private fun toGoShort(i: GlanceInput): String? {
        if ((progress(i)?.fraction ?: 0f) >= 1f) return null
        return when (val t = i.target) {
            is WorkoutTarget.Distance -> "${kmWithUnit(t.meters - (i.distanceMeters ?: 0f))} to go"
            is WorkoutTarget.Time -> minutesToGo((t.seconds - i.activeSeconds).toDouble())
            is WorkoutTarget.Kcal -> "${(t.kcal - (i.activeKcal ?: 0f)).toInt().coerceAtLeast(0)} kcal to go"
            else -> null
        }
    }

    /** "4 min to go", rounded up; "Under a minute to go". */
    private fun minutesToGo(seconds: Double): String =
        if (seconds < 60) "Under a minute to go" else "${ceil(seconds / 60).toInt()} min to go"

    /** "4.32" kilometres, as the figure. */
    fun kmFigure(meters: Float): String = String.format(Locale.ROOT, "%.2f", meters / 1000f)

    /** "4.32 km", or "680 m" under one. */
    fun kmWithUnit(meters: Float): String = km(meters.coerceAtLeast(0f)) + if (meters >= 1000) " km" else ""

    /** A pace as a clock, "5:42"; null with none, or one too slow to be moving. */
    fun paceClock(secondsPerKm: Double?): String? {
        if (secondsPerKm == null || secondsPerKm <= 0 || secondsPerKm > 3600) return null
        val s = Math.round(secondsPerKm)
        return "%d:%02d".format(s / 60, s % 60)
    }
}
