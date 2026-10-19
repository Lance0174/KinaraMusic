/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.account

import com.kinara.music.online.OnlineTransport.TransportResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class BiliApiTest {
    private val signingKeys = """{"wbi_img":{"img_url":"https://i.test/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i.test/4932caff0ff746eab6f01bf08b70ac45.png"}}"""
    private fun response(data: String) = TransportResponse(200, """{"code":0,"data":$data}""")

    @Test fun codeZeroVoucherIsARiskControlErrorNotAnEmptySearch() = runTest {
        val api = BiliApi({ url, _ -> when {
            url.contains("/nav") -> response(signingKeys)
            url.contains("finger/spi") -> response("{}")
            else -> response("""{"v_voucher":"private-challenge"}""")
        } }, { "" })
        try { api.search("Song"); fail("Risk challenge cannot mean no results") }
        catch (error: BiliApiException) {
            assertEquals(-352, error.code); assertTrue(error.message!!.contains("验证"))
            assertFalse(error.message!!.contains("private-challenge"))
        }
    }

    @Test fun backupUrlsAndLowerBitrateStayInSeparateEncodedTracks() = runTest {
        val api = BiliApi({ url, _ -> when {
            url.contains("/nav") -> response(signingKeys)
            url.contains("finger/spi") -> response("{}")
            else -> response("""{"dash":{"audio":[
                {"id":30232,"bandwidth":128000,"codecs":"mp4a.40.2","baseUrl":"https://high.bilivideo.com/high.m4s","backupUrl":["https://backup.bilivideo.cn/high.m4s","https://evil.test/a"]},
                {"id":30216,"bandwidth":64000,"codecs":"mp4a.40.2","base_url":"https://low.bilivideo.com/low.m4s","backup_url":["https://backup.bilivideo.com/low.m4s"]},
                {"id":30250,"bandwidth":999000,"codecs":"ec-3","baseUrl":"https://dolby.bilivideo.com/dolby.m4s"}]}}""")
        } }, { "" })
        val high = api.resolveAudio("BV1xx411c7mD", "456")
        val low = api.resolveAudio("BV1xx411c7mD", "456", "128")
        assertEquals(30232, high.id); assertEquals(30216, low.id)
        assertEquals(listOf("https://high.bilivideo.com/high.m4s", "https://backup.bilivideo.cn/high.m4s"), high.urls)
        assertTrue(low.urls.all { it.endsWith("low.m4s") })
    }

    @Test fun expiredSessionRetriesOnlyPublicAccessAndKeepsGuestCookiesUnique() = runTest {
        val headers = mutableListOf<Map<String, String>>()
        val api = BiliApi({ url, h -> when {
            url.contains("/nav") -> response(signingKeys)
            url.contains("finger/spi") -> response("""{"b_3":"guest-buvid"}""")
            else -> {
                headers += h
                if (h["Cookie"].orEmpty().contains("SESSDATA")) TransportResponse(200, """{"code":-101}""")
                else response("""{"result":[]}""")
            }
        } }, { "SESSDATA=expired; buvid3=session-buvid" })
        assertTrue(api.search("Song").isEmpty())
        assertEquals(2, headers.size)
        assertEquals(1, Regex("buvid3=").findAll(headers[0]["Cookie"]!!).count())
        assertFalse(headers[1]["Cookie"]!!.contains("SESSDATA"))
    }

    @Test fun staleSignatureRefreshesOnceButPermissionsNeverRetryAsGuest() = runTest {
        var navs = 0; var plays = 0
        val api = BiliApi({ url, _ -> when {
            url.contains("/nav") -> { navs++; response(signingKeys) }
            url.contains("finger/spi") -> response("{}")
            else -> { plays++; TransportResponse(200, if (plays == 1) """{"code":-403}""" else """{"code":-10403}""") }
        } }, { "SESSDATA=account" })
        try { api.resolveAudio("BV1xx411c7mD", "456"); fail("Permission failure expected") }
        catch (error: BiliApiException) { assertEquals(-10403, error.code) }
        assertEquals(2, navs); assertEquals(2, plays)
    }
    @Test fun protocolWbiExampleIsCompatible() {
        val key = BiliApi.mixinKey("7cd084941338484aae1ad9425b84077c4932caff0ff746eab6f01bf08b70ac45")
        assertEquals("ea1db124af3c7062474693fa704f4ff8", key)
        assertEquals("bar=514&baz=1919810&foo=114&wts=1702204169&w_rid=6149fdadf571698ca7e6a567265cd0ee",
            BiliApi.signedQuery(mapOf("foo" to "114", "bar" to "514", "baz" to "1919810"), key, 1702204169))
    }

    @Test fun pollReadsHttpOnlyCookiesAndDoesNotExposeThemInDebugText() = runTest {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        var phase = 0
        val api = BiliApi({ url, headers ->
            calls += url to headers
            when (phase++) {
                0 -> TransportResponse(200, """{"code":0,"data":{"qrcode_key":"qr-key","url":"https://account.bilibili.com/h5/account-h5/auth/scan-web?key=qr-key"}}""",
                    mapOf("Set-Cookie" to listOf("sid=guest; HttpOnly; Secure")))
                1 -> TransportResponse(200, """{"code":0,"data":{"code":86101}}""")
                2 -> TransportResponse(200, """{"code":0,"data":{"code":86090}}""")
                else -> TransportResponse(200, """{"code":0,"data":{"code":0}}""", mapOf("set-cookie" to
                    listOf("SESSDATA=fake%2Fsession; HttpOnly; Secure", "DedeUserID=123; Secure", "other=discard")))
            }
        }, { "SESSDATA=old-session" })
        val qr = api.generateQr()
        assertEquals(86101, api.pollQr(qr.key).code)
        assertEquals(86090, api.pollQr(qr.key).code)
        val login = api.pollQr(qr.key)
        assertEquals(0, login.code)
        assertTrue(login.cookie.contains("SESSDATA=fake%2Fsession"))
        assertFalse(login.cookie.contains("other="))
        assertFalse(login.toString().contains("fake%2Fsession"))
        assertNull(calls.first().second["Cookie"])
        assertEquals("sid=guest", calls[1].second["Cookie"])
        assertTrue(calls.all { java.net.URI(it.first).host == "passport.bilibili.com" })
    }

    @Test fun expiredAndIncompleteLoginCannotBecomeAuthenticated() = runTest {
        val expired = BiliApi({ _, _ -> TransportResponse(200, """{"code":0,"data":{"code":86038}}""") }, { "" })
        assertEquals(86038, expired.pollQr("expired").code)
        val invalid = BiliApi({ _, _ -> TransportResponse(200, """{"code":0,"data":{"code":0}}""") }, { "" })
        try { invalid.pollQr("incomplete"); fail("Missing SESSDATA must be rejected") } catch (_: java.io.IOException) { }
    }

    @Test fun untrustedQrTargetsAreNotShownToTheUser() = runTest {
        for (url in listOf("https://account.bilibili.com.evil.org/scan", "http://account.bilibili.com/scan",
            "https://someone@account.bilibili.com/scan", "https://account.bilibili.com:8443/scan")) {
            val api = BiliApi({ _, _ -> TransportResponse(200, """{"code":0,"data":{"qrcode_key":"test","url":"$url"}}""") }, { "" })
            try { api.generateQr(); fail("Untrusted QR target accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun authenticatedResolutionUsesVideoCidAndSendsCookiesOnlyToOfficialApi() = runTest {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val api = BiliApi({ url, headers ->
            calls += url to headers
            val body = when {
                url.contains("finger/spi") -> """{"b_3":"real-buvid","b_4":"real-buvid4"}"""
                url.contains("/nav") -> """{"wbi_img":{"img_url":"https://i.test/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i.test/4932caff0ff746eab6f01bf08b70ac45.png"}}"""
                url.contains("/view?") -> """{"cid":456}"""
                else -> """{"dash":{"audio":[{"bandwidth":999,"baseUrl":"https://evil.example/leak"},{"bandwidth":128,"baseUrl":"https://audio.bilivideo.com/music.m4s"}]}}"""
            }
            TransportResponse(200, """{"code":0,"data":$body}""")
        }, { "SESSDATA=test-private" })
        val audio = api.resolve("BV1xx411c7mD")
        assertEquals("https://audio.bilivideo.com/music.m4s", audio)
        assertTrue(calls.last().first.contains("cid=456"))
        assertTrue(calls.last().first.contains("w_rid="))
        assertTrue(calls.last().second["Cookie"]!!.contains("SESSDATA=test-private"))
        assertTrue(calls.all { java.net.URI(it.first).host == "api.bilibili.com" })
        assertFalse(BiliApi.playbackHeaders(audio).containsKey("Cookie"))
        assertTrue(BiliApi.playbackHeaders("https://worker.example/bili/stream?bvid=example").isEmpty())
    }

    @Test fun riskControlAndHtmlAreErrorsAndCredentialsAreNotInMessages() = runTest {
        for (response in listOf(TransportResponse(412, "private HTML"), TransportResponse(200, "<html>private</html>"),
            TransportResponse(200, """{"code":-101,"message":"sensitive"}"""))) {
            val api = BiliApi({ _, _ -> response }, { "SESSDATA=secret" })
            try { api.account("SESSDATA=secret"); fail("Should reject invalid login") }
            catch (error: java.io.IOException) { assertFalse(error.message.orEmpty().contains("secret")); assertFalse(error.message.orEmpty().contains("sensitive")) }
        }
    }

    @Test fun audioAllowlistRejectsHostConfusionAndCredentials() {
        assertTrue(BiliApi.trustedAudioUrl("https://a.bilivideo.cn/audio"))
        listOf("http://a.bilivideo.com/a", "https://a.bilivideo.com.evil.org/a", "https://bad@a.bilivideo.com/a", "https://a.bilivideo.com:8080/a")
            .forEach { assertFalse(it, BiliApi.trustedAudioUrl(it)) }
    }
}
