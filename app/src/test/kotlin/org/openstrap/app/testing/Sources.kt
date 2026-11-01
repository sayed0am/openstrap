package org.openstrap.app.testing

import org.junit.Assert.fail
import java.io.File

/**
 * Where the app module's sources are, for tests that read them. The `openstrap.srcRoot` system property names
 * `app/src/main` when Gradle sets it. Without it, the working directory decides: the module when Gradle runs the
 * tests, the repo root in some IDE runs.
 */
object SourceTree {
    val root: File by lazy {
        System.getProperty("openstrap.srcRoot")?.let(::File)?.absoluteFile
            ?: listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }.absoluteFile
    }

    /** The app's Kotlin package. */
    val kotlin: File get() = File(root, "kotlin/org/openstrap/app")

    /** The app module, the folder that holds `src` and `schemas`. */
    val module: File get() = root.parentFile.parentFile

    /** Room's exported schemas, one `<version>.json` each. */
    val schemas: File get() = File(module, "schemas/org.openstrap.app.data.db.OpenStrapDatabase")
}

/**
 * The punctuation copy never uses (spec 7.1 rule 6): interpunct, semicolon, em dash and en dash. An en dash between
 * two digits is a range, like 22:10–06:40 or 5–10, and stays allowed.
 */
private val BANNED = setOf('·', ';', '—', '–')

private fun String.bannedAt(i: Int): Boolean =
    this[i] in BANNED && !(this[i] == '–' && getOrNull(i - 1)?.isDigit() == true && getOrNull(i + 1)?.isDigit() == true)

/** Fails with every line of [text] that uses banned punctuation. */
fun assertCleanCopy(text: String) {
    val bad = text.lines().filter { line -> line.indices.any { line.bannedAt(it) } }
    if (bad.isNotEmpty()) fail("copy uses ·, ;, — or –:\n" + bad.joinToString("\n"))
}

/** [assertCleanCopy] for each of [texts]. */
fun assertCleanCopy(texts: Iterable<String>) = assertCleanCopy(texts.joinToString("\n"))
