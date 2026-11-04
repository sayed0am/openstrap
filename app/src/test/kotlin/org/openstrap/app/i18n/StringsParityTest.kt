package org.openstrap.app.i18n

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.testing.FileStrings
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The JVM tests' [FileStrings] gives exactly what Android's `Resources` give, for every string and plural in the
 * debug build's `res/values`, read, formatted and counted. Every later wave trusts its English asserts to this. The
 * debug only `strings_i18n_check.xml` holds the escapes, quotes, tags and placeholders the real text may not have yet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StringsParityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resources: Resources get() = context.resources

    @Test @Config(qualifiers = "en-rUS")
    fun `every string and plural reads, formats and counts as Resources in English`() = assertParity(FileStrings(Locale.US))

    @Test @Config(qualifiers = "de-rDE")
    fun `every string and plural formats its numbers as Resources do in German`() = assertParity(FileStrings(Locale.GERMANY))

    @Test @Config(qualifiers = "en-rUS")
    fun `the app's strings read the same as the double`() {
        val app = ResourceStrings(context)
        val double = FileStrings()
        for (name in double.strings.keys) {
            val id = double.stringId(name)
            assertEquals(name, double.get(id), app.get(id))
            assertEquals(name, double.format(id, *args(double.get(id))), app.format(id, *args(double.get(id))))
        }
        for (name in double.plurals.keys) {
            val id = double.pluralId(name)
            for (n in COUNTS) {
                assertEquals("$name $n", double.plural(id, n), app.plural(id, n))
                assertEquals("$name $n", double.plural(id, n, n, PI), app.plural(id, n, n, PI))
            }
        }
    }

    @Test
    fun `the tricky strings come out as Android writes them`() {
        val double = FileStrings()
        fun text(name: String) = double.get(double.stringId(name))
        assertEquals("Today's \"best\" and isn't", text("i18n_check_apostrophes"))
        assertEquals("Line one\nLine\ttwo @home ?attr back\\slash été #5 q", text("i18n_check_escapes"))
        assertEquals("  Kept   spaces, it's fine  ", text("i18n_check_quoted"))
        assertEquals("Folded over lines with a tab", text("i18n_check_whitespace"))
        assertEquals("<b> isn’t bold & this … quoted", text("i18n_check_entities"))
        assertEquals("<i>not a tag</i> and 'escaped'", text("i18n_check_cdata"))
        assertEquals("A bold and italic word", text("i18n_check_styled"))
        assertEquals("Ran %1\$s in %2\$s, %3\$s a km", text("i18n_check_xliff"))
        assertEquals("19.3 MB, 45%, 12,345 steps", double.format(double.stringId("i18n_check_numbers"), 19.27, 45, 12_345))
        assertEquals(
            "19,3 MB, 45%, 12.345 steps",
            FileStrings(Locale.GERMANY).format(double.stringId("i18n_check_numbers"), 19.27, 45, 12_345),
        )
        assertEquals("100%% done", text("i18n_check_percent"))
        assertEquals("100% done", double.format(double.stringId("i18n_check_percent")))
        assertEquals("1 day", double.plural(double.pluralId("i18n_check_days"), 1, 1))
        assertEquals("%d days", double.plural(double.pluralId("i18n_check_days"), 2))
        assertEquals("3 sets", double.plural(double.pluralId("i18n_check_only_other"), 1, 3))
    }

    private fun assertParity(double: FileStrings) {
        assertTrue("found only ${double.strings.size} strings", double.strings.size > 40)
        assertTrue("found only ${double.plurals.size} plurals", double.plurals.size >= 3)
        for ((name, text) in double.strings) {
            val id = double.stringId(name)
            assertEquals(name, resources.getString(id), text)
            assertEquals(name, resources.getString(id, *args(text)), double.format(id, *args(text)))
        }
        for (name in double.plurals.keys) {
            val id = double.pluralId(name)
            for (n in COUNTS) {
                assertEquals("$name $n", resources.getQuantityString(id, n), double.plural(id, n))
                val args = arrayOf<Any>(n, PI)
                assertEquals("$name $n", resources.getQuantityString(id, n, *args), double.plural(id, n, *args))
            }
        }
    }

    private companion object {
        val COUNTS = listOf(0, 1, 2, 5, 21, 1_000)
        const val PI = 31_415.926

        private val specifier = Regex("""%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([a-zA-Z%])""")

        /** Arguments that fill [text]'s placeholders, by position and type, so every string is formatted for real. */
        fun args(text: String): Array<Any> {
            val types = mutableMapOf<Int, Char>()
            var next = 1
            specifier.findAll(text).forEach { m ->
                val type = m.groupValues[2].single()
                if (type == '%' || type == 'n') return@forEach
                val position = m.groupValues[1].toIntOrNull() ?: next++
                types[position] = type
            }
            return Array(types.keys.maxOrNull() ?: 0) { i ->
                when (types[i + 1]) {
                    'd', 'x', 'o' -> 12_345
                    'f', 'e', 'g' -> PI
                    else -> "Ab"
                }
            }
        }
    }
}
