package org.openstrap.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the code a user runs in the first minutes: launch, every tab, You and Strap, and a few cards on each tab
 * opened and closed. Without it ART runs those screens interpreted until the JIT warms up, and the first opening of
 * each stutters (a 76 ms frame opening You on a release build, 41 ms precompiled, measured on a Galaxy Z Fold).
 *
 * Run it against the dev app on a phone with Android 13 or later, with the strap's data on it (cards it can't find
 * are skipped). It installs as org.openstrap.app.dev, so never over the real app. Nobody should use the phone while
 * it taps through, about five minutes:
 *
 *     ./gradlew :app:assembleBenchmark :baselineprofile:assembleBenchmark
 *     adb install -r --user 0 app/build/outputs/apk/benchmark/app-benchmark.apk
 *     adb install -r --user 0 baselineprofile/build/outputs/apk/benchmark/baselineprofile-benchmark.apk
 *     adb shell am instrument -w --user 0 -e class org.openstrap.baselineprofile.BaselineProfileGenerator \
 *         org.openstrap.baselineprofile/androidx.test.runner.AndroidJUnitRunner
 *
 * Then copy the newest `*-baseline-prof.txt` from /sdcard/Android/media/org.openstrap.baselineprofile/ over
 * app/src/main/baseline-prof.txt. Installed with --user 0 so neither lands in a second user (a phone's dual app space).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        // Today is first. Each tab: its cards scrolled through, then a few opened and closed.
        for ((tab, cards) in JOURNEY) {
            tapDesc(tab)
            scrollThrough()
            cards.forEach { openAndClose(it) }
        }
        tapDesc("Today")
        // The header's two pages.
        openAndClose(By.desc("You"))
        openAndClose(By.descStartsWith("Strap"))
    }

    private fun MacrobenchmarkScope.tapDesc(desc: String) {
        device.wait(Until.findObject(By.desc(desc)), WAIT_MS)?.click()
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
    }

    /** Down the page and back, so each card is drawn once. */
    private fun MacrobenchmarkScope.scrollThrough() {
        val x = device.displayWidth / 2
        val top = device.displayHeight * 3 / 10
        val bottom = device.displayHeight * 7 / 10
        repeat(2) { device.swipe(x, bottom, x, top, SWIPE_STEPS) }
        repeat(3) { device.swipe(x, top, x, bottom, SWIPE_STEPS) }
        Thread.sleep(SETTLE_MS)
    }

    private fun MacrobenchmarkScope.openAndClose(title: String) = openAndClose(By.text(title))

    /** Opens the card (or header button) matching [selector] if it shows above the tab bar, then goes back. */
    private fun MacrobenchmarkScope.openAndClose(selector: BySelector) {
        val target = findAboveTabs(selector) ?: run {
            // Lower on the page: one swipe down to look.
            val x = device.displayWidth / 2
            device.swipe(x, device.displayHeight * 7 / 10, x, device.displayHeight * 4 / 10, SWIPE_STEPS)
            Thread.sleep(SETTLE_MS)
            findAboveTabs(selector)
        } ?: return
        target.click()
        Thread.sleep(OPEN_MS)
        device.pressBack()
        Thread.sleep(OPEN_MS)
        // A tap that opened nothing lets back leave the app: start it again.
        if (device.currentPackageName != PACKAGE) startActivityAndWait()
    }

    private fun MacrobenchmarkScope.findAboveTabs(selector: BySelector): UiObject2? =
        device.findObjects(selector).firstOrNull { it.visibleBounds.bottom < device.displayHeight * TAB_BAR_TOP }

    private companion object {
        const val PACKAGE = "org.openstrap.app.dev"

        /** Each tab by its icon's description, and the cards opened on it by their titles. */
        val JOURNEY = listOf(
            "Today" to listOf("Steps", "Recovery"),
            "Recovery" to listOf("Resting heart rate", "Stress", "Load"),
            "Sleep" to listOf("Last night", "Lowest heart rate", "HRV"),
            "Activity" to listOf("Steps", "Active minutes", "Time to recover"),
        )

        /** Where the tab bar starts, as a share of the screen's height; a title lower down is the tab's own label. */
        const val TAB_BAR_TOP = 0.88
        const val WAIT_MS = 3_000L
        const val SETTLE_MS = 600L
        const val OPEN_MS = 1_200L
        const val SWIPE_STEPS = 20
    }
}
