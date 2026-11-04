package org.openstrap.app.testing

import org.openstrap.app.i18n.Strings
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * [Strings] for plain JVM tests: the English text read from the `res/values` XML on disk, rewritten as aapt rewrites it,
 * so a test gets exactly what `Resources` would give (StringsParityTest proves it for every string). The test classpath
 * offers it through `META-INF/services`, so copy that defaults to `Strings.app` reads English with no setup.
 *
 * It formats in [locale], en-US unless a test asks for another, as Robolectric's default configuration does. That
 * keeps the English asserts the same under `-PtestLocale=de-DE`. A test that checks a number follows the language
 * passes `FileStrings(Locale.GERMANY)`. Plurals follow English rules (one for 1, other for the rest), the rules of the
 * folder it reads.
 *
 * It reads `src/main/res/values` and, as the debug build merges them over it, `src/debug/res/values`. `<string>` and
 * `<plurals>` only: a string array or a reference to another string fails loudly instead of reading wrong.
 */
class FileStrings(val locale: Locale = Locale.US, folders: List<File> = DEBUG_VALUES) : Strings {
    /** Every string by name, as Android returns it. */
    val strings: Map<String, String>

    /** Every plural by name, then by quantity ("one", "other"). */
    val plurals: Map<String, Map<String, String>>

    init {
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()
        for (file in folders.flatMap { it.listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy(File::getName) }) {
            val root = parse(file)
            for (e in root.children()) {
                val name = e.getAttribute("name")
                when (e.tagName) {
                    "string" -> strings[name] = AaptText.of(e, "${file.name} $name")
                    "plurals" -> plurals[name] = e.children().filter { it.tagName == "item" }
                        .associate { it.getAttribute("quantity") to AaptText.of(it, "${file.name} $name") }
                    "string-array" -> error("${file.name}: string-array $name isn't supported by FileStrings")
                }
            }
        }
        this.strings = strings
        this.plurals = plurals
    }

    private val stringIds: Map<Int, String> = ids("string", strings.keys)
    private val pluralIds: Map<Int, String> = ids("plurals", plurals.keys)

    /** The id of the string [name], from R. */
    fun stringId(name: String): Int = stringIds.entries.first { it.value == name }.key

    /** The id of the plural [name], from R. */
    fun pluralId(name: String): Int = pluralIds.entries.first { it.value == name }.key

    override fun get(id: Int): String = strings.getValue(stringIds[id] ?: error("no string 0x${id.toString(16)} in res/values"))

    override fun format(id: Int, vararg args: Any): String = String.format(locale, get(id), *args)

    override fun plural(id: Int, count: Int, vararg args: Any): String {
        val name = pluralIds[id] ?: error("no plural 0x${id.toString(16)} in res/values")
        val items = plurals.getValue(name)
        val text = items[if (count == 1) "one" else "other"] ?: items["other"] ?: error("plural $name has no other")
        return if (args.isEmpty()) text else String.format(locale, text, *args)
    }

    companion object {
        val MAIN_VALUES: File get() = File(SourceTree.root, "res/values")
        val DEBUG_VALUES: List<File> get() = listOf(MAIN_VALUES, File(SourceTree.module, "src/debug/res/values"))

        private fun parse(file: File): Element {
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            return factory.newDocumentBuilder().parse(file).documentElement
        }

        private fun Element.children(): List<Element> =
            (0 until childNodes.length).map(childNodes::item).filterIsInstance<Element>()

        /** R's ids for [names] of [type], by reflection, so a name missing from R (a typo, a stale file) fails here. */
        private fun ids(type: String, names: Set<String>): Map<Int, String> {
            if (names.isEmpty()) return emptyMap()
            val fields = Class.forName("org.openstrap.app.R\$$type").fields.associate { it.name to it.getInt(null) }
            return names.associateBy { name -> fields[name] ?: error("R.$type has no $name") }
        }
    }
}

/**
 * What aapt2 makes of a string's XML: the text of its children with tags (`xliff:g`, `b`) dropped and comments left
 * out, then backslash escapes, double quotes (which keep the spaces between them and are dropped) and runs of white
 * space outside quotes folded to one space and trimmed at both ends.
 */
private object AaptText {
    fun of(e: Element, what: String): String {
        val raw = StringBuilder().also { collect(e, it) }.toString()
        require(!raw.trimStart().startsWith("@") && !raw.trimStart().startsWith("?")) { "$what is a reference, write the text" }
        return rewrite(raw)
    }

    private fun collect(node: Node, out: StringBuilder) {
        for (i in 0 until node.childNodes.length) {
            val child = node.childNodes.item(i)
            when (child.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> out.append(child.nodeValue)
                Node.ELEMENT_NODE -> collect(child, out)
            }
        }
    }

    private fun rewrite(raw: String): String {
        val out = StringBuilder()
        // Where the text after the last quoted or escaped character starts, so trailing spaces before it stay.
        var kept = 0
        var quoted = false
        var space = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i++]
            when {
                c == '\\' && i < raw.length -> {
                    val next = raw[i++]
                    when (next) {
                        't' -> out.append('\t')
                        'n' -> out.append('\n')
                        'u' -> {
                            val hex = raw.substring(i).takeWhile { it.lowercaseChar() in HEX }.take(4)
                            out.append(hex.toInt(16).toChar())
                            i += hex.length
                        }
                        else -> out.append(next)
                    }
                    space = false
                    kept = out.length
                }
                c == '"' -> {
                    quoted = !quoted
                    space = false
                    kept = out.length
                }
                quoted -> {
                    out.append(c)
                    kept = out.length
                }
                // aapt folds ASCII white space only: a no-break space stays.
                c in SPACES -> {
                    if (!space && out.isNotEmpty()) out.append(' ')
                    space = true
                }
                else -> {
                    out.append(c)
                    space = false
                    kept = out.length
                }
            }
        }
        return out.substring(0, kept)
    }

    private const val HEX = "0123456789abcdef"
    private const val SPACES = " \t\n\r\u000B\u000C"
}
