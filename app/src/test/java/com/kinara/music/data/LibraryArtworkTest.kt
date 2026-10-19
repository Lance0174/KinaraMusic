package com.kinara.music.data

import com.kinara.music.online.OnlineTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryArtworkTest {
    @Test fun oldPlaylistAndFavoritesRepairPersistWithoutChangingAudioIdentity() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val tracks = (1..4).map { Track("song$it", "song$it", "artist", "album", 120000, "netease", onlineKey = OnlineKey("netease", "$it")) }
        PlaylistStore.get(app).save(listOf(Playlist("old", "旧歌单", 1, tracks)))
        FavoritesStore.get(app).save(tracks.take(1))
        val requests = AtomicInteger(); val active = AtomicInteger(); val peak = AtomicInteger()
        val metadata = TrackInfoRepository(File(app.cacheDir, "cover-repair-${System.nanoTime()}"), { track, _ ->
            requests.incrementAndGet(); peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            delay(50); active.decrementAndGet()
            OnlineTrack("netease", track.onlineKey!!.id, track.title, track.artist, track.album, track.durationMs, "https://p1.music.126.net/${track.onlineKey!!.id}.jpg")
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
        val repo = LibraryRepository(app, scope, metadata)
        withTimeout(5000) { repo.playlists.first { it.isNotEmpty() } }
        repo.refreshArtwork(KinaraSettings(), false); delay(50)
        assertEquals(0, requests.get())
        repo.refreshArtwork(KinaraSettings(autoTrackInfo = false), true); delay(50)
        assertEquals(0, requests.get())
        repo.refreshArtwork(KinaraSettings(), true)
        withTimeout(5000) { repo.playlists.first { it.single().tracks.all { t -> t.coverUrl != null } } }
        assertEquals(4, requests.get()); assertTrue(peak.get() <= 2)
        assertEquals(tracks, repo.playlists.value.single().tracks.map { it.copy(coverUrl = null) })
        assertNotNull(FavoritesStore.get(app).load().single().coverUrl)
        assertTrue(PlaylistStore.get(app).load().single().tracks.all { it.coverUrl != null })
        repo.refreshArtwork(KinaraSettings(), true); delay(50)
        assertEquals(4, requests.get())
        } finally { scope.cancel() }
    }
    @Test fun lateArtworkDoesNotRestoreRemovedTracksOrOverwritePlaylistEdits() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val keep = Track("keep", "song", "artist", "album", 120000, "netease", onlineKey = OnlineKey("netease", "keep"))
        val removed = keep.copy(mediaId = "removed", onlineKey = OnlineKey("netease", "removed"))
        PlaylistStore.get(app).save(listOf(Playlist("editing", "原名称", 1, listOf(keep, removed))))
        FavoritesStore.get(app).save(emptyList())
        val started = kotlinx.coroutines.channels.Channel<Unit>(2)
        val release = CompletableDeferred<Unit>()
        val metadata = TrackInfoRepository(File(app.cacheDir, "late-artwork-${System.nanoTime()}"), { track, _ ->
            started.send(Unit); release.await()
            OnlineTrack("netease", track.onlineKey!!.id, track.title, track.artist, track.album, track.durationMs, "https://p1.music.126.net/cover.jpg")
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repo = LibraryRepository(app, scope, metadata)
            withTimeout(5000) { repo.playlists.first { it.isNotEmpty() } }
            repo.refreshArtwork(KinaraSettings(), true)
            withTimeout(5000) { repeat(2) { started.receive() } }
            repo.remove("editing", removed.mediaId)
            repo.rename("editing", "修改后")
            withTimeout(5000) { repo.playlists.first { it.single().let { p -> p.name == "修改后" && p.tracks.size == 1 } } }
            release.complete(Unit)
            withTimeout(5000) { repo.playlists.first { it.single().tracks.single().coverUrl != null } }
            val saved = PlaylistStore.get(app).load().single()
            assertEquals("修改后", saved.name)
            assertEquals(listOf(keep), saved.tracks.map { it.copy(coverUrl = null) })
        } finally { scope.cancel() }
    }
}
