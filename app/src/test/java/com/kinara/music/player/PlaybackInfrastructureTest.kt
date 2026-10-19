/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.player

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.media3.datasource.*
import com.kinara.music.data.*
import com.kinara.music.download.audioExtension
import com.kinara.music.online.OnlineMediaUri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackInfrastructureTest {
    private val song = Track("test", "歌", "歌手", "", 1000, "netease", onlineKey = OnlineKey("netease", "42"))
    private val bytes = ByteArray(16384) { (it % 127).toByte() }
    private fun upstream(onOpen: () -> Unit): DataSource.Factory = DataSource.Factory {
        object : DataSource {
            private val source = ByteArrayDataSource(bytes)
            override fun addTransferListener(listener: TransferListener) = source.addTransferListener(listener)
            override fun open(spec: DataSpec): Long { onOpen(); return source.open(spec) }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = source.read(buffer, offset, length)
            override fun getUri() = source.uri
            override fun close() = source.close()
        }
    }
    private fun read(source: DataSource, track: Track = song): ByteArray = try {
        source.open(DataSpec.Builder().setUri(OnlineMediaUri.encode(track)).build())
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) { val n = source.read(buffer, 0, buffer.size); if (n < 0) break; result.write(buffer, 0, n) }
        result.toByteArray()
    } finally { source.close() }

    @Test fun completedCacheSurvivesReopenAndNeverTouchesOfflineResolver() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "persist-${System.nanoTime()}")
        var calls = 0
        val upstream = upstream { calls++ }
        AudioCache(context, directory = dir).let { cache ->
            try { assertArrayEquals(bytes, read(cache.source(upstream, song))) } finally { cache.release() }
        }
        val networkCalls = calls
        AudioCache(context, directory = dir).let { cache ->
            try {
                assertArrayEquals(bytes, read(cache.source(upstream { error("offline resolver must not run") }, song)))
                assertEquals(networkCalls, calls)
            } finally { cache.release() }
        }
    }
    @Test fun cacheExpiresAfterSevenDaysAndQualityIsolated() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var clock = 1000L
        val cache = AudioCache(context, { clock }, File(context.cacheDir, "expiry-${System.nanoTime()}"))
        var calls = 0
        val upstream = upstream { calls++ }
        try {
            read(cache.source(upstream, song))
            val first = calls
            read(cache.source(upstream, song.copy(title = "标题变化")))
            assertEquals(first, calls)
            val flac = song.copy(onlineKey = song.onlineKey!!.copy(bitrate = "flac"))
            read(cache.source(upstream, flac), flac)
            assertEquals(first + 1, calls)
            clock += AudioCache.AGE_MS
            cache.maintain(1)
            read(cache.source(upstream, song))
            assertEquals(first + 2, calls)
        } finally { cache.release() }
    }
    @Test fun manualClearDefersActiveReadAndClearsOnClose() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = AudioCache(context, directory = File(context.cacheDir, "clear-${System.nanoTime()}"))
        var calls = 0
        val upstream = upstream { calls++ }
        try {
            read(cache.source(upstream, song))
            val active = cache.source(upstream, song)
            active.open(DataSpec.Builder().setUri(OnlineMediaUri.encode(song)).build())
            cache.maintain(1, clear = true)
            assertEquals(16, active.read(ByteArray(16), 0, 16))
            active.close()
            val before = calls
            read(cache.source(upstream, song))
            assertEquals(before + 1, calls)
        } finally { cache.release() }
    }
    @Test fun retryIsBoundedAndCannotRetryAnotherSelection() {
        val gate = PlaybackRetryGate()
        gate.select("a"); assertTrue(gate.take("a")); assertFalse(gate.take("a"))
        gate.select("b"); assertFalse(gate.take("a")); assertTrue(gate.take("b"))
    }
    @Test fun audioFallbackIsHttpsFirstAndConfinedToAudioHosts() {
        assertEquals(listOf("https://m10.music.126.net/song", "http://m10.music.126.net/song"), audioCandidates("http://m10.music.126.net/song"))
        assertEquals(listOf("https://cn-test.bilivideo.com/audio.m4s"), audioCandidates("https://cn-test.bilivideo.com/audio.m4s"))
        assertThrows(java.io.IOException::class.java) { audioCandidates("http://music.126.net.evil.test/song") }
        assertThrows(java.io.IOException::class.java) { audioCandidates("https://music.126.net.evil.test/song") }
        assertThrows(java.io.IOException::class.java) { audioCandidates("https://example.com/audio") }
        assertThrows(java.io.IOException::class.java) { audioCandidates("https://m10.music.126.net:8443/song") }
        assertThrows(java.io.IOException::class.java) { audioCandidates("file:///private/audio") }
        assertThrows(java.io.IOException::class.java) { audioCandidates("https://user:password@example.com/audio") }
        assertThrows(java.io.IOException::class.java) { audioCandidates("https://m10.music.126.net:80/song") }
        assertEquals(listOf("https://m10.music.126.net/song", "http://m10.music.126.net:80/song"), audioCandidates("http://m10.music.126.net:80/song"))
    }
    @Test fun untrustedBackupCannotDiscardAValidPrimary() {
        val valid = "https://m10.music.126.net/song"
        val result = com.kinara.music.online.ResolvedUrl(com.kinara.music.online.ResolveStage.OK, valid,
            backupUrls = listOf("https://untrusted.test/song", valid))
        assertEquals(listOf(valid), resolvedAudioCandidates(result))
        assertThrows(java.io.IOException::class.java) { resolvedAudioCandidates(result.copy(url = "https://untrusted.test/song")) }
    }
    @Test fun externalControllersCannotInjectMediaButCanOperatePlayback() {
        val commands = externalPlayerCommands()
        assertFalse(commands.contains(androidx.media3.common.Player.COMMAND_SET_MEDIA_ITEM))
        assertFalse(commands.contains(androidx.media3.common.Player.COMMAND_CHANGE_MEDIA_ITEMS))
        assertTrue(commands.contains(androidx.media3.common.Player.COMMAND_PLAY_PAUSE))
        assertTrue(commands.contains(androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
    }
    @Test fun timerRunsWithoutActivityAndCancelRemovesPendingPause() {
        var pauses = 0
        val timer = SleepTimer({ pauses++ })
        timer.set(15, false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(14))
        assertEquals(0, pauses)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        assertEquals(1, pauses); assertFalse(SleepTimer.state.value.active)
        timer.set(15, false); timer.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(16))
        assertEquals(1, pauses)
        timer.set(0, true); timer.trackEnded(); timer.trackEnded(); assertEquals(2, pauses)
    }
    @Test fun downloadRejectsErrorPagesAndRecognizesActualContainer() {
        assertNull(audioExtension("<html>502 Bad Gateway</html>".toByteArray()))
        assertNull(audioExtension("{\"error\":403}".toByteArray()))
        assertEquals("flac", audioExtension("fLaC12345678".toByteArray()))
        assertEquals("wav", audioExtension("RIFF0000WAVE".toByteArray()))
        assertEquals("m4a", audioExtension("0000ftypM4A ".toByteArray()))
        assertEquals("mp3", audioExtension("ID3audio".toByteArray()))
    }
    @Test fun diagnosticsHaveBoundedTypedEvents() {
        repeat(250) { PlaybackDiagnostics.record(PlaybackDiagnostics.Event.OUTPUT_GAIN, it) }
        val report = PlaybackDiagnostics.snapshot()
        assertEquals(202, report.lines().size)
        assertFalse(report.contains("://")); assertFalse(report.contains("Cookie")); assertTrue(report.contains("OUTPUT_GAIN=249"))
    }
}
