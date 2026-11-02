package org.openstrap.app.ui.zepp

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import org.openstrap.app.data.catching
import org.openstrap.app.ui.components.pressClickable
import org.openstrap.app.ui.theme.ControlSurface
import org.openstrap.app.ui.glow.GlowLayout
import org.openstrap.app.ui.glow.GlowType
import org.openstrap.app.ui.glow.PageButton
import org.openstrap.app.ui.glow.PageNote
import org.openstrap.app.ui.glow.PagePanel
import org.openstrap.app.ui.glow.PageProgress
import org.openstrap.app.ui.glow.PageTextButton
import org.openstrap.app.ui.glow.ROW_CARET_ALPHA
import org.openstrap.app.ui.glow.RowCaretGap
import org.openstrap.app.ui.glow.TapCaret
import org.openstrap.app.ui.theme.MetricColors
import org.openstrap.app.ui.theme.AppIcons
import org.openstrap.app.zepp.BoundDevice
import org.openstrap.app.zepp.DeviceListing
import org.openstrap.app.zepp.ZeppCloud
import org.openstrap.app.zepp.ZeppCloudException

private sealed interface SignInStep {
    data object SignIn : SignInStep
    data object Fetching : SignInStep
    data class Found(val listing: DeviceListing) : SignInStep
    /** [thirdParty]: the user picked Google, Apple and the like, which can't finish inside a WebView. */
    data class Failed(val message: String, val thirdParty: Boolean = false) : SignInStep
}

/** Gadgetbridge's walkthrough for reading the auth key on a computer, for accounts that can't sign in here. */
const val COMPUTER_KEY_GUIDE_URL = "https://gadgetbridge.org/basics/pairing/huami-xiaomi-server/#on-a-computer"

/** Sign-in providers that refuse to run in an embedded WebView (Google blocks it outright). */
private val THIRD_PARTY_HOSTS = mapOf(
    "accounts.google.com" to "Google",
    "appleid.apple.com" to "Apple",
    "www.facebook.com" to "Facebook",
    "m.facebook.com" to "Facebook",
    "account.xiaomi.com" to "Xiaomi",
    "access.line.me" to "LINE",
)

/**
 * Full-screen Zepp sign-in. The user signs in on watchface.zepp.com inside a WebView (OpenStrap never sees
 * the password); the app then reads the app token and user ID from that site's cookies, lists the account's
 * devices once, and shows the Helio Strap it found for the user to confirm.
 *
 * [onConfirm] gets the listing and the chosen strap (null when the user only keeps the sign-in). With
 * [requireKey], a strap without an auth key can't be confirmed with [confirmLabel], only kept as a sign-in.
 */
// TODO (deferred): a cloud first bind, so a new or reset strap can be set up with only OpenStrap and a Zepp
// account, without the Zepp app: AuthInfo 00, ECDH 02, binds.json digest, signature 03, then normal auth. Steps
// and byte layouts are in zepp-alt analysis/codemap/08-cloud-and-account.md §2 (first-bind checklist). Untried;
// whether the server signs for OpenStrap's appid is unknown.
@Composable
fun ZeppSignInDialog(
    preferredApiBase: String?,
    confirmLabel: String,
    requireKey: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (DeviceListing, BoundDevice?) -> Unit,
) {
    var step by remember { mutableStateOf<SignInStep>(SignInStep.SignIn) }
    var attempt by remember { mutableIntStateOf(0) }

    // Watch the cookies while the user signs in; the site is a single-page app, so page loads alone don't tell us.
    LaunchedEffect(attempt) {
        while (step == SignInStep.SignIn) {
            val session = ZeppCloud.sessionFromCookies(currentCookies())
            if (session != null) {
                step = SignInStep.Fetching
                step = catching { SignInStep.Found(ZeppCloud.listDevices(session, preferredApiBase)) }
                    .getOrElse {
                        Log.w("OpenStrap", "zepp: device list failed: $it")
                        SignInStep.Failed(deviceListError(it))
                    }
                break
            }
            delay(1000)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Black to the screen's edges, the page itself in one centred column like every other page (spec 2.4).
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = GlowLayout.PageMaxWidth).fillMaxSize()) {
                // A page's top: the round button (a close, since this sits over the page it came from) and the title.
                Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = GlowLayout.Gutter), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(ControlSurface).clickable(onClickLabel = "Close", onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) { Icon(AppIcons.X, "Close", Modifier.size(20.dp), tint = Color.White) }
                    Spacer(Modifier.width(12.dp))
                    Text("Sign in with Zepp", color = Color.White, style = GlowType.pageTitle, maxLines = 1, modifier = Modifier.weight(1f))
                    if (step is SignInStep.SignIn) PageTextButton("Reset", { clearZeppCookies(); attempt++ })
                }
                when (val s = step) {
                    SignInStep.SignIn -> {
                        Column(Modifier.padding(horizontal = GlowLayout.Gutter, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Use the email and password of the Zepp account your strap is linked to. Google, Apple, " +
                                    "Facebook and Xiaomi sign-in don't work here.",
                                color = Color.White, style = GlowType.body,
                            )
                            PageNote(
                                "This is Zepp's own page, so OpenStrap never sees your password. It reads only the app token and " +
                                    "user ID, then asks Zepp once for your devices.",
                            )
                        }
                        key(attempt) {
                            ZeppWebView(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)) { provider ->
                                step = SignInStep.Failed(
                                    "$provider sign-in doesn't work inside OpenStrap, because $provider blocks sign-in in embedded pages.\n\n" +
                                        "Open the Zepp app and add an email and password to your account in its account settings. " +
                                        "Then sign in here with that email. Or get the auth key on a computer.",
                                    thirdParty = true,
                                )
                            }
                        }
                    }
                    SignInStep.Fetching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            PageProgress(modifier = Modifier.width(160.dp))
                            Text("Signed in. Looking up your devices", color = Color.White, style = GlowType.body)
                        }
                    }
                    is SignInStep.Failed -> Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(GlowLayout.Gutter),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PagePanel {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(AppIcons.WarningCircle, null, Modifier.size(20.dp), tint = MetricColors.warn)
                                Text(if (s.thirdParty) "Use email sign-in" else "Couldn't get your devices", color = Color.White, style = GlowType.cardTitle, maxLines = 1)
                            }
                            Text(s.message, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body)
                        }
                        PageButton(
                            if (s.thirdParty) "Sign in with email" else "Sign in again",
                            { clearZeppCookies(); step = SignInStep.SignIn; attempt++ }, Modifier.fillMaxWidth(), primary = true,
                        )
                        ComputerGuideButton()
                        PageButton("Close", onDismiss, Modifier.fillMaxWidth())
                    }
                    is SignInStep.Found -> FoundStrap(
                        s.listing, confirmLabel, requireKey,
                        onConfirm = { strap -> clearZeppCookies(); onConfirm(s.listing, strap) },
                        onOtherAccount = { clearZeppCookies(); step = SignInStep.SignIn; attempt++ },
                    )
                }
            }
        }
    }
}

@Composable
private fun FoundStrap(
    listing: DeviceListing,
    confirmLabel: String,
    requireKey: Boolean,
    onConfirm: (BoundDevice?) -> Unit,
    onOtherAccount: () -> Unit,
) {
    val strap = listing.helioStrap
    val canConfirm = strap != null && (strap.authKey != null || !requireKey)
    var showKey by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(GlowLayout.Gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PagePanel(title = if (strap != null) "Found your Helio Strap" else "No Helio Strap on this account") {
            if (strap == null) {
                Text(
                    if (listing.devices.isEmpty()) {
                        "This account has no devices. Link the strap in the Zepp app first. If you did, it's on another " +
                            "account: sign in with the email added to the account you use in the Zepp app."
                    } else {
                        "This account has ${listing.devices.size} device(s), but none is an Amazfit Helio Strap. Link the strap " +
                            "in the Zepp app, then sign in again."
                    },
                    color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body,
                )
            } else {
                Line("Bluetooth address", strap.macAddress)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Auth key", color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, modifier = Modifier.weight(1f))
                    Text(
                        when {
                            strap.authKey == null -> "Not on the account"
                            showKey -> strap.authKey
                            else -> "•".repeat(8) + strap.authKey.takeLast(4)
                        },
                        color = Color.White, style = GlowType.body.copy(fontFamily = FontFamily.Monospace),
                    )
                    if (strap.authKey != null) PageTextButton(if (showKey) "Hide" else "Show", { showKey = !showKey })
                }
                strap.serial?.let { Line("Serial", it) }
                strap.firmwareVersion?.let { Line("Firmware", it) }
                Line("Region", if (strap.china) "China" else "Global")
                if (strap.authKey == null) {
                    PageNote(
                        "Zepp didn't return an auth key for this strap. Open the Zepp app and check the strap is still linked " +
                            "(unlink and link it again if needed), then sign in here again.",
                        color = MetricColors.warn,
                    )
                }
            }
            Line("Zepp user", listing.session.userId)
        }
        if (canConfirm && requireKey) {
            PageNote("Keep the strap on its charger near the phone. Don't unlink it in the Zepp app: that wipes this key from the strap.")
        }
        PageNote("The app token and user ID stay on this phone only, for firmware checks.")
        PageButton(if (canConfirm) confirmLabel else "Keep sign-in only", { onConfirm(strap) }, Modifier.fillMaxWidth(), primary = true)
        PageButton("Use another account", onOtherAccount, Modifier.fillMaxWidth())
        if (!canConfirm && requireKey) ComputerGuideButton()
    }
}

@Composable
private fun Line(label: String, value: String) {
    // The label is measured first and keeps to one line (spec 2.3), and the value takes what's left, ellipsized last.
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.body, maxLines = 1, modifier = Modifier.padding(end = 12.dp))
        Text(
            value, color = Color.White, style = GlowType.body, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f),
        )
    }
}

/** Opens Gadgetbridge's computer walkthrough in the browser. */
@Composable
fun ComputerGuideButton(modifier: Modifier = Modifier.fillMaxWidth()) {
    val uri = LocalUriHandler.current
    OptionButton("Can't sign in with email?", "Get the key on a computer", modifier) { uri.openUri(COMPUTER_KEY_GUIDE_URL) }
}

/**
 * A secondary choice: a tile on the Control surface (spec 2.4) with a [caption] in Secondary over the [label] in Body,
 * and the tap caret at its end, so it reads as something that opens.
 */
@Composable
fun OptionButton(caption: String, label: String, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
    Row(
        modifier.clip(GlowLayout.TileShape).background(ControlSurface).pressClickable(onClick, GlowLayout.TileShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(caption, color = Color.White.copy(alpha = GlowType.SECONDARY_ALPHA), style = GlowType.secondary)
            Text(label, color = Color.White, style = GlowType.body)
        }
        Spacer(Modifier.width(RowCaretGap))
        TapCaret(ROW_CARET_ALPHA)
    }
}

private fun deviceListError(e: Throwable): String = when {
    e is ZeppCloudException && e.rejected ->
        "Zepp didn't accept the sign-in. It may have expired, or the account is in another region. Sign in again."
    e is ZeppCloudException -> "${e.message}. Zepp may be having trouble. Try again in a few minutes."
    else -> "Couldn't reach Zepp's servers. Check your internet connection and try again."
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ZeppWebView(modifier: Modifier, onThirdParty: (provider: String) -> Unit) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.setSupportMultipleWindows(false)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    // Keep every https page inside the WebView so the sign-in stays here, except third-party sign-in,
                    // which can't finish in a WebView: stop it and explain instead of showing the provider's error.
                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                        THIRD_PARTY_HOSTS[request.url.host]?.let { onThirdParty(it); return true }
                        return request.url.scheme != "https"
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        THIRD_PARTY_HOSTS[url?.let(android.net.Uri::parse)?.host]?.let { view.stopLoading(); onThirdParty(it) }
                    }
                }
                loadUrl(ZeppCloud.SIGN_IN_URL)
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
    )
}

private fun currentCookies(): String {
    val cm = CookieManager.getInstance()
    return ZeppCloud.COOKIE_URLS.mapNotNull { cm.getCookie(it) }.joinToString("; ")
}

/** Drops the WebView's Zepp sign-in once we have what we need (the token is kept in settings instead). */
private fun clearZeppCookies() {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
}
