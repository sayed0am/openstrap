package org.openstrap.app.widget

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * Widgets spec 2.1 and 6.1: every widget has the same three sizes and resizes the same way. The provider check reads
 * every `appwidget-provider` in res/xml, so each widget step 4 on adds is held to it too, and a failure names it.
 */
class WidgetSizesTest {
    private val root: File = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val android = "http://schemas.android.com/apk/res/android"

    @Test fun `the three sizes are the spec's`() {
        assertEquals(listOf(150f to 150f, 280f to 160f, 280f to 260f), WidgetSizes.all.map { it.width.value to it.height.value })
        assertEquals(WidgetSizes.Small, WidgetSizes.all.first())
        assertEquals(WidgetSizes.Large, WidgetSizes.all.last())
    }

    /** The provider XML files, by name: those whose root is `appwidget-provider`. */
    private fun providers(): Map<String, Element> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return File(root, "res/xml").listFiles().orEmpty().filter { it.extension == "xml" }.mapNotNull { f ->
            val doc = factory.newDocumentBuilder().parse(f).documentElement
            if (doc.tagName == "appwidget-provider") f.name to doc else null
        }.toMap()
    }

    @Test fun `every provider declares the same sizes and resize limits`() {
        val all = providers()
        assertTrue("no widget providers in res/xml", all.containsKey("widget_recovery.xml"))
        // Each widget built so far, so a provider dropped by mistake fails here too.
        listOf("widget_moment.xml", "widget_recovery.xml", "widget_steps.xml", "widget_sleep.xml", "widget_start.xml").forEach {
            assertTrue("$it missing from res/xml", all.containsKey(it))
        }
        val wrong = mutableListOf<String>()
        val max = WidgetSizes.MaxResize
        val expected = mapOf(
            "targetCellWidth" to "${WidgetSizes.TARGET_CELLS}",
            "targetCellHeight" to "${WidgetSizes.TARGET_CELLS}",
            "minWidth" to "${WidgetSizes.MIN_DP}dp",
            "minHeight" to "${WidgetSizes.MIN_DP}dp",
            "minResizeWidth" to "${WidgetSizes.MIN_DP}dp",
            "minResizeHeight" to "${WidgetSizes.MIN_DP}dp",
            "maxResizeWidth" to "${max.width.value.toInt()}dp",
            "maxResizeHeight" to "${max.height.value.toInt()}dp",
            "resizeMode" to "horizontal|vertical",
            "updatePeriodMillis" to "0",
            "widgetCategory" to "home_screen",
        )
        all.forEach { (name, e) ->
            expected.forEach { (attr, value) ->
                val got = e.getAttributeNS(android, attr)
                if (got != value) wrong += "$name: $attr is \"$got\", the spec's is \"$value\""
            }
            if (!e.getAttributeNS(android, "description").startsWith("@string/")) wrong += "$name: no description string"
            // The picker's preview (spec 6.4): a layout on Android 12 and later, an image before, both real files.
            val layout = e.getAttributeNS(android, "previewLayout")
            if (!layout.startsWith("@layout/")) wrong += "$name: no previewLayout"
            else if (!File(root, "res/layout/${layout.removePrefix("@layout/")}.xml").isFile) wrong += "$name: $layout has no file"
            val image = e.getAttributeNS(android, "previewImage")
            if (!image.startsWith("@drawable/")) wrong += "$name: no previewImage"
            else if (root.resolve("res").listFiles().orEmpty().filter { it.name.startsWith("drawable") }
                    .none { d -> d.listFiles().orEmpty().any { it.nameWithoutExtension == image.removePrefix("@drawable/") } }) wrong += "$name: $image has no file"
        }
        if (wrong.isNotEmpty()) fail(wrong.joinToString("\n"))
    }

    @Test fun `every provider's description and name are strings in the copy rules`() {
        val strings = File(root, "res/values/strings.xml").readText()
        providers().forEach { (name, e) ->
            val key = e.getAttributeNS(android, "description").removePrefix("@string/")
            val text = Regex("<string name=\"$key\">([^<]*)</string>").find(strings)?.groupValues?.get(1)
            assertTrue("$name: $key missing from strings.xml", text != null)
            assertTrue("$name: \"$text\" breaks the copy rules", text!!.none { it in "·;—" } && !text.endsWith("."))
        }
        // Each widget's receiver has its picker name.
        val manifest = File(root, "AndroidManifest.xml").readText()
        listOf("moment", "recovery", "steps", "sleep", "start").forEach {
            assertTrue("widget_${it}_name not the receiver's label", "@string/widget_${it}_name" in manifest)
            assertTrue("widget_${it}_name missing", "name=\"widget_${it}_name\"" in strings)
        }
    }

    @Test fun `the layout picked is the largest that fits, else the Small`() {
        assertEquals(WidgetSizes.Small, WidgetSizes.bucket(173f, 176f))
        assertEquals(WidgetSizes.Wide, WidgetSizes.bucket(300f, 250f))
        // One UI on the Fold's cover screen: 2 × 2 is 167 × 201, 4 × 2 is 353 × 201, 4 × 3 is 353 × 375.
        assertEquals(WidgetSizes.Small, WidgetSizes.bucket(167.6f, 201.1f))
        assertEquals(WidgetSizes.Wide, WidgetSizes.bucket(353.5f, 201.1f))
        assertEquals(WidgetSizes.Large, WidgetSizes.bucket(353.5f, 375.8f))
        assertEquals(WidgetSizes.Wide, WidgetSizes.bucket(400f, 200f))
        assertEquals(WidgetSizes.Large, WidgetSizes.bucket(358f, 272f))
        assertEquals(WidgetSizes.Large, WidgetSizes.bucket(357.8f, 271.6f))
        assertEquals(WidgetSizes.Small, WidgetSizes.bucket(120f, 120f))
    }

    @Test fun `bitmaps are drawn at the cell's real size when the launcher reports it`() {
        assertEquals(390f to 190f, WidgetSizes.drawSize(WidgetSizes.Wide, listOf(180f to 400f, 390f to 190f)))
        assertEquals(280f to 160f, WidgetSizes.drawSize(WidgetSizes.Wide, emptyList()))
        assertEquals(280f to 160f, WidgetSizes.drawSize(WidgetSizes.Wide, listOf(0f to 0f)))
    }

    @Test fun `content fits the smallest real cell of its layout`() {
        // One UI's 2 x 2 in portrait and landscape: both are the Small, fit to the smaller of each.
        assertEquals(155.4f to 158.5f, WidgetSizes.fitSize(WidgetSizes.Small, listOf(167.6f to 201.1f, 155.4f to 158.5f)))
        // A size that maps to another layout doesn't count.
        assertEquals(167.6f to 201.1f, WidgetSizes.fitSize(WidgetSizes.Small, listOf(167.6f to 201.1f, 353.5f to 201.1f)))
        assertEquals(150f to 150f, WidgetSizes.fitSize(WidgetSizes.Small, emptyList()))
    }

    @Test fun `the corner is 28 dp, or the launcher's when smaller`() {
        assertEquals(28f, WidgetSizes.corner(null), 0f)
        assertEquals(16f, WidgetSizes.corner(16f), 0f)
        assertEquals(28f, WidgetSizes.corner(32f), 0f)
        assertEquals(28f, WidgetSizes.corner(0f), 0f)
    }

    @Test fun `bitmaps stay inside the RemoteViews limit`() {
        val budget = WidgetSizes.bitmapBudget(1080, 2400)
        assertEquals(1080L * 2400 * 6, budget)
        assertEquals(1f, WidgetSizes.bitmapScale(1_000_000, budget), 0f)
        val big = 10_000_000L
        val s = WidgetSizes.bitmapScale(big, budget)
        assertTrue(s < 1f)
        assertTrue(big * s * s * 4 <= budget)
    }
}
