/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 播放状态机：UI 只读 StateFlow，控制经 Media3 Controller 发往 PlaybackService。
 * 纪律（前会话教训）：
 *  - 持久层为唯一事实来源：播放中状态不进 Composable 局部变量；
 *  - 旧异步请求不得覆盖新曲目（切歌竞态，folia/Alger 经验：代次号隔离）；
 *  - 队列调整保留当前曲目。
 */
package com.kinara.music.player

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import com.kinara.music.data.encodeTrackLine
import com.kinara.music.data.decodeTrackLine
import com.kinara.music.online.OnlineMediaUri
import androidx.media3.common.PlaybackException
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.kinara.music.data.FavoritesStore
import com.kinara.music.data.Playlist
import com.kinara.music.data.PlaylistStore
import com.kinara.music.data.addTracksToPlaylist
import com.kinara.music.data.createPlaylist
import com.kinara.music.data.deletePlaylist
import com.kinara.music.data.removeTrackFromPlaylist
import com.kinara.music.data.renamePlaylist
import com.kinara.music.data.Track
import com.kinara.music.data.lyricIdentity
import com.kinara.music.data.filterTracks
import com.kinara.music.data.sortForDisplay
import com.kinara.music.data.toggleFavorite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job

data class PlayerUiState(
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val buffering: Boolean = false,
    val error: String? = null,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val playbackSource: String? = null,
    val playbackBitrate: String? = null,
    val actualTrack: Track? = null,
) {
    val current: Track? get() = queue.getOrNull(currentIndex)
}

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui
    private val _progress = MutableStateFlow(PlayerProgress())
    val progress: StateFlow<PlayerProgress> = _progress

    val library = com.kinara.music.data.LibraryRepository(app, viewModelScope)
    val favorites = library.favorites
    val playlists = library.playlists
    fun toggleFavorite(track: Track) = library.toggle(track)
    fun isFavorite(mediaId: String): Boolean = favorites.value.any { it.mediaId == mediaId }
    fun createPlaylist(name: String) = library.create(name)
    fun renamePlaylist(id: String, name: String) = library.rename(id, name)
    fun deletePlaylist(id: String) = library.delete(id)
    fun addToPlaylist(playlistId: String, track: Track) = library.add(playlistId, listOf(track))
    fun removeFromPlaylist(playlistId: String, mediaId: String) = library.remove(playlistId, mediaId)

    private var controller: MediaController? = null

    // 连接完成前只保留用户最后一次选择。
    private var pendingQueue: Pair<List<Track>, Int>? = null
    private var queueJob: Job? = null
    private var disposed = false

    /** controller 连接进行中标记（防 onResume 风暴重复 buildAsync）。 */
    @Volatile private var connecting = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val tracks = if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_MEDIA_METADATA_CHANGED))
                (0 until player.mediaItemCount).mapNotNull {
                player.getMediaItemAt(it).mediaMetadata.extras?.getString("kinara.track")?.let(::decodeTrackLine)
            } else _ui.value.queue
            _ui.value = _ui.value.copy(
                queue = tracks,
                currentIndex = player.currentMediaItemIndex,
                playing = player.isPlaying,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                error = if (player.playerError == null) null else _ui.value.error,
                repeatMode = player.repeatMode, shuffle = player.shuffleModeEnabled,
                playbackSource = player.currentMediaItem?.mediaMetadata?.extras?.getString("kinara.playbackSource"),
                playbackBitrate = player.currentMediaItem?.mediaMetadata?.extras?.getString("kinara.playbackBitrate"),
                actualTrack = player.currentMediaItem?.mediaMetadata?.extras?.getString("kinara.actualTrack")?.let(::decodeTrackLine),
            )
            syncProgress()
        }
        override fun onPlayerError(error: PlaybackException) {
            // 不把可能携带签名/凭据的原始网络 URL 展示给用户。
            val message = generateSequence(error.cause) { it.cause }
                .filterIsInstance<java.io.IOException>()
                .mapNotNull { it.message }
                .firstOrNull { !it.contains("://") && it.any { ch -> ch.code > 127 } }
            _ui.value = _ui.value.copy(error = message ?: "播放失败，请重试或选择其他音源", playing = false, buffering = false)
        }
    }

    /**
     * 连接 PlaybackService（幂等；Activity onResume 调用）。
     * 死锁教训（真机白屏卡死根因）：MediaController future 的完成回调 post 到主线程，
     * 若在主线程 future.get() 同步等待，回调永远无法投递 → 自我死锁。
     * 必须用 addListener 回调或把 get() 挪到 IO 线程；此处用回调式。
     */
    fun ensureController() {
        if (controller != null) return
        if (connecting) return
        connecting = true
        val context = getApplication<Application>()
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                val connected = future.get()
                if (disposed) { connected.release(); return@addListener }
                controller = connected
                connected.addListener(listener)
                val savedQueue = (0 until connected.mediaItemCount).mapNotNull {
                    connected.getMediaItemAt(it).mediaMetadata.extras?.getString("kinara.track")?.let(::decodeTrackLine)
                }
                _ui.value = PlayerUiState(queue = savedQueue, currentIndex = connected.currentMediaItemIndex,
                    playing = connected.isPlaying, buffering = connected.playbackState == Player.STATE_BUFFERING,
                    repeatMode = connected.repeatMode, shuffle = connected.shuffleModeEnabled,
                    playbackSource = connected.currentMediaItem?.mediaMetadata?.extras?.getString("kinara.playbackSource"),
                    playbackBitrate = connected.currentMediaItem?.mediaMetadata?.extras?.getString("kinara.playbackBitrate"),
                    actualTrack = connected.currentMediaItem?.mediaMetadata?.extras?.getString("kinara.actualTrack")?.let(::decodeTrackLine))
                syncProgress()
                pendingQueue?.let { (tracks, index) -> pendingQueue = null; playQueue(tracks, index) }
            } catch (e: Exception) {
                // 连接失败不崩应用；下次 onResume 重试
                android.util.Log.w("kinara", "controller connect failed", e)
            } finally {
                connecting = false
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(context))
    }

    override fun onCleared() {
        disposed = true
        controller?.release()
        controller = null
        super.onCleared()
    }

    /** 本地、在线、收藏和导入歌单共用队列，最新选择立即替换旧加载。 */
    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        val index = startIndex.coerceIn(tracks.indices)
        val c = controller
        if (c == null) {
            pendingQueue = tracks to index
            ensureController()
            return
        }
        queueJob?.cancel()
        c.pause()
        val bitrate = com.kinara.music.data.KinaraPrefs.get(getApplication()).settings().preferredBitrate
        queueJob = viewModelScope.launch {
        val (preparedTracks, items) = withContext(Dispatchers.Default) {
        val selectedTracks = tracks.map { t -> if (!t.isLocal && t.onlineKey != null) t.copy(onlineKey = t.onlineKey.copy(bitrate = bitrate)) else t }
        selectedTracks to selectedTracks.map { t ->
            val uri = t.localPath?.let(::localPlaybackUri)
                ?: t.onlineKey?.let { android.net.Uri.parse(OnlineMediaUri.encode(t)) }
            MediaItem.Builder().setMediaId(t.mediaId).setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
                    .setArtworkUri(com.kinara.music.data.safeCoverUrl(t.coverUrl)?.let(android.net.Uri::parse))
                    .setExtras(Bundle().apply { putString("kinara.track", encodeTrackLine(t)) })
                    .build()).build()
        }
        }
        if (controller !== c) return@launch
        _ui.value = PlayerUiState(queue = preparedTracks, currentIndex = index, buffering = true,
            repeatMode = c.repeatMode, shuffle = c.shuffleModeEnabled)
        _progress.value = PlayerProgress(0, preparedTracks[index].durationMs,
            mediaId = preparedTracks[index].mediaId, recordingIdentity = lyricIdentity(preparedTracks[index]))
        c.setMediaItems(items, index, 0)
        c.prepare()
        c.play()
        }
    }

    fun togglePlayPause() { controller?.let {
        if (it.isPlaying || (it.playWhenReady && it.playbackState == Player.STATE_BUFFERING)) it.pause()
        else if (it.playerError != null) it.sendCustomCommand(androidx.media3.session.SessionCommand(PlaybackService.RETRY_COMMAND, Bundle.EMPTY), Bundle.EMPTY)
        else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() }
    } }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPreviousMediaItem() }
    fun playAt(index: Int) { controller?.let { c ->
        if (index in 0 until c.mediaItemCount) { c.seekTo(index, 0); c.prepare(); c.play() }
    } }
    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs.coerceAtLeast(0)); syncProgress() }
    fun stop() { controller?.stop() }

    /** Activity STARTED 期间每 500ms 同步；切曲事件另行立即同步。 */
    fun syncProgress() {
        val c = controller ?: return
        val current = _ui.value.current
        val actual = _ui.value.actualTrack ?: current
        _progress.value = PlayerProgress(
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: (_ui.value.current?.durationMs ?: 0),
            sampledAtMs = android.os.SystemClock.elapsedRealtime(), advancing = c.isPlaying,
            speed = c.playbackParameters.speed,
            mediaId = c.currentMediaItem?.mediaId,
            recordingIdentity = lyricIdentity(actual),
        )
    }

    fun saveImportedPlaylist(name: String, tracks: List<Track>) {
        library.save(name, tracks)
    }

    fun setSleepTimer(minutes: Int, afterTrack: Boolean) {
        controller?.sendCustomCommand(androidx.media3.session.SessionCommand(SleepTimer.COMMAND, Bundle.EMPTY),
            Bundle().apply { putInt("minutes", minutes); putBoolean("afterTrack", afterTrack) })
    }

    fun cycleMode() { controller?.let { c ->
        when {
            c.shuffleModeEnabled -> { c.shuffleModeEnabled = false; c.repeatMode = Player.REPEAT_MODE_OFF }
            c.repeatMode == Player.REPEAT_MODE_OFF -> c.repeatMode = Player.REPEAT_MODE_ALL
            c.repeatMode == Player.REPEAT_MODE_ALL -> c.repeatMode = Player.REPEAT_MODE_ONE
            else -> { c.repeatMode = Player.REPEAT_MODE_ALL; c.shuffleModeEnabled = true }
        }
    } }

    fun searchLocal(all: List<Track>, q: String): List<Track> =
        sortForDisplay(filterTracks(all, q))
}
