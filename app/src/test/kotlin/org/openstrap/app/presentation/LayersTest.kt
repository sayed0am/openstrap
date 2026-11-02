package org.openstrap.app.presentation

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openstrap.app.testing.SourceTree
import java.io.File

/**
 * The service, the notifications and the updater read their words and colours from presentation, never from the
 * screens: device and update code imports nothing from ui or Compose, and presentation nothing from either of those.
 */
class LayersTest {
    private fun offenders(dir: String, banned: List<String>): List<String> =
        File(SourceTree.kotlin, dir).walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().filter { line -> banned.any { line.contains(it) } }.map { "${f.name}: ${it.trim()}" }
        }.toList()

    private val ui = listOf("org.openstrap.app.ui.", "androidx.compose")

    @Test
    fun deviceAndUpdateDontReachIntoTheScreens() {
        assertEquals(emptyList<String>(), offenders("device", ui))
        assertEquals(emptyList<String>(), offenders("update", ui))
    }

    @Test
    fun presentationStandsOnItsOwn() {
        assertEquals(emptyList<String>(), offenders("presentation", ui + "org.openstrap.app.device."))
    }
}
