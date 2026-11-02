package org.openstrap.app.data

/**
 * Zone seconds for the workout lists, kept per workout so a change to one doesn't redo the rest. Each workout comes
 * with a stamp of everything its zones depend on (its row, the max HR used, its HR stream in brief). Its zones are
 * worked out again only when that stamp changes, and workouts no longer listed are forgotten. Not for concurrent use:
 * each collection of [RoomWorkoutRepository.workouts] has its own and calls it from one mapLatest, which finishes or is
 * cancelled before the next call starts. A call cancelled part way keeps what it had worked out.
 */
internal class ZoneCache<K> {
    private val cache = HashMap<K, Pair<Any?, List<Int>>>()

    suspend fun zones(stamps: Map<K, Any?>, work: suspend (K) -> List<Int>): Map<K, List<Int>> {
        cache.keys.retainAll(stamps.keys)
        for ((key, stamp) in stamps) {
            val had = cache[key]
            if (had == null || had.first != stamp) cache[key] = stamp to work(key)
        }
        return cache.mapValues { it.value.second }
    }
}
