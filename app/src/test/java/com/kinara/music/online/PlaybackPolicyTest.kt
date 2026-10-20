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

    @Test fun fallbackRacesEnabledSourcesAndFollowsPriorityAmongReadyResults() = runBlocking {
        val original = track.copy(source = "netease", onlineKey = OnlineKey("netease", "123"))
        val access = Sources(setOf("kuwo")) { p -> listOf(OnlineTrack(p, "id", track.title, track.artist, track.album, track.durationMs)) }
        val settings = KinaraSettings(sources = SourceToggles(kugou = false))
        val next = OnlinePlayback(access).resolveNextForPlay(original, original, settings, PlaybackAttempts())!!
        assertEquals("kuwo", next.second.onlineKey!!.platform)
        assertFalse(access.calls.any { it.contains("kugou") })
        assertTrue(access.calls.containsAll(listOf("search:qq", "resolve:qq:320", "search:kuwo", "resolve:kuwo:320")))
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
        ), PlaybackAttempts())!!
        assertEquals("gd", next.second.onlineKey!!.platform)
        assertEquals(listOf("search:netease", "search:kugou", "search:kuwo", "search:bilibili", "search:solara", "search:gd", "resolve:gd:320"), access.calls)
    }

    @Test fun biliLowQualityRacesBesideOtherSourcesAndCannotLoop() = runBlocking {
        val bili = track.copy(source = "bilibili", onlineKey = OnlineKey("bilibili", "BV1xx411c7mD|"))
        val access = Sources(setOf("bilibili")) { emptyList() }
        val playback = OnlinePlayback(access)
        val settings = KinaraSettings(sources = SourceToggles(solara = true))
        val attempts = PlaybackAttempts()
        val low = playback.resolveNextForPlay(bili, bili, settings, attempts)!!.second
        assertEquals("128", low.onlineKey!!.bitrate)
        assertNull(playback.resolveNextForPlay(bili, low, settings, attempts))
        assertEquals(1, access.calls.count { it == "resolve:bilibili:128" })
        assertEquals(listOf("search:netease", "search:qq", "search:kugou", "search:kuwo", "search:solara", "search:gd"), access.calls.filter { it.startsWith("search:") })
    }

    @Test fun allUnavailableEndsAndDisabledOtherSourcesNeverRun() = runBlocking {
        val access = Sources(emptySet()) { emptyList() }
        val settings = KinaraSettings(sources = SourceToggles(netease = false, kuwo = false, kugou = false, bilibili = false, solara = false, gdFallback = false))
        assertNull(OnlinePlayback(access).resolveNextForPlay(track, track, settings, PlaybackAttempts()))
        assertTrue(access.calls.isEmpty())
    }

    @Test fun lowerPriorityOriginalCanRecoverFromNeteaseAndNeverRevisitFailedStreams() = runBlocking {
        val original = track.copy(source = "kuwo", onlineKey = OnlineKey("kuwo", "first"))
        val access = Sources(setOf("netease", "qq")) { p ->
            (1..2).map { OnlineTrack(p, "$it", track.title, track.artist, "", track.durationMs) }
        }
        val settings = KinaraSettings(sources = SourceToggles(kugou = false, bilibili = false, gdFallback = false))
        val playback = OnlinePlayback(access)
        val attempts = PlaybackAttempts()
        var failed = original
        val selected = mutableListOf<OnlineKey>()
        repeat(4) {
            failed = playback.resolveNextForPlay(original, failed, settings, attempts)!!.second
            selected += failed.onlineKey!!
        }
        assertEquals(listOf("netease", "netease", "qq", "qq"), selected.map { it.platform })
        assertEquals(4, selected.toSet().size)
        assertNull(playback.resolveNextForPlay(original, failed, settings, attempts))
        assertEquals(listOf("search:netease", "search:qq"), access.calls.filter { it.startsWith("search:") })
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
        val next = OnlinePlayback(access).resolveNextForPlay(original, original, KinaraSettings(), PlaybackAttempts())!!
        assertEquals("kuwo", next.second.source)
        assertTrue(calls.containsAll(listOf("qq", "kugou", "kuwo")))
        assertEquals(200L, testScheduler.currentTime)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun allSlowSourcesShareOneDeadlineAndCancellationReleasesEveryRequest() = kotlinx.coroutines.test.runTest {
        val active = mutableSetOf<String>()
        val access = object : PlaybackAccess {
            override suspend fun searchSource(platform: String, keyword: String, settings: KinaraSettings): OnlineResult {
                active += platform
                try { kotlinx.coroutines.delay(60_000); error("should time out") } finally { active -= platform }
            }
            override suspend fun resolveUrl(key: OnlineKey, settings: KinaraSettings) = error("no candidates")
        }
        assertNull(OnlinePlayback(access).resolveNextForPlay(track, track, KinaraSettings(), PlaybackAttempts()))
        assertEquals(6000L, testScheduler.currentTime)
        assertTrue(active.isEmpty())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun verifiedFastAudioWinsOverAStalledUrlAndCancelledCandidatesCanRecoverLater() = kotlinx.coroutines.test.runTest {
        val original = track.copy(source = "netease", onlineKey = OnlineKey("netease", "123"))
        val settings = KinaraSettings(sources = SourceToggles(kugou = false, bilibili = false, gdFallback = false, solara = false))
        val access = Sources(setOf("qq", "kuwo")) { p -> listOf(OnlineTrack(p, p, track.title, track.artist, "")) }
        var slow = true
        val playback = OnlinePlayback(object : PlaybackAccess by access {
            override suspend fun resolveUrl(key: OnlineKey, settings: KinaraSettings) =
                ResolvedUrl(ResolveStage.OK, "https://audio.example/${key.platform}")
        }) { url ->
            if (url.url!!.endsWith("qq") && slow) kotlinx.coroutines.delay(60_000)
            else kotlinx.coroutines.delay(100)
            url
        }
        val attempts = PlaybackAttempts()
        val first = playback.resolveNextForPlay(original, original, settings, attempts)!!.second
        assertEquals("kuwo", first.source)
        assertEquals(300L, testScheduler.currentTime)
        slow = false
        assertEquals("qq", playback.resolveNextForPlay(original, first, settings, attempts)!!.second.source)
        assertNull(playback.resolveNextForPlay(original, first, settings, attempts))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun readyHigherPrioritySourceWinsWithinShortGraceAndBadAudioNeverReachesPlayer() = kotlinx.coroutines.test.runTest {
        val settings = KinaraSettings(sources = SourceToggles(kugou = false, bilibili = false, gdFallback = false, solara = false))
        val access = object : PlaybackAccess {
            override suspend fun searchSource(platform: String, keyword: String, settings: KinaraSettings) =
                OnlineResult(platform, listOf(OnlineTrack(platform, platform, track.title, track.artist, "")))
            override suspend fun resolveUrl(key: OnlineKey, settings: KinaraSettings): ResolvedUrl {
                kotlinx.coroutines.delay(if (key.platform == "netease") 150 else 50)
                return ResolvedUrl(ResolveStage.OK, "https://audio.example/${key.platform}")
            }
        }
        val playback = OnlinePlayback(access)
        assertEquals("netease", playback.resolveNextForPlay(track, track, settings, PlaybackAttempts())!!.second.source)
        assertEquals(150L, testScheduler.currentTime)
        val rejecting = OnlinePlayback(access) { if (it.url!!.endsWith("netease")) null else it }
        assertEquals("kuwo", rejecting.resolveNextForPlay(track, track, settings, PlaybackAttempts())!!.second.source)
    }
}
