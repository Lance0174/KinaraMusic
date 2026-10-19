/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.download

import android.app.DownloadManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kinara.music.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34])
class DownloadsTest {
    private val song = Track("online", "歌曲", "歌手", "", 0, "bilibili", onlineKey = OnlineKey("bilibili", "BVtest"))
    @Test fun fallbackDownloadPersistsActualLyricIdentityAndKeepsOriginalRetryKey() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("kinara_downloads", 0)
        prefs.edit().clear().commit()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val actual = song.copy(source = "netease", onlineKey = OnlineKey("netease", "actual42"))
        try {
            val first = Downloads(context, { PreparedDownload("https://m10.music.126.net/song", "mp3", actual) }, firstScope)
            first.enqueue(song, "320").join()
            val entry = withTimeout(10_000) { first.entries.first { it.firstOrNull()?.phase == DownloadPhase.RUNNING }.first() }
            assertEquals("netease:actual42", lyricIdentity(entry.track))
            assertEquals(song.onlineKey, entry.requestedTrack.onlineKey)
            withTimeout(10_000) { while (!prefs.getString("items", "").orEmpty().contains("RUNNING")) delay(5) }
            firstScope.cancel()
            val restored = Downloads(context, { error("must reuse system task") }, secondScope)
            val recovered = withTimeout(10_000) { restored.entries.first { it.firstOrNull()?.phase == DownloadPhase.RUNNING }.first() }
            assertEquals(entry.key, recovered.key)
            assertEquals(actual.onlineKey, recovered.track.onlineKey)
            assertEquals(song.onlineKey, recovered.requestedTrack.onlineKey)
            val local = recovered.copy(phase = DownloadPhase.COMPLETE, uri = "content://downloads/42").localTrack()!!
            assertEquals("netease:actual42", lyricIdentity(local))
            restored.cancel(recovered.key).join()
        } finally { firstScope.cancel(); secondScope.cancel() }
    }
    @Test fun persistedSystemTaskReconnectsWithoutResolvingAnExpiredUrl() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("kinara_downloads", 0)
        prefs.edit().clear().commit()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val first = Downloads(context, { PreparedDownload("https://audio.bilivideo.com/first", "m4a", song) }, firstScope)
            first.enqueue(song, "320").join()
            val entry = withTimeout(10_000) { first.entries.first { it.firstOrNull()?.phase == DownloadPhase.RUNNING }.first() }
            withTimeout(10_000) { while (!prefs.getString("items", "").orEmpty().contains("RUNNING")) delay(5) }
            firstScope.cancel()
            val restored = Downloads(context, { error("must reconnect to existing system task") }, secondScope)
            val recovered = withTimeout(10_000) { restored.entries.first { it.firstOrNull()?.phase == DownloadPhase.RUNNING }.first() }
            assertEquals(entry.id, recovered.id); assertEquals(entry.key, recovered.key)
            restored.cancel(recovered.key).join()
        } finally { firstScope.cancel(); secondScope.cancel() }
    }
    @Test fun systemDownloadUsesRequestedQualityAndCancelsWithoutCookieHeaders() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("kinara_downloads", 0).edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var bitrate = ""
        val downloads = Downloads(context, { track -> bitrate = track.onlineKey!!.bitrate; PreparedDownload("https://audio.bilivideo.com/test", "m4a", song) }, scope)
        try {
            downloads.enqueue(song, "flac").join()
            val entry = withTimeout(10_000) { downloads.entries.first { it.firstOrNull()?.phase == DownloadPhase.RUNNING }.first() }
            val manager = context.getSystemService(DownloadManager::class.java)
            val request = shadowOf(shadowOf(manager).getRequest(entry.id))
            assertEquals("flac", bitrate); assertTrue(request.destination.toString().contains("Music/Kinara"))
            assertTrue(request.destination.toString().endsWith(".m4a"))
            assertFalse(request.requestHeaders.any { it.first.equals("Cookie", true) })
            assertTrue(request.requestHeaders.any { it.first.equals("Referer", true) })
            downloads.cancel(entry.key).join()
            assertEquals(DownloadPhase.CANCELLED, downloads.entries.value.first().phase)
            assertEquals(0, shadowOf(manager).requestCount)
        } finally { scope.cancel() }
    }
    @Test fun failedDownloadRetryResolvesAgainInsteadOfReusingStaleUrl() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("kinara_downloads", 0).edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var attempts = 0
        val downloads = Downloads(context, { if (++attempts == 1) error("expired"); PreparedDownload("https://audio.bilivideo.com/fresh", "m4a", song) }, scope)
        try {
            downloads.enqueue(song, "320").join()
            val failed = withTimeout(10_000) { downloads.entries.first { it.firstOrNull()?.phase == DownloadPhase.FAILED }.first() }
            downloads.retry(failed)
            val ready = withTimeout(10_000) { downloads.entries.first { it.firstOrNull()?.phase == DownloadPhase.RUNNING }.first() }
            assertEquals(2, attempts); assertTrue(ready.id >= 0)
            downloads.cancel(ready.key).join()
        } finally { scope.cancel() }
    }
    @Test fun cancelDuringResolutionCannotEnqueueAStaleDownload() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("kinara_downloads", 0).edit().clear().commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<PreparedDownload>()
        val downloads = Downloads(context, { gate.await() }, scope)
        try {
            downloads.enqueue(song, "320").join()
            val entry = downloads.entries.value.first()
            downloads.cancel(entry.key).join()
            gate.complete(PreparedDownload("https://audio.bilivideo.com/late", "m4a", song))
            assertEquals(DownloadPhase.CANCELLED, downloads.entries.value.first().phase)
            assertEquals(0, shadowOf(context.getSystemService(DownloadManager::class.java)).requestCount)
        } finally { scope.cancel() }
    }
}
