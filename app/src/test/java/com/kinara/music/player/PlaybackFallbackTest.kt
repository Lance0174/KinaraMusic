package com.kinara.music.player

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.*
import androidx.test.core.app.ApplicationProvider
import com.kinara.music.data.*
import com.kinara.music.online.OnlineMediaUri
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackFallbackTest {
    private val original = Track("Oqq42", "Song", "Artist", "Album", 240000, "qq", onlineKey = OnlineKey("qq", "42"))
    private val next = original.copy(mediaId = "Okuwo7", source = "kuwo", onlineKey = OnlineKey("kuwo", "7"))

    @Test fun replacementPreservesLibraryIdentityButChangesMediaUriAndCacheKey() {
        val item = MediaItem.Builder().setMediaId(original.mediaId).setUri(OnlineMediaUri.encode(original))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(original.title).setExtras(Bundle().apply {
                putString("kinara.track", encodeTrackLine(original))
            }).build()).build()
        val changed = fallbackMediaItem(item, next)
        assertEquals(original.mediaId, changed.mediaId)
        assertEquals(original, decodeTrackLine(changed.mediaMetadata.extras!!.getString("kinara.track")!!))
        assertEquals(next, OnlineMediaUri.decode(changed.localConfiguration!!.uri.toString()))
        assertEquals("kuwo", changed.mediaMetadata.extras!!.getString("kinara.playbackSource"))
        assertNotEquals(audioCacheKey(original), audioCacheKey(next))
        assertNotEquals(audioCacheKey(next), audioCacheKey(next.copy(onlineKey = next.onlineKey!!.copy(bitrate = "128"))))
    }

    @Test fun changingSelectionRejectsLateUncancellableFallback() = runTest {
        var applied = false
        val coordinator = PlaybackFallback(backgroundScope) { _, _, _ -> withContext(NonCancellable) { delay(100); next } }
        coordinator.start(original, original, { true }) { applied = true }
        runCurrent(); coordinator.reset(); advanceTimeBy(101); runCurrent()
        assertFalse(applied)
    }

    @Test fun pauseSuppressesPendingFallbackAndDuplicateErrorsStartOnlyOneSearch() = runTest {
        var calls = 0; var playing = true; var applied = false
        val coordinator = PlaybackFallback(backgroundScope) { _, _, _ -> calls++; delay(100); next }
        repeat(2) { coordinator.start(original, original, { playing }) { applied = true } }
        runCurrent(); playing = false; advanceTimeBy(101); runCurrent()
        assertEquals(1, calls); assertFalse(applied)
    }

    @Test fun successfulFallbackAppliesOnceAndExhaustionDoesNotRestartTheSearch() = runTest {
        var calls = 0; var applications = 0
        val coordinator = PlaybackFallback(backgroundScope) { _, _, _ -> calls++; if (calls == 1) next else null }
        coordinator.start(original, original, { true }) { applications++ }
        runCurrent()
        coordinator.start(original, next, { true }) { applications++ }
        runCurrent()
        assertEquals(2, calls); assertEquals(1, applications)
    }

    @Test fun exhaustionAdvancesOnlyTheCurrentSelectionAndPauseCancelsLateExhaustion() = runTest {
        var exhausted = 0
        val coordinator = PlaybackFallback(backgroundScope) { _, _, _ -> delay(100); null }
        coordinator.start(original, original, { true }, { exhausted++ }) { fail("No replacement expected") }
        advanceTimeBy(101); runCurrent()
        assertEquals(1, exhausted)
        coordinator.start(original, original, { true }, { exhausted++ }) { fail("No replacement expected") }
        runCurrent(); coordinator.reset(); advanceTimeBy(101); runCurrent()
        assertEquals(1, exhausted)
    }

    @Test fun unavailableCdnClosesBeforeBackupAndPreservesRangeWithoutSendingCookies() {
        val specs = mutableListOf<DataSpec>(); var closes = 0
        val bytes = ByteArray(500) { it.toByte() }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = ResolvedAudioSource(context, AudioUrlAccess {
            listOf("https://bad.bilivideo.com/audio.m4s", "https://good.bilivideo.cn/audio.m4s")
        }) {
            object : DataSource {
                private val data = ByteArrayDataSource(bytes)
                override fun addTransferListener(listener: TransferListener) = Unit
                override fun open(spec: DataSpec): Long {
                    specs += spec
                    if (spec.uri.host == "bad.bilivideo.com") throw IOException("CDN unavailable")
                    assertEquals(1, closes)
                    return data.open(spec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = data.read(buffer, offset, length)
                override fun getUri() = data.uri
                override fun close() { closes++; data.close() }
            }
        }
        try {
            source.open(DataSpec.Builder().setUri(OnlineMediaUri.encode(original)).setPosition(100).setLength(20).build())
            val read = ByteArray(20)
            assertEquals(20, source.read(read, 0, 20)); assertArrayEquals(bytes.copyOfRange(100, 120), read)
            assertEquals(listOf(100L, 100L), specs.map { it.position })
            assertTrue(specs.all { !it.httpRequestHeaders.containsKey("Cookie") && it.httpRequestHeaders.containsKey("Referer") })
        } finally { source.close() }
        assertEquals(2, closes)
    }

    @Test fun partialFailedRecordingCannotContaminateFallbackAndFallbackRemainsCachedOffline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = AudioCache(context, directory = java.io.File(context.cacheDir, "fallback-${System.nanoTime()}"))
        val first = ByteArray(1024) { 1 }
        val second = ByteArray(1024) { 2 }
        fun factory(bytes: ByteArray) = DataSource.Factory { ByteArrayDataSource(bytes) }
        val spec = DataSpec.Builder().setUri(OnlineMediaUri.encode(original)).build()
        try {
            cache.source(factory(first), original).let { source ->
                source.open(spec); source.read(ByteArray(100), 0, 100); source.close()
            }
            cache.source(factory(second), next).let { source ->
                source.open(spec.withUri(android.net.Uri.parse(OnlineMediaUri.encode(next))))
                val actual = ByteArray(1024); var count = 0
                while (count < actual.size) { val n = source.read(actual, count, actual.size - count); if (n < 0) break; count += n }
                source.read(ByteArray(1), 0, 1); source.close()
                assertArrayEquals(second, actual)
            }
            val offline = cache.source(DataSource.Factory {
                object : DataSource {
                    override fun addTransferListener(listener: TransferListener) = Unit
                    override fun open(spec: DataSpec): Long = error("A completed fallback must not request the network")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = error("Offline upstream read")
                    override fun getUri(): android.net.Uri? = null
                    override fun close() = Unit
                }
            }, next)
            try {
                offline.open(spec.withUri(android.net.Uri.parse(OnlineMediaUri.encode(next))))
                val actual = ByteArray(32); assertEquals(32, offline.read(actual, 0, 32))
                assertArrayEquals(ByteArray(32) { 2 }, actual)
            } finally { offline.close() }
        } finally { cache.release() }
    }
}
