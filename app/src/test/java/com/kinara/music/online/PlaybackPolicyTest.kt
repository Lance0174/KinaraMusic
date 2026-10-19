/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.online

import com.kinara.music.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    private val track = Track("Oqqmid", "歌 & + /?", "歌手", "专辑", 12000, "qq", onlineKey = OnlineKey("qq", "mid"))

    @Test fun stableQueueUriRoundTripsOnlineIdentityAndMetadata() {
        assertEquals(track, OnlineMediaUri.decode(OnlineMediaUri.encode(track)))
        assertNull(OnlineMediaUri.decode("file:///audio.mp3"))
        assertNull(OnlineMediaUri.decode("kinara://online/%XX"))
    }
    @Test fun neteaseHttpsUpgradeDoesNotRewriteUnrelatedHosts() {
        assertEquals("https://m701.music.126.net/audio?a=1%2B2", secureAudioUrl("http://m701.music.126.net/audio?a=1%2B2"))
        assertEquals("http://music.126.net.evil.test/a", secureAudioUrl("http://music.126.net.evil.test/a"))
    }
    @Test fun fallbackIdsStayInTheNeteaseNamespace() {
        assertEquals("123", solaraFallbackId(OnlineKey("netease", "123")))
        for (platform in listOf("kuwo", "qq", "kugou", "bilibili", "gd")) assertNull(solaraFallbackId(OnlineKey(platform, "123")))
    }
    @Test fun disabledSourceStopsBeforeAnyNetworkCall() = runBlocking {
        val settings = KinaraSettings(sources = SourceToggles(qq = false, gdFallback = false))
        val result = OnlinePlayback(OnlineRepository { settings.sources }).resolveForPlay(track, settings).first
        assertEquals(ResolveStage.NOT_CONFIGURED, result.stage)
        assertTrue(result.errorMessage!!.contains("关闭"))
    }
    @Test fun automaticMatchingRejectsCoverAndLiveVersions() {
        assertTrue(sameRecording("Song", "Singer/Guest", "song", "Guest/Singer"))
        assertFalse(sameRecording("Song", "Singer/Guest", "song", "Guest"))
        assertFalse(sameRecording("Song", "Singer", "Song Live", "Singer"))
        assertFalse(sameRecording("Song", "Singer", "Song", "Cover Artist"))
        assertFalse(sameRecording("", "Singer", "", "Singer"))
        assertFalse(sameRecording("Song", "未知艺术家", "Song", "未知艺术家"))
    }

    private class Sources(private val playable: Set<String>, private val candidates: (String) -> List<OnlineTrack>) : PlaybackAccess {
        val calls = mutableListOf<String>()
        override suspend fun searchSource(platform: String, keyword: String, settings: KinaraSettings): OnlineResult {
            calls += "search:$platform"; return OnlineResult(platform, candidates(platform))
        }
        override suspend fun resolveUrl(key: OnlineKey, settings: KinaraSettings): ResolvedUrl {
            calls += "resolve:${key.platform}:${key.bitrate}"
            return if (key.platform in playable) ResolvedUrl(ResolveStage.OK, "https://audio.example/test") else ResolvedUrl(ResolveStage.NO_RESULT)
        }
    }

    @Test fun fallbackFollowsPrioritySkipsDisabledAndStopsOnSuccess() = runBlocking {
        val original = track.copy(source = "netease", onlineKey = OnlineKey("netease", "123"))
        val access = Sources(setOf("kuwo")) { p -> listOf(OnlineTrack(p, "id", track.title, track.artist, track.album, track.durationMs)) }
        val settings = KinaraSettings(sources = SourceToggles(kugou = false))
        val next = OnlinePlayback(access).resolveNextForPlay(original, original, settings)!!
        assertEquals("kuwo", next.second.onlineKey!!.platform)
        assertEquals(listOf("search:qq", "resolve:qq:320", "search:kuwo", "resolve:kuwo:320"), access.calls)
    }

    @Test fun emptyAndMismatchedSourcesContinueWithoutPlayingOtherRecordings() = runBlocking {
        val access = Sources(setOf("kuwo", "gd")) { p -> when (p) {
            "kuwo" -> listOf(OnlineTrack(p, "cover", track.title, "另一个歌手", ""),
                OnlineTrack(p, "short", track.title, track.artist, "", track.durationMs + 10000))
            "gd" -> listOf(OnlineTrack(p, "match", track.title, track.artist, ""))
            else -> emptyList()
        } }
        val next = OnlinePlayback(access).resolveNextForPlay(track, track, KinaraSettings(
            sources = SourceToggles(solara = true)
        ))!!
        assertEquals("gd", next.second.onlineKey!!.platform)
        assertEquals(listOf("search:kugou", "search:kuwo", "search:bilibili", "search:solara", "search:gd", "resolve:gd:320"), access.calls)
    }

    @Test fun biliUsesIndependentLowQualityBeforeOtherSourcesAndCannotLoop() = runBlocking {
        val bili = track.copy(source = "bilibili", onlineKey = OnlineKey("bilibili", "BV1xx411c7mD|"))
        val access = Sources(setOf("bilibili")) { emptyList() }
        val playback = OnlinePlayback(access)
        val settings = KinaraSettings(sources = SourceToggles(solara = true))
        val low = playback.resolveNextForPlay(bili, bili, settings)!!.second
        assertEquals("128", low.onlineKey!!.bitrate)
        assertNull(playback.resolveNextForPlay(bili, low, settings))
        assertEquals(listOf("resolve:bilibili:128", "search:solara", "search:gd"), access.calls)
    }

    @Test fun allUnavailableEndsAndDisabledOtherSourcesNeverRun() = runBlocking {
        val access = Sources(emptySet()) { emptyList() }
        val settings = KinaraSettings(sources = SourceToggles(netease = false, kuwo = false, kugou = false, bilibili = false, solara = false, gdFallback = false))
        assertNull(OnlinePlayback(access).resolveNextForPlay(track, track, settings))
        assertTrue(access.calls.isEmpty())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun sourceTimeoutAndExceptionDoNotBlockLaterPlatforms() = kotlinx.coroutines.test.runTest {
        val original = track.copy(source = "netease", onlineKey = OnlineKey("netease", "123"))
        val calls = mutableListOf<String>()
        val access = object : PlaybackAccess {
            override suspend fun searchSource(platform: String, keyword: String, settings: KinaraSettings): OnlineResult {
                calls += platform
                if (platform == "qq") kotlinx.coroutines.delay(60_000)
                if (platform == "kugou") throw java.io.IOException("temporary error")
                return OnlineResult(platform, listOf(OnlineTrack(platform, "id", track.title, track.artist, "")))
            }
            override suspend fun resolveUrl(key: OnlineKey, settings: KinaraSettings) = ResolvedUrl(ResolveStage.OK, "https://example.test/audio")
        }
        val next = OnlinePlayback(access).resolveNextForPlay(original, original, KinaraSettings())!!
        assertEquals("kuwo", next.second.source)
        assertEquals(listOf("qq", "kugou", "kuwo"), calls)
        assertEquals(12000L, testScheduler.currentTime)
    }
}
