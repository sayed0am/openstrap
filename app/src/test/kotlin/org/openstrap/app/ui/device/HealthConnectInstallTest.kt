package org.openstrap.app.ui.device

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.health.connect.client.HealthConnectClient
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.openstrap.app.data.settings.HealthConnectAuto
import org.openstrap.app.health.HcAvailability
import org.openstrap.app.testing.ComposeRules
import org.openstrap.app.ui.root.HC_INSTALL_FIRST
import org.openstrap.app.ui.root.HC_UNAVAILABLE
import org.openstrap.app.ui.root.HcState
import org.openstrap.app.ui.root.hcMissingMessage
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * On Android 12 and 13 Health Connect is a Play Store app. Where the SDK says it's missing or out of date the app offers
 * to install it, where it truly isn't there it says so.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HealthConnectInstallTest {
    @get:Rule val rules = ComposeRules()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `the SDK's status says available, installable or unavailable`() {
        assertEquals(HcAvailability.AVAILABLE, HcAvailability.of(HealthConnectClient.SDK_AVAILABLE))
        assertEquals(HcAvailability.INSTALLABLE, HcAvailability.of(HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED))
        assertEquals(HcAvailability.UNAVAILABLE, HcAvailability.of(HealthConnectClient.SDK_UNAVAILABLE))
    }

    @Config(sdk = [32])
    @Test fun `Android 12 without the Health Connect app can install it`() {
        assertEquals(HcAvailability.INSTALLABLE, HcAvailability.of(app))
    }

    @Test fun `an action stops to install first, or says it isn't available`() {
        assertNull(HcState.blocked(HcAvailability.AVAILABLE))
        assertEquals(HcState.Installable, HcState.blocked(HcAvailability.INSTALLABLE))
        assertEquals(HcState.Unavailable, HcState.blocked(HcAvailability.UNAVAILABLE))
        assertEquals("Install Health Connect first.", HC_INSTALL_FIRST)
        assertEquals(HC_INSTALL_FIRST, hcMissingMessage(HcAvailability.INSTALLABLE))
        assertEquals(HC_UNAVAILABLE, hcMissingMessage(HcAvailability.UNAVAILABLE))
        assertEquals(HC_UNAVAILABLE, hcMissingMessage(HcAvailability.AVAILABLE))
    }

    @Test fun `the store page is the Play Store's with the onboarding url, then any store, then the web`() {
        val (play, store, web) = healthConnectStoreIntents("org.openstrap.app")
        val market = "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"
        assertEquals(Intent.ACTION_VIEW, play.action)
        assertEquals(Uri.parse(market), play.data)
        assertEquals("com.android.vending", play.`package`)
        assertTrue(play.getBooleanExtra("overlay", false))
        assertEquals("org.openstrap.app", play.getStringExtra("callerId"))
        assertEquals(Uri.parse(market), store.data)
        assertNull(store.`package`)
        assertEquals(
            Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"),
            web.data,
        )
    }

    // Started from the activity, as the page does: a start from the application would need a new task.
    private fun activity() = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    @Test fun `with no store app the web page opens`() {
        val activity = activity()
        shadowOf(app).checkActivities(true)
        val web = healthConnectStoreIntents(app.packageName).last()
        canOpen(web)
        assertTrue(installHealthConnect(activity))
        assertEquals(web.data, shadowOf(activity).nextStartedActivity.data)
    }

    @Test fun `with the Play Store there its page opens`() {
        val activity = activity()
        shadowOf(app).checkActivities(true)
        val (play, _, web) = healthConnectStoreIntents(app.packageName)
        canOpen(play)
        canOpen(web)
        assertTrue(installHealthConnect(activity))
        assertEquals("com.android.vending", shadowOf(activity).nextStartedActivity.`package`)
    }

    @Test fun `with nothing to open it, it says so`() {
        val activity = activity()
        shadowOf(app).checkActivities(true)
        assertFalse(installHealthConnect(activity))
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("Couldn't open the Play Store.", HC_STORE_FAILED)
    }

    @Test fun `the page shows the install button only where Health Connect can be installed`() {
        var availability by mutableStateOf(HcAvailability.INSTALLABLE)
        var installs = 0
        rules.compose.setContent {
            HealthConnectPage(
                HealthConnectAuto(), availability, PaddingValues(),
                onAuto = {}, onCopyNow = {}, onRestore = {}, onOpen = {}, onInstall = { installs++ }, onBack = {},
            )
        }
        rules.compose.onNodeWithText("Install Health Connect").assertIsDisplayed().performClick()
        rules.compose.onNodeWithText(HC_INSTALL_NOTE).assertIsDisplayed()
        assertEquals(1, installs)
        for (other in listOf(HcAvailability.AVAILABLE, HcAvailability.UNAVAILABLE)) {
            availability = other
            rules.compose.waitForIdle()
            assertTrue(rules.compose.onAllNodesWithText("Install Health Connect").fetchSemanticsNodes().isEmpty())
        }
    }

    /** An installed activity that opens [intent]: in its own package when it names one, else a browser's. */
    private fun canOpen(intent: Intent) {
        val component = ComponentName(intent.`package` ?: "org.example.browser", "Opener")
        val packages = shadowOf(app.packageManager)
        packages.addActivityIfNotPresent(component)
        packages.addIntentFilterForActivity(
            component,
            IntentFilter(intent.action).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme(intent.data!!.scheme)
            },
        )
    }
}
