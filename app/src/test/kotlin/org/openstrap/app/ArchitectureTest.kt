package org.openstrap.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openstrap.app.testing.SourceTree

/**
 * The layer rules from the wave 4 sketch (section 4 and Decisions 6), checked on every Kotlin file in `src/main`.
 *
 * A file's layer is its package line. Edges are read from import lines and from fully qualified
 * `org.openstrap.app.<package>.` names in code, since many files use those without an import. Comments, KDoc and
 * string literals are blanked out first, so only code counts (`LayersTest` stays as the stricter check that also
 * keeps KDoc links in data, device, update and presentation clean).
 *
 * It fails on an edge outside [Architecture.ALLOWED], on an `androidx.compose` name in [Architecture.NO_COMPOSE],
 * on `AppContainer` named below the root, and on any cycle between top-level packages. `ui.root` counts as the root
 * for the names in the root package (`AppContainer`, `NavRequest`, `PendingAction` and the rest): it is the root's
 * composition split out of MainActivity, and `ViewModelFactories` takes the container by design (Decisions 2). For
 * every other edge it is plain `ui`. Cycles are counted between top-level packages, so `ui.glow` and `ui.workout`
 * are one node and the `ui` sub-package cycles are out of scope, as Decisions 6 says.
 *
 * `src/debug` and `src/release` are not scanned. They hold the debug intents, the demo data and the galleries, which
 * may reach anything the root may, so no rule here could fail on them.
 */
class ArchitectureTest {
    @Test
    fun mainSourcesKeepTheLayers() {
        val files = SourceTree.kotlin.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { Source(it.relativeTo(SourceTree.kotlin).invariantSeparatorsPath, it.readText()) }
            .toList()
        assertTrue("found only ${files.size} sources under ${SourceTree.kotlin}", files.size > 100)
        val report = Architecture.check(files, Architecture.EXCEPTIONS)
        println(report.skippedText())
        if (report.failures.isNotEmpty()) fail(report.failureText())
    }

    @Test
    fun checkerCatchesAPlantedImport() {
        val report = Architecture.check(
            listOf(
                source("data/Plant.kt", "package org.openstrap.app.data", "", "import org.openstrap.app.ui.dashboard.TabCopy"),
                source("ui/dashboard/TabCopy.kt", "package org.openstrap.app.ui.dashboard", "", "object TabCopy"),
            ),
            emptyList(),
        )
        assertEquals(listOf("data/Plant.kt:3 edge"), report.failures.map { "${it.where} ${it.rule}" })
        assertEquals("org.openstrap.app.ui.dashboard.TabCopy", report.failures.single().target)
    }

    @Test
    fun checkerCatchesAPlantedQualifiedNameInCodeOnly() {
        val report = Architecture.check(
            listOf(
                source(
                    "device/Plant.kt",
                    "package org.openstrap.app.device",
                    "",
                    "/** Calls [org.openstrap.app.widget.WidgetUpdater] after a sync. */",
                    "fun plant() = org.openstrap.app.widget.WidgetUpdater.changed()",
                    "// org.openstrap.app.widget.WidgetUpdater in a comment",
                    "val name = \"org.openstrap.app.widget.WidgetUpdater\"",
                    "val line = \"now \${org.openstrap.app.widget.WidgetUpdater.count} widgets\"",
                    "/* outer /* nested org.openstrap.app.widget.WidgetUpdater */ still org.openstrap.app.widget.X */",
                    "val c = '\"'",
                ),
                source("widget/WidgetUpdater.kt", "package org.openstrap.app.widget", "", "object WidgetUpdater"),
            ),
            emptyList(),
        )
        assertEquals(listOf("device/Plant.kt:4", "device/Plant.kt:7"), report.failures.map { it.where })
        assertTrue(report.failures.all { it.rule == "edge" && it.target.startsWith("org.openstrap.app.widget.WidgetUpdater") })
    }

    @Test
    fun checkerKnowsTheOtherRules() {
        val known = KnownBreak("export/", listOf("org.openstrap.app.data.Gone"), "a planted break", "never")
        val report = Architecture.check(
            listOf(
                source("widget/W.kt", "package org.openstrap.app.widget", "", "import org.openstrap.app.AppContainer"),
                source("ui/root/F.kt", "package org.openstrap.app.ui.root", "", "import org.openstrap.app.AppContainer"),
                source("device/D.kt", "package org.openstrap.app.device", "", "import androidx.compose.ui.graphics.Color"),
                source("data/A.kt", "package org.openstrap.app.data", "", "fun a() = org.openstrap.app.export.B.b()"),
                source("export/B.kt", "package org.openstrap.app.export", "", "import org.openstrap.app.data.a"),
                source("ui/glow/G.kt", "package org.openstrap.app.ui.glow", "", "import org.openstrap.app.ui.workout.K"),
                source("ui/workout/K.kt", "package org.openstrap.app.ui.workout", "", "import org.openstrap.app.ui.glow.G"),
                source("OpenStrapApp.kt", "package org.openstrap.app", "", "class AppContainer"),
            ),
            listOf(known),
        )
        val rules = report.failures.map { "${it.where} ${it.rule}" }
        assertEquals(listOf("data/A.kt:3 edge", "device/D.kt:3 compose", "widget/W.kt:3 container"), rules)
        assertEquals(listOf(listOf("data", "export")), report.cycles.map { it.nodes })
        assertEquals(listOf(known), report.unused)
    }

    private fun source(path: String, vararg lines: String) = Source(path, lines.joinToString("\n"))
}

/** One Kotlin file, its [path] relative to the app's package folder. */
internal data class Source(val path: String, val text: String)

/**
 * A known break of the rules that the check lets through and prints, kept until [removedBy]. It covers a finding in
 * a file whose path is [from] (or starts with it, when [from] ends with `/`) naming one of [to] or a member of it.
 */
internal data class KnownBreak(val from: String, val to: List<String>, val reason: String, val removedBy: String) {
    fun covers(f: Finding): Boolean =
        (f.path == from || from.endsWith("/") && f.path.startsWith(from)) &&
            to.any { f.target == it || f.target.startsWith("$it.") }

    override fun toString() = "$from to ${to.joinToString()}: $reason ($removedBy)"
}

/** A broken rule: [rule] is `edge`, `compose` or `container`. */
internal data class Finding(val path: String, val line: Int, val rule: String, val target: String, val detail: String) {
    val where get() = "$path:$line"
}

/** A strongly connected group of top-level packages, with one place for each edge inside it. */
internal data class Cycle(val nodes: List<String>, val edges: List<String>)

internal data class Report(
    val failures: List<Finding>,
    val cycles: List<Cycle>,
    val unused: List<KnownBreak>,
    val skipped: Map<KnownBreak, List<Finding>>,
    val heldOpen: List<Cycle>,
) {
    fun failureText(): String = buildString {
        if (failures.isNotEmpty()) appendLine("Broken layer rules:")
        failures.forEach { appendLine("  ${it.where}  ${it.rule}: ${it.detail}") }
        if (cycles.isNotEmpty()) appendLine("Cycles between top-level packages:")
        cycles.forEach(::appendCycle)
        if (unused.isNotEmpty()) appendLine("Exceptions that no longer match anything (delete them):")
        unused.forEach { appendLine("  $it") }
    }

    fun skippedText(): String = buildString {
        appendLine("ArchitectureTest skipped these known breaks:")
        skipped.forEach { (k, fs) ->
            appendLine("  $k")
            fs.forEach { appendLine("    ${it.where}  ${it.target}") }
        }
        if (heldOpen.isNotEmpty()) appendLine("Cycles that only those known breaks keep open:")
        heldOpen.forEach(::appendCycle)
    }
}

private fun StringBuilder.appendCycle(c: Cycle) {
    appendLine("  ${c.nodes.joinToString(" and ")}")
    c.edges.forEach { appendLine("    $it") }
}

internal object Architecture {
    private const val APP = "org.openstrap.app"
    private const val ROOT = "root"

    /**
     * What each top-level package may name in `org.openstrap.app`. `root` means only [ROOT_NAMES]. `i18n` (the app's
     * text for plain Kotlin) names nothing and every layer may read it.
     */
    val ALLOWED: Map<String, Set<String>> = mapOf(
        "i18n" to setOf(),
        "ble" to setOf("i18n"),
        "firmware" to setOf("i18n"),
        "zepp" to setOf("i18n", "firmware", "data"),
        "data" to setOf("i18n", "ble", "firmware", "zepp", "data"),
        "presentation" to setOf("i18n", "data"),
        "device" to setOf("i18n", "ble", "firmware", "zepp", "data", "presentation", ROOT),
        "health" to setOf("i18n", "ble", "firmware", "zepp", "data", "presentation", "device", ROOT),
        "export" to setOf("i18n", "ble", "firmware", "zepp", "data", "presentation", "device", ROOT),
        "update" to setOf("i18n", "ble", "firmware", "zepp", "data", "presentation", "device", ROOT),
        "widget" to setOf(
            "i18n", "ble", "firmware", "zepp", "data", "presentation", "device", "health", "export", "update", ROOT,
            "ui.glow.draw", "ui.theme",
        ),
        "ui" to setOf("i18n", "ble", "firmware", "zepp", "data", "presentation", "device", "health", "export", "update", ROOT, "ui"),
    )

    /** Packages with no Compose at all, in imports or in code. */
    val NO_COMPOSE = setOf("i18n", "ble", "firmware", "zepp", "data", "presentation", "device", "health", "export", "update")

    /**
     * The entry points a lower layer may name: to reach the container's owner, build an intent, read the build. `R`
     * (the resource ids) any layer may name, since copy in every layer reads its text through `Strings`.
     */
    val ROOT_NAMES = setOf("OpenStrapApp", "MainActivity", "BuildConfig", "R")

    /** Breaks the check lets through for now, each with the wave that removes it. One that matches nothing fails. */
    val EXCEPTIONS: List<KnownBreak> = emptyList()

    private val appName = Regex("""(?<![\w.])org\.openstrap\.app(?:\.(?:\w+|\*))+""")
    private val composeName = Regex("""(?<![\w.])androidx\.compose(?:\.(?:\w+|\*))+""")
    private val packageLine = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)

    private fun short(pkg: String) = if (pkg == APP) ROOT else pkg.removePrefix("$APP.")

    private fun top(short: String) = short.substringBefore('.')

    private fun wiresRoot(short: String) = short == ROOT || short == "ui.root" || short.startsWith("ui.root.")

    private class Ref(val src: Source, val line: Int, val from: String, val to: String, val name: String, val target: String)

    fun check(files: List<Source>, exceptions: List<KnownBreak>): Report {
        val packages = files.associateWith { packageLine.find(it.text)?.groupValues?.get(1) }
        val known = packages.values.filterNotNull().toSet() + APP
        val refs = mutableListOf<Ref>()
        val findings = mutableListOf<Pair<Ref?, Finding>>()
        for ((src, pkg) in packages) {
            if (pkg == null) {
                findings += null to Finding(src.path, 1, "edge", "", "no package line")
                continue
            }
            val from = short(pkg)
            blankCommentsAndStrings(src.text).lines().forEachIndexed { i, line ->
                if (line.trimStart().startsWith("package ")) return@forEachIndexed
                appName.findAll(line).forEach { m ->
                    val segs = m.value.split('.')
                    val pk = (segs.size downTo 3).map { segs.take(it).joinToString(".") }.first { it in known }
                    val to = short(pk)
                    if (pk != pkg) refs += Ref(src, i + 1, from, to, segs.getOrElse(pk.count { it == '.' } + 1) { "*" }, m.value)
                }
                if (top(from) in NO_COMPOSE) composeName.findAll(line).forEach { m ->
                    findings += null to Finding(src.path, i + 1, "compose", m.value, "${m.value} in $from")
                }
            }
        }
        refs.forEach { r -> rule(r)?.let { findings += r to it } }

        val excused = mutableSetOf<Ref>()
        val skipped = linkedMapOf<KnownBreak, MutableList<Finding>>()
        val failures = mutableListOf<Finding>()
        for ((ref, f) in findings) {
            val k = exceptions.firstOrNull { it.covers(f) }
            if (k == null) failures += f else {
                skipped.getOrPut(k) { mutableListOf() } += f
                ref?.let(excused::add)
            }
        }
        val strict = cycles(refs.filter { it !in excused })
        val all = cycles(refs)
        return Report(
            failures.sortedWith(compareBy({ it.path }, { it.line })),
            strict,
            exceptions.filter { it !in skipped },
            skipped.mapValues { (_, fs) -> fs.sortedWith(compareBy({ it.path }, { it.line })) },
            all.filter { c -> strict.none { it.nodes.containsAll(c.nodes) } },
        )
    }

    private fun rule(r: Ref): Finding? {
        if (r.from == ROOT) return null
        if (r.to == ROOT) return when {
            wiresRoot(r.from) -> null
            r.name == "AppContainer" -> Finding(r.src.path, r.line, "container", r.target, "AppContainer named in ${r.from}")
            r.name == "R" -> null
            r.name in ROOT_NAMES && ROOT in ALLOWED[top(r.from)].orEmpty() -> null
            else -> Finding(r.src.path, r.line, "edge", r.target, "${r.from} names root ${r.name}")
        }
        val allowed = ALLOWED[top(r.from)]
            ?: return Finding(r.src.path, r.line, "edge", r.target, "${top(r.from)} has no row in ALLOWED")
        val ok = top(r.to) == top(r.from) || allowed.any { r.to == it || r.to.startsWith("$it.") }
        return if (ok) null else Finding(r.src.path, r.line, "edge", r.target, "${r.from} to ${r.to} (${r.target})")
    }

    /** Groups of top-level packages that reach each other, the root left out since it may name anything. */
    private fun cycles(refs: List<Ref>): List<Cycle> {
        val edges = linkedMapOf<Pair<String, String>, Ref>()
        refs.filter { top(it.from) != ROOT && top(it.to) != ROOT && top(it.from) != top(it.to) }
            .sortedWith(compareBy({ it.src.path }, { it.line }))
            .forEach { edges.putIfAbsent(top(it.from) to top(it.to), it) }
        val next = edges.keys.groupBy({ it.first }, { it.second })
        fun reach(a: String): Set<String> {
            val seen = mutableSetOf<String>()
            val todo = ArrayDeque(next[a].orEmpty())
            while (todo.isNotEmpty()) todo.removeFirst().let { if (seen.add(it)) todo += next[it].orEmpty() }
            return seen
        }
        val reaches = next.keys.associateWith(::reach)
        return reaches.keys.mapNotNull { a ->
            reaches.keys.filter { b -> b in reaches[a].orEmpty() && a in reaches[b].orEmpty() }.sorted().takeIf { it.size > 1 }
        }.distinct().sortedBy { it.first() }.map { group ->
            Cycle(
                group,
                edges.filterKeys { (a, b) -> a in group && b in group }
                    .map { (e, r) -> "${e.first} to ${e.second} at ${r.src.path}:${r.line} (${r.target})" },
            )
        }
    }
}

/**
 * [src] with comments, KDoc and string literal text replaced by spaces, newlines kept so line numbers hold. Code
 * inside a string template stays, nested block comments are followed to their end.
 */
internal fun blankCommentsAndStrings(src: String): String = Blanker(src).run()

private class Blanker(private val s: String) {
    private val out = StringBuilder(s.length)
    private var i = 0

    fun run(): String {
        code(nested = false)
        return out.toString()
    }

    private fun keep() {
        out.append(s[i])
        i++
    }

    private fun blank(n: Int = 1) = repeat(n) {
        if (i < s.length) {
            out.append(if (s[i] == '\n') '\n' else ' ')
            i++
        }
    }

    private fun code(nested: Boolean) {
        var depth = 0
        while (i < s.length) {
            when {
                s.startsWith("//", i) -> while (i < s.length && s[i] != '\n') blank()
                s.startsWith("/*", i) -> comment()
                s.startsWith("\"\"\"", i) -> blank(3).also { string(raw = true) }
                s[i] == '"' -> blank().also { string(raw = false) }
                s[i] == '\'' -> char()
                s[i] == '{' -> depth++.also { keep() }
                s[i] == '}' && nested && depth == 0 -> return blank()
                s[i] == '}' -> depth--.also { keep() }
                else -> keep()
            }
        }
    }

    private fun comment() {
        var depth = 0
        do {
            when {
                s.startsWith("/*", i) -> depth++.also { blank(2) }
                s.startsWith("*/", i) -> depth--.also { blank(2) }
                else -> blank()
            }
        } while (depth > 0 && i < s.length)
    }

    private fun string(raw: Boolean) {
        while (i < s.length) {
            when {
                raw && s.startsWith("\"\"\"", i) -> {
                    while (s.startsWith("\"\"\"\"", i)) blank()
                    return blank(3)
                }
                !raw && s[i] == '"' -> return blank()
                !raw && s[i] == '\\' -> blank(2)
                s.startsWith("\${", i) -> blank(2).also { code(nested = true) }
                else -> blank()
            }
        }
    }

    private fun char() {
        blank()
        while (i < s.length && s[i] != '\'') {
            if (s[i] == '\\') blank()
            blank()
        }
        blank()
    }
}
