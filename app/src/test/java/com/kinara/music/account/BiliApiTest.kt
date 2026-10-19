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
        } })
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
        } })
        val high = api.resolveAudio("BV1xx411c7mD", "456")
        val low = api.resolveAudio("BV1xx411c7mD", "456", "128")
        assertEquals(30232, high.id); assertEquals(30216, low.id)
        assertEquals(listOf("https://high.bilivideo.com/high.m4s", "https://backup.bilivideo.cn/high.m4s"), high.urls)
        assertTrue(low.urls.all { it.endsWith("low.m4s") })
    }

    @Test fun staleSignatureRefreshesOnceButPermissionsNeverRetryAsGuest() = runTest {
        var navs = 0; var plays = 0
        val api = BiliApi({ url, _ -> when {
            url.contains("/nav") -> { navs++; response(signingKeys) }
            url.contains("finger/spi") -> response("{}")
            else -> { plays++; TransportResponse(200, if (plays == 1) """{"code":-403}""" else """{"code":-10403}""") }
        } })
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

    @Test fun anonymousResolutionUsesVideoCidAndGuestCookiesOnly() = runTest {
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
        })
        val audio = api.resolve("BV1xx411c7mD")
        assertEquals("https://audio.bilivideo.com/music.m4s", audio)
        assertTrue(calls.last().first.contains("cid=456"))
        assertTrue(calls.last().first.contains("w_rid="))
        assertTrue(calls.last().second["Cookie"]!!.contains("buvid3=real-buvid"))
        assertTrue(calls.all { !it.second["Cookie"].orEmpty().contains("SESSDATA") })
        assertTrue(calls.all { java.net.URI(it.first).host == "api.bilibili.com" })
        assertFalse(BiliApi.playbackHeaders(audio).containsKey("Cookie"))
        assertTrue(BiliApi.playbackHeaders("https://worker.example/bili/stream?bvid=example").isEmpty())
    }

    @Test fun riskControlAndHtmlAreErrorsAndCredentialsAreNotInMessages() = runTest {
        for (response in listOf(TransportResponse(412, "private HTML"), TransportResponse(200, "<html>private</html>"),
            TransportResponse(200, """{"code":-101,"message":"sensitive"}"""))) {
            val api = BiliApi({ _, _ -> response })
            try { api.search("Song"); fail("Should reject invalid response") }
            catch (error: java.io.IOException) { assertFalse(error.message.orEmpty().contains("secret")); assertFalse(error.message.orEmpty().contains("sensitive")) }
        }
    }

    @Test fun audioAllowlistRejectsHostConfusionAndCredentials() {
        assertTrue(BiliApi.trustedAudioUrl("https://a.bilivideo.cn/audio"))
        listOf("http://a.bilivideo.com/a", "https://a.bilivideo.com.evil.org/a", "https://bad@a.bilivideo.com/a", "https://a.bilivideo.com:8080/a")
            .forEach { assertFalse(it, BiliApi.trustedAudioUrl(it)) }
    }
}
