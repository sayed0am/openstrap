package org.openstrap.app.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The list's zones are worked out once per workout, and again only for the one whose stamp changed. */
class ZoneCacheTest {
    private val cache = ZoneCache<Long>()
    private val worked = mutableListOf<Long>()
    private suspend fun zones(stamps: Map<Long, Any?>) = cache.zones(stamps) { start -> worked += start; listOf(start.toInt(), 0, 0, 0, 0) }

    @Test fun `each workout is worked out once`() = runTest {
        val first = zones(mapOf(1L to "a", 2L to "b"))
        val again = zones(mapOf(1L to "a", 2L to "b"))
        assertEquals(listOf(1L, 2L), worked)
        assertEquals(first, again)
        assertEquals(listOf(2, 0, 0, 0, 0), again[2L])
    }

    @Test fun `only the workout whose stamp changed is worked out again`() = runTest {
        zones(mapOf(1L to "a", 2L to "b", 3L to null))
        worked.clear()
        zones(mapOf(1L to "a", 2L to "b2", 3L to null))
        assertEquals(listOf(2L), worked)
    }

    @Test fun `a new workout is added and a deleted one forgotten`() = runTest {
        zones(mapOf(1L to "a", 2L to "b"))
        worked.clear()
        val now = zones(mapOf(2L to "b", 4L to "d"))
        assertEquals(listOf(4L), worked)
        assertEquals(setOf(2L, 4L), now.keys)
        // Back again after it was forgotten, so worked out again.
        zones(mapOf(1L to "a", 2L to "b", 4L to "d"))
        assertEquals(listOf(4L, 1L), worked)
    }

    @Test fun `a cancelled call keeps what it worked out`() = runTest {
        runCatching {
            cache.zones(mapOf(1L to "a", 2L to "b")) { start -> if (start == 2L) throw kotlinx.coroutines.CancellationException() else listOf(1, 0, 0, 0, 0) }
        }
        val now = zones(mapOf(1L to "a", 2L to "b"))
        assertEquals(listOf(2L), worked)
        assertEquals(listOf(1, 0, 0, 0, 0), now[1L])
    }
}
