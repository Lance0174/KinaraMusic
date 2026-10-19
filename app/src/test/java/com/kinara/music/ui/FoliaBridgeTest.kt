package com.kinara.music.ui

import android.net.Uri
import android.webkit.WebResourceRequest
import com.kinara.music.data.*
import com.kinara.music.online.*
import com.kinara.music.player.PlayerUiState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34])
class FoliaBridgeTest {
    @Test fun bridgeUsesActualRecordingMillisecondsAndDoesNotInventWordTiming() {
        val chosen = Track("one", "original", "artist", "album", 10000, "netease", onlineKey = OnlineKey("netease", "1"))
        val actual = chosen.copy(source = "qq", onlineKey = OnlineKey("qq", "2"))
        val lines = listOf(LyricLine(720, "</script>\"测试\n文本"), LyricLine(2070, "word", 3600, listOf(LyricWord(2070, 3600, "word"))))
        val json = JSONObject(foliaSongJson(lines, PlayerUiState(listOf(chosen),0,actualTrack = actual), KinaraSettings()))
        assertEquals("qq:2", json.getString("identity"))
        val output = json.getJSONArray("lines")
        assertEquals(.72, output.getJSONObject(0).getDouble("startTime"), 0.0)
        assertEquals(2.07, output.getJSONObject(0).getDouble("endTime"), 0.0)
        assertEquals(lines[0].text, output.getJSONObject(0).getString("fullText"))
        assertEquals(0, output.getJSONObject(0).getJSONArray("words").length())
        assertEquals(2.07, output.getJSONObject(1).getJSONArray("words").getJSONObject(0).getDouble("startTime"), 0.0)
    }
    @Test fun resourceBoundaryAllowsOnlyThePackagedRenderer() {
        val client = FoliaAssetClient(RuntimeEnvironment.getApplication(), {}, {})
        fun request(url: String) = object : WebResourceRequest {
            override fun getUrl() = Uri.parse(url)
            override fun isForMainFrame() = true
            override fun isRedirect() = false
            override fun hasGesture() = false
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = emptyMap<String,String>()
        }
        assertEquals(200, client.shouldInterceptRequest(null, request("https://kinara.invalid/folia/index.html")).statusCode)
        listOf("https://example.com/music", "file:///data/data/com.kinara.music/files/session", "https://kinara.invalid/folia/../secrets",
            "https://kinara.invalid/folia/missing.js").forEach {
            assertEquals(403, client.shouldInterceptRequest(null, request(it)).statusCode)
        }
    }
    @Test fun localAudioUsesTheSameIdentityForSceneAndClock() {
        val track = Track("local-file", "instrumental", "artist", "album", 30000, "local", localPath = "/music/song.mp3")
        val json = JSONObject(foliaSongJson(emptyList(), PlayerUiState(listOf(track), 0), KinaraSettings()))
        assertEquals(lyricIdentity(track), json.getString("identity"))
        assertEquals(0, json.getJSONArray("lines").length())
    }
}
