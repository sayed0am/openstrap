package org.openstrap.app.testing

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.robolectric.Shadows.shadowOf

/**
 * Compose UI tests under Robolectric. The merged manifest the unit tests get doesn't declare the activity the compose
 * rule hosts its content in, so it is registered with Robolectric's package manager first. Use it as
 * `@get:Rule val rules = ComposeRules()` and drive `rules.compose`.
 *
 * The deprecated rule on purpose: the v2 one sets Compose's main dispatcher up differently, and as that dispatcher is
 * made once per test process, LiveRecompositionTest then never sees a frame when it runs after a v2 test.
 */
@Suppress("DEPRECATION")
class ComposeRules(val compose: ComposeContentTestRule = createComposeRule()) : org.junit.rules.TestRule {
    private val chain = RuleChain.outerRule(HostActivity()).around(compose)

    override fun apply(base: org.junit.runners.model.Statement, description: org.junit.runner.Description) =
        chain.apply(base, description)

    private class HostActivity : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }
}
