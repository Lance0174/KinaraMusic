/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Serial mutations plus IO serialization keep large playlists off the UI thread. */
class LibraryRepository(private val context: Context, private val scope: CoroutineScope,
    private val artwork: TrackInfoRepository = TrackInfoRepository(java.io.File(context.filesDir, "track-info-v1"))) {
    private val favoritesStore = FavoritesStore.get(context)
    private val playlistStore = PlaylistStore.get(context)
    private val _favorites = MutableStateFlow<List<Track>>(emptyList())
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    private val _notice = MutableStateFlow<String?>(null)
    private val mutex = Mutex()
    private var artworkJob: Job? = null
    private var artworkPolicy: Triple<Boolean, Boolean, SourceToggles>? = null
    private val artworkAttempted = mutableSetOf<String>()
    val favorites: StateFlow<List<Track>> = _favorites
    val playlists: StateFlow<List<Playlist>> = _playlists
    val notice: StateFlow<String?> = _notice
    private val initial = scope.launch {
        val loaded = withContext(Dispatchers.IO) {
            val fav = favoritesStore.load()
            val all = playlistStore.load()
            val local = all.filter { it.name == "本地音乐" }
            val merged = if (local.size > 1) all.filterNot { it in local.drop(1) }.map { p ->
                if (p.id == local.first().id) p.copy(tracks = local.flatMap { it.tracks }.distinctBy { it.mediaId }) else p
            } else all
            if (merged != all) playlistStore.save(merged)
            fav to merged
        }
        _favorites.value = loaded.first
        _playlists.value = loaded.second
    }
    fun clearNotice() { _notice.value = null }
    fun message(text: String) { _notice.value = text }

    /** Repair already imported playlists as well as favorites. Two requests at a time, no UI blocking. */
    fun refreshArtwork(settings: KinaraSettings, online: Boolean) {
        val policy = Triple(online, settings.autoTrackInfo, settings.sources)
        if (policy != artworkPolicy) {
            artworkJob?.cancel(); artworkAttempted.clear(); artworkPolicy = policy
        }
        if (!online || !settings.autoTrackInfo || artworkJob?.isActive == true) return
        artworkJob = scope.launch {
            initial.join()
            while (isActive) {
                val missing = (_favorites.value + _playlists.value.flatMap { it.tracks })
                    .filter { safeCoverUrl(it.coverUrl) == null && it.onlineKey != null }
                    .distinctBy(::lyricIdentity).filter { lyricIdentity(it) !in artworkAttempted }.take(2)
                if (missing.isEmpty()) break
                missing.forEach { artworkAttempted.add(lyricIdentity(it)!!) }
                val found = coroutineScope { missing.map { track -> async {
                    var info: Track? = null
                    artwork.sync(track, settings, true) { if (safeCoverUrl(it.coverUrl) != null) info = it }
                    info
                } }.awaitAll().filterNotNull() }
                if (found.isNotEmpty()) mergeArtwork(found)
            }
        }
    }

    private suspend fun mergeArtwork(found: List<Track>) {
        mutex.withLock {
            val byIdentity = found.associateBy(::lyricIdentity)
            fun merge(track: Track): Track = byIdentity[lyricIdentity(track)]?.let { info ->
                // Only fill a missing image; don't change identity, audio URI, duration or user data.
                if (safeCoverUrl(track.coverUrl) == null) track.copy(coverUrl = info.coverUrl) else track
            } ?: track
            val favorites = _favorites.value.map(::merge)
            val playlists = _playlists.value.map { it.copy(tracks = it.tracks.map(::merge)) }
            withContext(Dispatchers.IO) {
                if (favorites != _favorites.value) favoritesStore.save(favorites)
                if (playlists != _playlists.value) playlistStore.save(playlists)
            }
            _favorites.value = favorites; _playlists.value = playlists
        }
    }

    private fun edit(change: (List<Playlist>) -> List<Playlist>) = scope.launch {
        initial.join()
        mutex.withLock {
            val next = withContext(Dispatchers.Default) { change(_playlists.value) }
            withContext(Dispatchers.IO) { playlistStore.save(next) }
            _playlists.value = next
        }
    }
    private fun editFavorites(change: (List<Track>) -> List<Track>) = scope.launch {
        initial.join()
        mutex.withLock {
            val next = withContext(Dispatchers.Default) { change(_favorites.value) }
            withContext(Dispatchers.IO) { favoritesStore.save(next) }
            _favorites.value = next
        }
    }
    fun toggle(track: Track) { editFavorites { toggleFavorite(it, track) } }
    fun create(name: String) { edit { createPlaylist(it, name, System.currentTimeMillis(), "pl_${UUID.randomUUID()}") } }
    fun rename(id: String, name: String) { if (id != FAVORITES) edit { renamePlaylist(it, id, name) } }
    fun delete(id: String) { if (id != FAVORITES) edit { deletePlaylist(it, id) } }
    fun add(id: String, tracks: List<Track>) {
        if (id == FAVORITES) editFavorites { (it + tracks).distinctBy { t -> t.mediaId } }
        else edit { addTracksToPlaylist(it, id, tracks) }
    }
    fun remove(id: String, mediaId: String) {
        if (id == FAVORITES) editFavorites { it.filterNot { t -> t.mediaId == mediaId } }
        else edit { removeTrackFromPlaylist(it, id, mediaId) }
    }
    fun save(name: String, tracks: List<Track>) { if (tracks.isNotEmpty()) edit {
        it + Playlist("pl_${UUID.randomUUID()}", name.trim().ifBlank { "导入歌单" }, System.currentTimeMillis(), tracks.distinctBy { t -> t.mediaId })
    } }
    fun importAudio(uris: List<Uri>, targetId: String?) = scope.launch {
        initial.join()
        _notice.value = "正在读取 ${uris.distinct().size} 个音频文件…"
        val result = LocalAudioImport.read(context, uris)
        mutex.withLock {
            val current = _playlists.value
            val target = current.firstOrNull { it.id == targetId } ?: if (targetId == null) current.firstOrNull { it.name == "本地音乐" } else null
            if (targetId != null && targetId != FAVORITES && target == null) { _notice.value = "目标歌单已删除，未添加歌曲"; return@withLock }
            val before = if (targetId == FAVORITES) _favorites.value else target?.tracks.orEmpty()
            val merged = (before + result.tracks).distinctBy { it.mediaId }
            if (targetId == FAVORITES) {
                withContext(Dispatchers.IO) { favoritesStore.save(merged) }; _favorites.value = merged
            } else if (result.tracks.isNotEmpty()) {
                val next = if (target == null) current + Playlist("pl_${UUID.randomUUID()}", "本地音乐", System.currentTimeMillis(), merged)
                    else current.map { if (it.id == target.id) it.copy(tracks = merged) else it }
                withContext(Dispatchers.IO) { playlistStore.save(next) }; _playlists.value = next
            }
            _notice.value = "已添加 ${merged.size - before.size} 首，跳过 ${result.tracks.size - (merged.size - before.size)} 首重复，${result.failed} 个读取失败"
        }
    }
    fun importJson(uri: Uri) = scope.launch {
        try {
            val decoded = withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri) ?: error("无法打开文件")
                val text = input.bufferedReader().use { reader ->
                    val output = StringBuilder(); val chunk = CharArray(8192)
                    while (true) { val n = reader.read(chunk); if (n < 0) break; output.append(chunk, 0, n); require(output.length <= 5_000_000) }
                    output.toString()
                }
                PortablePlaylist.decode(text)
            }
            save(decoded.first, decoded.second)
            _notice.value = "已导入 ${decoded.second.size} 首在线曲目"
        } catch (e: Exception) { if (e is CancellationException) throw e; _notice.value = "无法读取歌单，请选择有效的 JSON 歌单文件" }
    }
    fun export(uri: Uri, playlist: Playlist) = scope.launch {
        try {
            withContext(Dispatchers.IO) {
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入文件")
                output.bufferedWriter().use { it.write(PortablePlaylist.encode(playlist.name, playlist.tracks)) }
            }
            _notice.value = "歌单已导出；跳过 ${playlist.tracks.count { it.onlineKey == null }} 首仅存本机的音频"
        } catch (e: Exception) { if (e is CancellationException) throw e; _notice.value = "导出失败，请重试" }
    }
    fun exportById(uri: Uri, id: String) = scope.launch {
        initial.join()
        val playlist = if (id == FAVORITES) Playlist(FAVORITES, "我喜欢", 0, _favorites.value)
            else _playlists.value.firstOrNull { it.id == id }
        if (playlist == null) _notice.value = "歌单已不存在，未导出"
        else export(uri, playlist).join()
    }
    companion object { const val FAVORITES = "kinara-favorites" }
}
