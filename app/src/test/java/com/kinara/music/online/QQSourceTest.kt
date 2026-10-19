package com.kinara.music.online

import com.kinara.music.online.OnlineTransport.TransportResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class QQSourceTest {
    @Test fun searchRejectsBusinessErrorsAndMissingDataInsteadOfShowingNoResults() {
        for (body in listOf("""{"code":500003,"message":"private-response"}""", """{"code":0}""", "<html>error</html>")) {
            val result = QQSource.parseSearch(TransportResponse(200, body))
            assertNotNull(result.errorMessage); assertTrue(result.tracks.isEmpty())
            assertFalse(result.errorMessage!!.contains("private-response"))
        }
        assertNull(QQSource.parseSearch(TransportResponse(200, """{"code":0,"data":{"song":{"list":[]}}}""")).errorMessage)
    }
    @Test fun searchKeepsMetadataAndOnlyValidMids() {
        val result = QQSource.parseSearch(TransportResponse(200, """{"code":0,"data":{"song":{"list":[
            {"songmid":"song123","songname":"Song","singer":[{"name":"Artist"}],"albumname":"Album","albummid":"album123","interval":240},
            {"songmid":"","songname":"Invalid"}]}}}"""))
        assertEquals(1, result.tracks.size)
        assertEquals(240000L, result.tracks.single().durationMs)
        assertTrue(result.tracks.single().picUrl!!.contains("album123"))
    }
    @Test fun vkeyEmptyPurlIsUnavailableAndNeverUsesAnotherSongOrUntrustedHost() {
        for (row in listOf("""{"songmid":"mid","purl":""}""", """{"songmid":"other","purl":"song.mp3"}""",
            """{"songmid":"mid","purl":"https://evil.example/leak"}""", """{"songmid":"mid","purl":"preview/song.mp3"}""")) {
            val result = QQSource.parseUrl(TransportResponse(200, """{"code":0,"req":{"code":0,"data":{"sip":["https://dl.stream.qqmusic.qq.com/"],"midurlinfo":[$row]}}}"""), "mid")
            assertEquals(ResolveStage.NO_RESULT, result.stage)
        }
    }
    @Test fun vkeyKeepsAlternativeCdnAddressesForTheSameFile() {
        val result = QQSource.parseUrl(TransportResponse(200, """{"code":0,"req":{"code":0,"data":{"sip":["https://a.qq.com/","https://b.qq.com/"],"midurlinfo":[{"songmid":"mid","purl":"M800midmid.mp3?vkey=test"}]}}}"""), "mid")
        assertEquals(ResolveStage.OK, result.stage)
        assertEquals("https://b.qq.com/M800midmid.mp3?vkey=test", result.backupUrls.single())
    }
}
