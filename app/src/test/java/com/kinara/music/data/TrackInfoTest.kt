/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.data

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.kinara.music.online.*
import com.kinara.music.player.fallbackMediaItem
import com.kinara.music.player.withDisplayMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrackInfoTest {
    private val original = Track("song", "Title", "Artist", "未知专辑", 200000, "netease", onlineKey = OnlineKey("netease", "42"))
    private val info = OnlineTrack("netease", "42", "Title", "Artist", "Album", 200010, "http://p1.music.126.net/art.jpg")
    private fun dir() = File(RuntimeEnvironment.getApplication().filesDir, "info-${System.nanoTime()}")

    @Test fun exactIdentityUpdatesDisplayAndRestoresFromCacheOffline() = runTest {
        val directory = dir()
        var updated = original
        TrackInfoRepository(directory, { _, _ -> info }).sync(original, KinaraSettings(), true) { updated = it }
        assertEquals("Album", updated.album)
        assertEquals("https://p1.music.126.net/art.jpg", updated.coverUrl)
        assertEquals(original.onlineKey, updated.onlineKey)
        assertEquals(original.durationMs, updated.durationMs)
        var restored: Track? = null
        TrackInfoRepository(directory, { _, _ -> error("No offline network request") }).sync(original, KinaraSettings(), false) { restored = it }
        assertEquals(updated, restored)
    }

    @Test fun localMatchRejectsWrongVersionArtistAlbumAndDuration() = runTest {
        val local = original.copy(source = "local", onlineKey = null, localPath = "/music/title.mp3")
        assertTrue(metadataMatch(local, info))
        assertFalse(metadataMatch(local, info.copy(title = "Title (Live)")))
        assertFalse(metadataMatch(local, info.copy(artist = "Artist/Guest")))
        assertFalse(metadataMatch(local.copy(album = "Other"), info))
        assertFalse(metadataMatch(local, info.copy(durationMs = 0)))
        assertFalse(metadataMatch(local, info.copy(durationMs = 206000)))
        var updated = local
        TrackInfoRepository(dir(), { _, _ -> info }).sync(local, KinaraSettings(), true) { updated = it }
        assertEquals(local.localPath, updated.localPath)
        assertNull(updated.onlineKey)
        assertNotNull(updated.coverUrl)
    }

    @Test fun disabledSyncAndOfflineModeNeverFetchAndFailuresKeepExistingCover() = runTest {
        val directory = dir()
        val repository = TrackInfoRepository(directory, { _, _ -> error("must not fetch") })
        repository.sync(original, KinaraSettings(autoTrackInfo = false), true) { fail("No cached data") }
        repository.sync(original, KinaraSettings(), false) { fail("No cached data") }
        var updated = original.copy(coverUrl = "https://example.org/original.jpg")
        TrackInfoRepository(directory, { _, _ -> info.copy(picUrl = "file:///private") }).sync(updated, KinaraSettings(), true) { updated = it }
        assertEquals("https://example.org/original.jpg", updated.coverUrl)
    }

    @Test fun cancelledFetchCannotPublishItsLateMetadata() = runTest {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val repo = TrackInfoRepository(dir(), { _, _ -> withContext(NonCancellable) { started.complete(Unit); delay(1000); info } })
        val task = launch { repo.sync(original, KinaraSettings(), true) { calls++ } }
        started.await()
        task.cancel(); advanceUntilIdle(); task.join()
        assertEquals(0, calls)
    }

    @Test fun metadataReplacementRetainsActualPlaybackUriExtrasAndOriginalLibraryIdentity() {
        val selected = MediaItem.Builder().setMediaId(original.mediaId).setUri(OnlineMediaUri.encode(original))
            .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply { putString("kinara.track", encodeTrackLine(original)) }).build()).build()
        val actual = original.copy(onlineKey = OnlineKey("qq", "mid"), source = "qq")
        val fallback = fallbackMediaItem(selected, actual)
        val updated = withDisplayMetadata(fallback, original.copy(album = "Album", coverUrl = "https://example.org/art.jpg"))
        assertEquals(fallback.localConfiguration, updated.localConfiguration)
        assertEquals(selected.mediaId, updated.mediaId)
        assertEquals("qq", updated.mediaMetadata.extras!!.getString("kinara.playbackSource"))
        assertEquals(actual, updated.mediaMetadata.extras!!.getString("kinara.actualTrack")?.let(::decodeTrackLine))
        assertEquals(original.onlineKey, updated.mediaMetadata.extras!!.getString("kinara.track")?.let(::decodeTrackLine)?.onlineKey)
        assertEquals("https://example.org/art.jpg", updated.mediaMetadata.artworkUri.toString())
        val source = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
            androidx.media3.datasource.DataSource.Factory { error("Metadata update must not open audio") }).createMediaSource(fallback)
        assertTrue("Media3 must accept an in-place source update", source.canUpdateMediaItem(updated))
        source.updateMediaItem(updated)
        assertEquals(updated, source.mediaItem)
    }

    @Test fun lyricAndMetadataPreferencesRoundTripAndRejectInvalidValues() {
        val settings = KinaraSettings(playerVisualMode = PlayerVisualMode.LYRICS, showTranslation = false, showRomanization = true,
            reduceLyricMotion = true, lyricDelayMs = -500, autoTrackInfo = false)
        val data = settings.toPrefsMap()
        assertEquals(settings, parseSettings { data[it] })
        val invalid = mapOf("player_visual_mode" to "broken", "lyric_delay_ms" to "99999999")
        assertEquals(PlayerVisualMode.COVER, parseSettings { invalid[it] }.playerVisualMode)
        assertEquals(10000, parseSettings { invalid[it] }.lyricDelayMs)
    }
}
