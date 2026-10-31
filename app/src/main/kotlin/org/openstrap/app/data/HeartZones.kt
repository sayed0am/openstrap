package org.openstrap.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.ceil

/**
 * Your heart rate zones, one set for every sport (the Heart rate zones page): where each of the five starts, as a
 * percent of max heart rate ([starts], ascending), so they follow max HR as it's learned or set. Zone 5 runs on up.
 *
 * Everything that reads a zone ([Workouts.zoneOf], [WorkoutRecorder.liveZone] and [WorkoutRecorder.zonePosition], so
 * zone goals, live zones, zone time and the route's colours) reads [current], which the app keeps to what's stored
 * ([org.openstrap.app.data.settings.SettingsStore.heartZones]). Stored as [encode]; [STANDARD] until moved.
 */
data class HeartZones(val starts: List<Float>) {
    init {
        require(starts.size == Workouts.ZONES) { "five zones" }
    }

    /** Zone 0..4 for [bpm] at [maxHr]; null under zone 1's start. */
    fun zoneOf(bpm: Int, maxHr: Int): Int? {
        val pct = bpm * 100f / maxHr
        return starts.indexOfLast { pct >= it - EPS }.takeIf { it >= 0 }
    }

    /** Where [bpm] sits along the five zones, 0..1: zone 1's start at the left, each zone a fifth, max at the right. */
    fun position(bpm: Int, maxHr: Int): Float {
        val z = zoneOf(bpm, maxHr) ?: return 0f
        val pct = bpm * 100f / maxHr
        val to = starts.getOrNull(z + 1) ?: 100f
        val within = if (to > starts[z]) ((pct - starts[z]) / (to - starts[z])).coerceIn(0f, 1f) else 1f
        return ((z + within) / Workouts.ZONES).coerceIn(0f, 1f)
    }

    /** The lowest bpm in [zone] at [maxHr]. */
    fun startBpm(zone: Int, maxHr: Int): Int = ceil(starts[zone] * maxHr / 100f - EPS).toInt()

    /** The highest bpm in [zone] at [maxHr]; null for zone 5, which runs on up. */
    fun endBpm(zone: Int, maxHr: Int): Int? = if (zone == LAST) null else startBpm(zone + 1, maxHr) - 1

    /**
     * [zone] starting at [bpm] instead, at [maxHr]: kept [MIN_WIDTH_BPM] clear of the zones either side, zone 1 no
     * lower than [MIN_START_PCT] of max and zone 5 under max. The zone below ends where it starts.
     */
    fun withStart(zone: Int, bpm: Int, maxHr: Int): HeartZones {
        val low = if (zone == 0) ceil(MIN_START_PCT * maxHr / 100f).toInt() else startBpm(zone - 1, maxHr) + MIN_WIDTH_BPM
        val high = if (zone == LAST) maxHr - 1 else startBpm(zone + 1, maxHr) - MIN_WIDTH_BPM
        if (low > high) return this
        val b = bpm.coerceIn(low, high)
        return HeartZones(starts.toMutableList().also { it[zone] = b * 100f / maxHr })
    }

    /** [zone] ending at [bpm] instead: the next zone starts one higher. Zone 5 has no end. */
    fun withEnd(zone: Int, bpm: Int, maxHr: Int): HeartZones = if (zone == LAST) this else withStart(zone + 1, bpm + 1, maxHr)

    val standard: Boolean get() = this == STANDARD

    /** "50.0,60.0,70.0,80.0,90.0". */
    fun encode(): String = starts.joinToString(",")

    companion object {
        /** 50, 60, 70, 80 and 90 % of max, as the app has always used. */
        val STANDARD = HeartZones(listOf(50f, 60f, 70f, 80f, 90f))

        /** A zone is at least this many bpm wide. */
        const val MIN_WIDTH_BPM = 2

        /** Zone 1 starts no lower than this percent of max. */
        const val MIN_START_PCT = 30f

        private const val LAST = Workouts.ZONES - 1

        /** Float slack, so a start set from a whole bpm reads back as that bpm. */
        private const val EPS = 1e-3f

        /** [text] as written by [encode]: five rising percents of max under 100; null for anything else. */
        fun parse(text: String?): HeartZones? {
            val v = text?.split(',')?.map { it.trim().toFloatOrNull() ?: return null } ?: return null
            if (v.size != Workouts.ZONES || v.first() < MIN_START_PCT || v.last() >= 100f) return null
            if (v.zipWithNext().any { (a, b) -> b <= a }) return null
            return HeartZones(v)
        }

        private val live = MutableStateFlow(STANDARD)

        /** The zones in use now, everywhere. */
        val current: StateFlow<HeartZones> = live

        /** Makes [zones] the ones in use ([current]). */
        fun use(zones: HeartZones) {
            live.value = zones
        }
    }
}

/** Max heart rate as the Heart rate zones page shows it: the [bpm], where it comes from, and the day it was [learnedOn]. */
data class MaxHeartRate(val bpm: Int, val source: Source, val learnedOn: java.time.LocalDate? = null) {
    enum class Source {
        /** You set it yourself ([org.openstrap.app.data.settings.UserProfile.maxHeartRate]). */
        Set,
        /** Your workouts went higher than the estimate, on two days that agree ([Recovery.observedPeak]). */
        Learned,
        /** Tanaka's estimate from your age. */
        Age,
        /** The strap's own value, from the Zepp profile. */
        Strap,
        /** Nothing to go on: 190. */
        Guess,
    }
}
