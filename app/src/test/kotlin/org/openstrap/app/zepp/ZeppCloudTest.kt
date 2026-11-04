package org.openstrap.app.zepp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.settings.ZeppAccount
import java.net.URLEncoder

class ZeppCloudTest {
    // Made-up values in the shape the device list returns.
    private val list = """
        {"items":[
          {"deviceId":"AA11","macAddress":"D1:22:33:44:55:66","deviceType":0,"deviceSource":8,"bindingStatus":1,
           "firmwareVersion":"1.0.0","additionalInfo":"{\"auth_key\":\"0x00112233445566778899aabbccddeeff\",\"sn\":\"OTHER1\",\"productId\":42}"},
          {"deviceId":"BB22","macAddress":"c7:2b:1f:00:00:01","deviceType":0,"deviceSource":10289411,"bindingStatus":0,"activeStatus":0,
           "additionalInfo":"{\"sn\":\"OLD\",\"productId\":157}"},
          {"deviceId":"CC33","macAddress":"C7:2B:1F:00:00:02","deviceType":0,"deviceSource":10289411,"bindingStatus":1,"activeStatus":1,
           "firmwareVersion":"3.1.5","additionalInfo":"{\"auth_key\":\"ffeeddccbbaa99887766554433221100\",\"sn\":\"HELIO1\",\"productId\":157,\"hardwareVersion\":\"0.1.0\"}"},
          {"deviceId":"DD44","macAddress":"not-a-mac","deviceSource":10289411,"additionalInfo":"{}"}
        ]}
    """.trimIndent()

    @Test
    fun parsesItemsAndAdditionalInfo() {
        val devices = ZeppCloud.parseDeviceList(list)
        assertEquals(3, devices.size) // the one without a valid MAC is dropped
        val other = devices[0]
        assertEquals("00112233445566778899aabbccddeeff", other.authKey)
        assertEquals(42, other.productId)
        assertFalse(other.isHelioStrap)

        val strap = devices[2]
        assertEquals("CC33", strap.deviceId)
        assertEquals("C7:2B:1F:00:00:02", strap.macAddress)
        assertEquals("ffeeddccbbaa99887766554433221100", strap.authKey)
        assertEquals("HELIO1", strap.serial)
        assertEquals("3.1.5", strap.firmwareVersion)
        assertEquals("0.1.0", strap.hardwareVersion)
        assertTrue(strap.isHelioStrap)
        assertFalse(strap.china)

        assertEquals("C7:2B:1F:00:00:01", devices[1].macAddress) // upper-cased
        assertNull(devices[1].authKey)
    }

    @Test
    fun picksBoundHelioStrapWithKey() {
        assertEquals("CC33", ZeppCloud.pickHelioStrap(ZeppCloud.parseDeviceList(list))?.deviceId)
    }

    @Test
    fun chinaSourceAndObjectAdditionalInfo() {
        val body = """{"items":[{"deviceId":"X","macAddress":"C7:2B:1F:55:24:AB","deviceSource":10289410,"bindingStatus":1,
            "additionalInfo":{"auth_key":"00112233445566778899AABBCCDDEEFF"}}]}"""
        val strap = ZeppCloud.pickHelioStrap(ZeppCloud.parseDeviceList(body))!!
        assertTrue(strap.china)
        assertEquals("00112233445566778899aabbccddeeff", strap.authKey)
    }

    @Test
    fun noStrapAndEmptyLists() {
        assertNull(ZeppCloud.pickHelioStrap(ZeppCloud.parseDeviceList("""{"items":[]}""")))
        assertTrue(ZeppCloud.parseDeviceList("{}").isEmpty())
        assertNull(ZeppCloud.pickHelioStrap(ZeppCloud.parseDeviceList(list).take(1)))
    }

    @Test(expected = ZeppCloudException::class)
    fun rejectsNonJson() {
        ZeppCloud.parseDeviceList("<html>")
    }

    @Test
    fun badKeysAreIgnored() {
        assertNull(ZeppCloud.normalizeKey("1234"))
        assertNull(ZeppCloud.normalizeKey("zz112233445566778899aabbccddeeff"))
        assertNull(ZeppCloud.normalizeKey(null))
    }

    @Test
    fun sessionFromLoginInfoCookie() {
        val info = """{"token_info":{"app_token":"tok-abc","user_id":"1234567"},"user_info":{}}"""
        val cookies = "_ga=GA1.2; hm-user-login-info=${URLEncoder.encode(info, "UTF-8")}; lang=en"
        assertEquals(ZeppSession("tok-abc", "1234567"), ZeppCloud.sessionFromCookies(cookies))
    }

    @Test
    fun sessionFromPlainCookies() {
        assertEquals(ZeppSession("tok-xyz", "42"), ZeppCloud.sessionFromCookies("apptoken=tok-xyz; userid=42"))
    }

    @Test
    fun noSessionWithoutBoth() {
        assertNull(ZeppCloud.sessionFromCookies(null))
        assertNull(ZeppCloud.sessionFromCookies("apptoken=tok-xyz"))
        assertNull(ZeppCloud.sessionFromCookies("hm-user-login-info=%7B%7D"))
    }

    @Test
    fun sessionKeepsLoginToken() {
        val info = """{"token_info":{"app_token":"tok-abc","user_id":"1234567","login_token":"login-xyz","app_ttl":7776000}}"""
        val session = ZeppCloud.sessionFromCookies("hm-user-login-info=${URLEncoder.encode(info, "UTF-8")}")!!
        assertEquals(ZeppSession("tok-abc", "1234567", "login-xyz", 7_776_000), session)
        val account = session.toAccount("https://api-mifit.zepp.com/", china = false, nowMs = 1_000)
        assertEquals("login-xyz", account.loginToken)
        assertEquals(1_000L, account.appTokenAtMs)
        assertEquals(7_776_000L, account.appTtlSeconds)
    }

    private val signedIn = ZeppAccount("app", "42", "https://api-mifit.zepp.com/", false, "login", appTokenAtMs = 0, appTtlSeconds = 100)

    @Test
    fun refreshesAfterHalfTheAppTokenLifetime() {
        // Zepp's A3 rule: (now − lu_app_ttl)/1000 > app_ttl/2 (codemap 08 §1.4).
        assertFalse(ZeppCloud.needsAppTokenRefresh(signedIn, nowMs = 50_000, rejected = false))
        assertFalse(ZeppCloud.needsAppTokenRefresh(signedIn, nowMs = 50_999, rejected = false))
        assertTrue(ZeppCloud.needsAppTokenRefresh(signedIn, nowMs = 51_000, rejected = false))
    }

    @Test
    fun refreshesWhenRejectedButNeverWithoutLoginToken() {
        assertTrue(ZeppCloud.needsAppTokenRefresh(signedIn, nowMs = 0, rejected = true))
        // Lifetime unknown: only a rejection triggers it.
        val noTtl = signedIn.copy(appTokenAtMs = null, appTtlSeconds = null)
        assertFalse(ZeppCloud.needsAppTokenRefresh(noTtl, nowMs = Long.MAX_VALUE / 2, rejected = false))
        assertTrue(ZeppCloud.needsAppTokenRefresh(noTtl, nowMs = 0, rejected = true))
        // A typed-in token or a cookie without a login token can't be renewed: sign in again.
        val typed = signedIn.copy(loginToken = null)
        assertFalse(ZeppCloud.needsAppTokenRefresh(typed, nowMs = Long.MAX_VALUE / 2, rejected = false))
        assertFalse(ZeppCloud.needsAppTokenRefresh(typed, nowMs = 0, rejected = true))
        assertFalse(ZeppCloud.needsAppTokenRefresh(signedIn.copy(loginToken = " "), nowMs = 0, rejected = true))
    }

    @Test
    fun appTokensRequestAndReply() {
        assertEquals(
            listOf("login_token", "os_version", "source", "dn"),
            ZeppCloud.appTokensQuery("login").keys.toList(),
        )
        assertEquals("com.huami.midong:10.10.5-play:151997", ZeppCloud.appTokensQuery("login")["source"])
        val r = ZeppCloud.parseAppTokens("""{"result":"ok","token_info":{"app_token":"new-app","user_id":"42","app_ttl":7776000}}""")
        assertEquals(AppTokenRefresh("new-app", "42", 7_776_000), r)
        val renewed = ZeppCloud.withRefresh(signedIn, r, nowMs = 9_000)
        assertEquals(signedIn.copy(appToken = "new-app", appTokenAtMs = 9_000, appTtlSeconds = 7_776_000), renewed)
        // Without a TTL in the reply the old one stays.
        assertEquals(100L, ZeppCloud.withRefresh(signedIn, AppTokenRefresh("x", null, null), 0).appTtlSeconds)
    }

    @Test
    fun appTokensFailures() {
        val refused = runCatching { ZeppCloud.parseAppTokens("""{"result":"invalid_token"}""") }.exceptionOrNull() as ZeppCloudException
        assertTrue(refused.rejected)
        val empty = runCatching { ZeppCloud.parseAppTokens("""{"result":"ok","token_info":{}}""") }.exceptionOrNull() as ZeppCloudException
        assertTrue(empty.rejected)
        val garbage = runCatching { ZeppCloud.parseAppTokens("<html>") }.exceptionOrNull() as ZeppCloudException
        assertFalse(garbage.rejected)
    }

    @Test
    fun toStringHidesSecrets() {
        assertFalse(ZeppSession("secret-token", "1").toString().contains("secret-token"))
        assertFalse(ZeppSession("secret-token", "1", "secret-login").toString().contains("secret-login"))
        assertFalse(signedIn.copy(appToken = "secret-app", loginToken = "secret-login").toString().contains("secret"))
        val strap = ZeppCloud.parseDeviceList(list)[2]
        assertFalse(strap.toString().contains(strap.authKey!!))
    }
}
