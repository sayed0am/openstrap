package org.openstrap.app.data

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Sleep is read through [SleepSource] (or the repository's wrappers of it), so every screen, widget and model counts
 * the same sleep after a move or a delete (sleep edit spec 3). Only the source itself, the sync that writes the
 * strap's sessions and the backup that copies them as they are may read the DAO's sleep sessions or stages.
 */
class SleepReadersTest {
    private val allowed = setOf("data/SleepSource.kt", "device/HistorySync.kt", "export/CsvTables.kt", "data/db/Daos.kt")

    /** A call of a sleep session or stage read, with what it's called on. */
    private val call = Regex("""(\w+)(\(\))?\.sleep(Sessions|Stages)\w*\(""")

    /** Receivers that are the repository's wrappers, not the DAO. */
    private val wrappers = setOf("repo")

    @Test fun `nothing reads the DAO's sleep outside the source, the sync and the backup`() {
        val roots = listOf("src/main", "src/debug", "app/src/main", "app/src/debug").map(::File).filter { it.isDirectory }
        assertTrue("found no source", roots.isNotEmpty())
        val found = mutableListOf<String>()
        roots.forEach { root ->
            val kotlin = File(root, "kotlin/org/openstrap/app")
            kotlin.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val path = f.relativeTo(kotlin).invariantSeparatorsPath
                if (path in allowed) return@forEach
                f.readLines().forEachIndexed { i, line ->
                    call.findAll(line).filter { it.groupValues[1] !in wrappers }.forEach { found += "$path:${i + 1}  ${it.value}" }
                }
            }
        }
        if (found.isNotEmpty()) fail("Read sleep through SleepSource instead:\n" + found.joinToString("\n"))
    }
}
