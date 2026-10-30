package org.openstrap.app.zepp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.openstrap.app.data.settings.ZeppAccount
import org.openstrap.app.firmware.StrapIdentity
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.openstrap.app.R
import org.openstrap.app.tr

/**
 * The user's own Zepp sign-in, read from the watchface.zepp.com cookies after they signed in there. [loginToken] and
 * [appTtlSeconds] are kept when the cookie's `token_info` carries them, so the app token can be renewed (A3).
 */
data class ZeppSession(val appToken: String, val userId: String, val loginToken: String? = null, val appTtlSeconds: Long? = null) {
    override fun toString() = "ZeppSession(userId=$userId, appToken=…, loginToken=${if (loginToken == null) "none" else "…"})"

    /** The account to store, with the app token stamped as obtained [nowMs]. */
    fun toAccount(apiBase: String, china: Boolean, nowMs: Long) =
        ZeppAccount(appToken, userId, apiBase, china, loginToken, nowMs, appTtlSeconds)
}

/** What A3 `v1/client/app_tokens` returns (codemap 08 §1.4). */
data class AppTokenRefresh(val appToken: String, val userId: String?, val appTtlSeconds: Long?) {
    override fun toString() = "AppTokenRefresh(userId=$userId, appTtlSeconds=$appTtlSeconds, appToken=…)"
}

/** One device bound to the account, as the device list reports it. */
data class BoundDevice(
    val deviceId: String,
    val macAddress: String,
    val authKey: String?,
    val deviceSource: Int,
    val productId: Int?,
    val serial: String?,
    val firmwareVersion: String?,
    val hardwareVersion: String?,
    val bindingStatus: Int,
    val activeStatus: Int?,
) {
    val isHelioStrap: Boolean
        get() = deviceSource == StrapIdentity.DEVICE_SOURCE_GLOBAL || deviceSource == StrapIdentity.DEVICE_SOURCE_CHINA ||
            productId == StrapIdentity.PRODUCT_ID
    val china: Boolean get() = deviceSource == StrapIdentity.DEVICE_SOURCE_CHINA

    override fun toString() = "BoundDevice(deviceId=$deviceId, mac=$macAddress, source=$deviceSource, authKey=${if (authKey == null) "none" else "…"})"
}

/** A device list fetched with [session] from [apiBase]. */
data class DeviceListing(val session: ZeppSession, val apiBase: String, val devices: List<BoundDevice>) {
    /** The Helio Strap to use: a currently bound one with a key first, then the most recent. */
    val helioStrap: BoundDevice? get() = ZeppCloud.pickHelioStrap(devices)
}

class ZeppCloudException(message: String, val rejected: Boolean = false) : IOException(message)

/**
 * Reads the account's bound devices with the user's own app token. This is the only call made; it
 * sends the token and user ID, nothing about the strap or its data.
 */
object ZeppCloud {
    // Same identity as the firmware check.
    private const val APP_ID = "428135909242707968"
    private const val APP_VERSION_NAME = "10.9.0-play"
    private const val APP_VERSION_CODE = "151983"
    private const val APP_NAME = "com.huami.midong"

    /**
     * Zepp's universal login, set up as watchface.zepp.com's sign-in: after the password it returns to that
     * site, whose cookies hold the token we need. The page follows the phone's language.
     */
    const val SIGN_IN_URL = "https://user.zepp.com/universalLogin/index.html#/login" +
        "?project_name=watchface" +
        "&project_redirect_uri=https%3A%2F%2Fwatchface.zepp.com%2Fcreate" +
        "&platform_app=com.huami.webapp"
    val COOKIE_URLS = listOf("https://watchface.zepp.com", "https://zepp.com", "https://user.zepp.com", "https://user.huami.com")

    /** Accounts live in one region; the global host usually answers for all, the others are tried if it doesn't. */
    val API_HOSTS = listOf(
        "https://api-mifit.zepp.com/",
        "https://api-mifit-us2.zepp.com/",
        "https://api-mifit-us3.zepp.com/",
        "https://api-mifit-de2.zepp.com/",
        "https://api-mifit-sg2.zepp.com/",
    )

    /**
     * Finds the app token and user ID in a `Cookie`-style string ("a=b; c=d"). watchface.zepp.com keeps them in
     * `hm-user-login-info` (URL-encoded JSON with `token_info.app_token` / `token_info.user_id`), or in plain
     * `apptoken` / `userid` cookies.
     */
    fun sessionFromCookies(cookies: String?): ZeppSession? {
        if (cookies.isNullOrBlank()) return null
        val map = cookies.split(';').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.toMap()

        map["hm-user-login-info"]?.let { raw ->
            val json = decodeJson(raw) ?: return@let
            val info = json.optJSONObject("token_info") ?: json
            val token = info.optString("app_token").ifBlank { info.optString("apptoken") }
            val user = info.optString("user_id").ifBlank { info.optString("userid") }
            val login = info.optString("login_token").ifBlank { null }
            val ttl = info.optLong("app_ttl", 0).takeIf { it > 0 }
            if (token.isNotBlank() && user.isNotBlank()) return ZeppSession(token, user, login, ttl)
        }
        val token = map["apptoken"]?.let(::urlDecode).orEmpty()
        val user = map["userid"]?.let(::urlDecode).orEmpty()
        return if (token.isNotBlank() && user.isNotBlank()) ZeppSession(token, user) else null
    }

    private fun urlDecode(s: String): String = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun decodeJson(raw: String): JSONObject? {
        var s = raw.trim().removeSurrounding("\"")
        repeat(3) { // Sometimes encoded more than once.
            runCatching { return JSONObject(s) }
            val next = urlDecode(s)
            if (next == s) return null
            s = next
        }
        return null
    }

    /** Parses the `users/{id}/devices` reply: `{"items":[{…, "additionalInfo":"{\"auth_key\":…}"}]}`. */
    fun parseDeviceList(body: String): List<BoundDevice> {
        val root = runCatching { JSONObject(body) }.getOrElse { throw ZeppCloudException(tr(R.string.zepp_cloud_unexpected_reply_from_zepp)) }
        val items = root.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val mac = item.optString("macAddress").uppercase()
            if (!MAC.matches(mac)) return@mapNotNull null
            // additionalInfo is a JSON string; tolerate an object too.
            val extra = item.optJSONObject("additionalInfo")
                ?: item.optString("additionalInfo").takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: JSONObject()
            BoundDevice(
                deviceId = item.optString("deviceId"),
                macAddress = mac,
                authKey = normalizeKey(extra.optString("auth_key")),
                deviceSource = item.optInt("deviceSource"),
                productId = extra.optInt("productId", -1).takeIf { it >= 0 },
                serial = extra.optString("sn").ifBlank { extra.optString("SN") }.ifBlank { null },
                firmwareVersion = item.optString("firmwareVersion").ifBlank { null },
                hardwareVersion = extra.optString("hardwareVersion").ifBlank { null },
                bindingStatus = item.optInt("bindingStatus"),
                activeStatus = if (item.has("activeStatus")) item.optInt("activeStatus") else null,
            )
        }
    }

    /** Prefers a bound strap with a usable key, then the most recently active one. */
    fun pickHelioStrap(devices: List<BoundDevice>): BoundDevice? =
        devices.filter { it.isHelioStrap }
            .sortedWith(compareByDescending<BoundDevice> { it.authKey != null }.thenByDescending { it.bindingStatus == 1 }.thenByDescending { it.activeStatus == 1 })
            .firstOrNull()

    /** "0x" prefix, separators and case are dropped; anything not 16 bytes of hex is ignored. */
    fun normalizeKey(raw: String?): String? {
        val hex = raw.orEmpty().trim().removePrefix("0x").removePrefix("0X").filter { it.isLetterOrDigit() }.lowercase()
        return hex.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    private val MAC = Regex("^([0-9A-F]{2}:){5}[0-9A-F]{2}$")

    /**
     * Whether to get a new app token with the login token, by Zepp's A3 rule (codemap 08 §1.4): when the server
     * [rejected] the current one, or once half its lifetime has passed. Never without a login token; without a known
     * lifetime, only on rejection.
     */
    fun needsAppTokenRefresh(account: ZeppAccount, nowMs: Long, rejected: Boolean): Boolean {
        if (account.loginToken.isNullOrBlank()) return false
        if (rejected) return true
        val at = account.appTokenAtMs ?: return false
        val ttl = account.appTtlSeconds ?: return false
        return (nowMs - at) / 1000 > ttl / 2
    }

    /** A3's query: the passport params, not the common ones (codemap 08 §1.2, §1.4). */
    fun appTokensQuery(loginToken: String): LinkedHashMap<String, String> = linkedMapOf(
        "login_token" to loginToken,
        "os_version" to "vnull",
        "source" to "$APP_NAME:$APP_VERSION_NAME:$APP_VERSION_CODE",
        "dn" to "api-mifit.zepp.com,api-user.zepp.com",
    )

    /** Parses A3's reply (`t7s`): `result` must be ok and `token_info.app_token` set (codemap 08 §1.2). */
    fun parseAppTokens(body: String): AppTokenRefresh {
        val root = runCatching { JSONObject(body) }.getOrElse { throw ZeppCloudException(tr(R.string.zepp_cloud_unexpected_reply_when_renewing)) }
        val result = root.optString("result")
        if (result.isNotEmpty() && result != "ok") throw ZeppCloudException(tr(R.string.zepp_cloud_zepp_refused_to_renew, result), rejected = true)
        val info = root.optJSONObject("token_info") ?: JSONObject()
        val token = info.optString("app_token").ifBlank { null }
            ?: throw ZeppCloudException(tr(R.string.zepp_cloud_zepp_didn_t_renew), rejected = true)
        return AppTokenRefresh(
            token,
            info.optString("user_id").ifBlank { root.optString("user_id") }.ifBlank { null },
            info.optLong("app_ttl", 0).takeIf { it > 0 },
        )
    }

    /** [account] with a refreshed app token, stamped as obtained [nowMs]; the login token stays. */
    fun withRefresh(account: ZeppAccount, r: AppTokenRefresh, nowMs: Long): ZeppAccount = account.copy(
        appToken = r.appToken,
        userId = r.userId ?: account.userId,
        appTokenAtMs = nowMs,
        appTtlSeconds = r.appTtlSeconds ?: account.appTtlSeconds,
    )

    /** Gets a new app token with [account]'s login token (A3, `GET v1/client/app_tokens`, codemap 08 §1.4). */
    suspend fun refreshAppToken(account: ZeppAccount, nowMs: Long = System.currentTimeMillis()): ZeppAccount = withContext(Dispatchers.IO) {
        val login = account.loginToken?.takeIf { it.isNotBlank() } ?: throw ZeppCloudException(tr(R.string.zepp_cloud_no_login_token_to), rejected = true)
        val base = account.apiBase.trim().let { if (it.endsWith("/")) it else "$it/" }
        require(base.startsWith("https://")) { tr(R.string.common_the_server_address_must) }
        val query = appTokensQuery(login).entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val conn = URL("${base}v1/client/app_tokens?$query").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            setCommonHeaders(conn, Locale.getDefault(), appToken = null)
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            when (code) {
                in 200..299 -> withRefresh(account, parseAppTokens(body), nowMs)
                401, 403 -> throw ZeppCloudException(tr(R.string.zepp_cloud_zepp_refused_to_renew_2, code), rejected = true)
                else -> throw ZeppCloudException(tr(R.string.zepp_cloud_renewing_the_zepp_sign, code))
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Tries [preferredBase] first, then the regional hosts, until one lists at least one device. */
    suspend fun listDevices(session: ZeppSession, preferredBase: String?): DeviceListing = withContext(Dispatchers.IO) {
        val bases = (listOfNotNull(preferredBase?.trim()?.takeIf { it.startsWith("https://") }?.let { if (it.endsWith("/")) it else "$it/" }) + API_HOSTS).distinct()
        var lastError: Exception? = null
        var empty: DeviceListing? = null
        for (base in bases) {
            try {
                val devices = parseDeviceList(get(base, session))
                val listing = DeviceListing(session, base, devices)
                if (devices.isNotEmpty()) return@withContext listing
                if (empty == null) empty = listing
            } catch (e: ZeppCloudException) {
                lastError = e
                if (e.rejected) break
            } catch (e: IOException) {
                lastError = e
            }
        }
        empty ?: throw (lastError ?: ZeppCloudException(tr(R.string.zepp_cloud_couldn_t_reach_zepp)))
    }

    private fun get(base: String, session: ZeppSession): String {
        val locale = Locale.getDefault()
        val params = linkedMapOf(
            "enableMultiDeviceOnMultiType" to "true",
            "enableMultiDevice" to "true",
            "userid" to session.userId,
            "appid" to APP_ID,
            "channel" to "play",
            "device" to "android_${android.os.Build.VERSION.SDK_INT}",
            "device_type" to "android_phone",
            "cv" to "${APP_VERSION_CODE}_$APP_VERSION_NAME",
            "v" to "2.0",
            "lang" to "${locale.language}_${locale.country}",
            "country" to locale.country,
            "timezone" to TimeZone.getDefault().id,
        )
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val user = URLEncoder.encode(session.userId, "UTF-8")
        val conn = URL("${base}users/$user/devices?$query").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            setCommonHeaders(conn, locale, session.appToken)
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return when (code) {
                in 200..299 -> body
                401, 403 -> throw ZeppCloudException(tr(R.string.zepp_cloud_zepp_rejected_the_sign, code), rejected = true)
                else -> throw ZeppCloudException(tr(R.string.zepp_cloud_zepp_s_device_list, code))
            }
        } finally {
            conn.disconnect()
        }
    }

    /** The headers every call here sends; [appToken] is left out for A3, which authenticates with the login token. */
    private fun setCommonHeaders(conn: HttpURLConnection, locale: Locale, appToken: String?) {
        appToken?.let { conn.setRequestProperty("apptoken", it) }
        conn.setRequestProperty("appname", APP_NAME)
        conn.setRequestProperty("appplatform", "android_phone")
        conn.setRequestProperty("channel", "play")
        conn.setRequestProperty("cv", "${APP_VERSION_CODE}_$APP_VERSION_NAME")
        conn.setRequestProperty("v", "2.0")
        conn.setRequestProperty("lang", "${locale.language}_${locale.country}")
        conn.setRequestProperty("country", locale.country)
        conn.setRequestProperty("timezone", TimeZone.getDefault().id)
        conn.setRequestProperty("hm-privacy-diagnostics", "false")
        conn.setRequestProperty("hm-privacy-ceip", "false")
        conn.setRequestProperty("X-Request-Id", UUID.randomUUID().toString())
    }
}
