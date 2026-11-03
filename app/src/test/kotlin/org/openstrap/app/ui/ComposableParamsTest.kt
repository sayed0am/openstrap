package org.openstrap.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openstrap.app.testing.SourceTree

/**
 * No composable takes a primitive array. Compose counts an `IntArray` as unstable and compares it by instance, so a
 * screen given one composes again on every new array even when the numbers are the same (the live zone times did,
 * open item "zoneSeconds: IntArray"). A `List<Int>` is stable and compared by its values.
 */
class ComposableParamsTest {
    @Test
    fun noComposableTakesAPrimitiveArray() {
        val files = SourceTree.kotlin.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("found only ${files.size} sources under ${SourceTree.kotlin}", files.size > 100)
        val found = files.flatMap { f -> arrayParams(f.readText()).map { "${f.relativeTo(SourceTree.kotlin).invariantSeparatorsPath}: $it" } }
        if (found.isNotEmpty()) fail("composables taking a primitive array:\n" + found.joinToString("\n"))
    }

    @Test
    fun checkerCatchesAPlantedArray() {
        val planted = """
            @Composable
            internal fun ZoneTimeBar(
                zoneSeconds: IntArray,
                current: Int?,
            ) {}

            @Composable fun Fine(zones: List<Int>, onTap: (IntArray) -> Unit) {}

            fun notComposable(values: FloatArray) = Unit
        """.trimIndent()
        assertEquals(listOf("ZoneTimeBar(zoneSeconds: IntArray)"), arrayParams(planted))
    }

    private companion object {
        val composable = Regex("""@Composable\b[^(]*?\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")
        val arrayParam = Regex("""(?:^|[(,])\s*(\w+)\s*:\s*((?:Int|Long|Float|Double|Short|Byte|Char|Boolean)Array)\b""")

        /** Each composable's parameters typed as a primitive array, as "Name(param: Type)". */
        fun arrayParams(text: String): List<String> = composable.findAll(text).flatMap { m ->
            val params = paramList(text, m.range.last)
            arrayParam.findAll(params).map { "${m.groupValues[1]}(${it.groupValues[1]}: ${it.groupValues[2]})" }
        }.toList()

        /** The parameter list that opens at [open], with nested brackets and lambda types blanked out. */
        fun paramList(text: String, open: Int): String {
            val out = StringBuilder()
            var depth = 0
            for (i in open until text.length) {
                val c = text[i]
                if (c == '(' || c == '<' || c == '{') depth++
                // The arrow of a lambda type isn't a closing bracket.
                if (c == ')' || (c == '>' && text[i - 1] != '-') || c == '}') depth--
                if (depth == 0) break
                // Only the top level counts, so a lambda's own parameter types don't.
                out.append(if (depth == 1 && c != '(') c else ' ')
            }
            return out.toString()
        }
    }
}
