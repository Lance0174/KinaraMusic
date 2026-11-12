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

/** 播放进度落盘的节流周期；暂停/切歌会越过节流立刻写。 */
private const val PERSIST_INTERVAL_MS = 10_000L

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
    val speed: Float = 1f,
    /** 冷启动从落盘快照恢复出来、但还没开始播；界面用它给一次性说明。 */
    val restored: Boolean = false,
    val playbackSource: String? = null,
    val playbackBitrate: String? = null,
    val actualTrack: Track? = null,
    val recovering: Boolean = false,
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
    /** 上次落盘进度的时间戳（elapsedRealtime）。 */
    private var lastPersistAt = 0L

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val before = _ui.value
            val count = player.mediaItemCount
            val decoded = if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_MEDIA_METADATA_CHANGED))
                (0 until count).mapNotNull {
                    player.getMediaItemAt(it).mediaMetadata.extras?.getString("kinara.track")?.let(::decodeTrackLine)
                }
            else null
            // 只有整条时间线都能解出曲目时才整体替换队列。旧写法用 mapNotNull 直接替换，
            // 任何一个条目缺 extras 都会让 queue 比时间线短，而界面是拿 queue 的下标回调
            // playAt(index) 的——下标一错位就会出现"点 A 却播了 B"。
            val tracks = if (decoded != null && decoded.size == count) decoded else before.queue
            _ui.value = _ui.value.copy(
                queue = tracks,
                currentIndex = player.currentMediaItemIndex,
                playing = player.isPlaying,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                recovering = player.playerError?.errorCode in 2000..4999 && player.playWhenReady,
                // onEvents 在 onPlayerError 之后还会跑一次,原来这里会把 onPlayerError 里
                // 精心提取的具体错误文案覆盖成通用文案(两者在同一批 ListenerSet 里)。
                // 保留已有文案,只有真的换了错误/恢复时才重置。
                error = when {
                    player.playerError == null -> null
                    else -> _ui.value.error ?: "当前歌曲暂时无法播放"
                },
                repeatMode = player.repeatMode, shuffle = player.shuffleModeEnabled,
                // 速度是播放器状态的一部分，切歌后必须回读，否则界面会显示与实际不符。
                speed = player.playbackParameters.speed,
                // 一旦真的播起来，恢复提示就该消失。
                restored = if (player.isPlaying) false else _ui.value.restored,
                playbackSource = player.currentMediaItem?.mediaMetadata?.extras?.getString("kinara.playbackSource"),
                playbackBitrate = player.currentMediaItem?.mediaMetadata?.extras?.getString("kinara.playbackBitrate"),
                actualTrack = player.currentMediaItem?.mediaMetadata?.extras?.getString("kinara.actualTrack")?.let(::decodeTrackLine),
            )
            syncProgress()
            // 暂停与切歌是用户最可能离开 App 的时刻，这两个时机越过节流立刻落盘。
            if (before.playing && !_ui.value.playing) persistPlayback(force = true)
            if (before.currentIndex != _ui.value.currentIndex) persistPlayback(force = true)
        }
        override fun onPlayerError(error: PlaybackException) {
            // 不把可能携带签名/凭据的原始网络 URL 展示给用户。
            val message = generateSequence(error.cause) { it.cause }
                .filterIsInstance<java.io.IOException>()
                .mapNotNull { it.message }
                .firstOrNull { !it.contains("://") && it.any { ch -> ch.code > 127 } }
            _ui.value = _ui.value.copy(error = message ?: "当前歌曲暂时无法播放", playing = false, buffering = false,
                recovering = error.errorCode in 2000..4999 && controller?.playWhenReady == true)
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
        // buildAsync() 本身若同步抛错(如 SecurityException),旧写法会让 connecting 永久停在 true,
        // 之后每次 ensureController 都被上面的短路挡掉,播放控制永久失效且毫无提示。
        val future = try {
            MediaController.Builder(context, token).buildAsync()
        } catch (e: Exception) {
            connecting = false
            android.util.Log.w("kinara", "controller build failed", e)
            return
        }
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
                // 进程被杀后重进：service 里队列已空，用落盘状态把队列与位置摆好（暂停态，不出声）。
                if (connected.mediaItemCount == 0 && pendingQueue == null) restoreSaved(connected)
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
        val selectedTracks = tracks.map { t -> withPreferredBitrate(t, bitrate) }
        selectedTracks to selectedTracks.map(::mediaItemFor)
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

    /** 在线曲目统一用设置里的码率；本地曲目原样返回。 */
    private fun withPreferredBitrate(t: Track,
        bitrate: String = com.kinara.music.data.KinaraPrefs.get(getApplication()).settings().preferredBitrate): Track =
        if (!t.isLocal && t.onlineKey != null) t.copy(onlineKey = t.onlineKey.copy(bitrate = bitrate)) else t

    /** 队列条目：媒体项里塞入完整曲目，队列同步与冷启动还原都靠它。 */
    private fun mediaItemFor(t: Track): MediaItem {
        val uri = t.localPath?.let(::localPlaybackUri)
            ?: t.onlineKey?.let { android.net.Uri.parse(OnlineMediaUri.encode(t)) }
        return MediaItem.Builder().setMediaId(t.mediaId).setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
                .setArtworkUri(com.kinara.music.data.safeCoverUrl(t.coverUrl)?.let(android.net.Uri::parse))
                .setExtras(Bundle().apply { putString("kinara.track", encodeTrackLine(t)) })
                .build()).build()
    }

    /**
     * 下一首播放：插到当前曲目之后。紧跟其后已经是同一首就不重复插入。
     * 队列为空或还没连上控制端时退化为直接开播，避免点下去毫无反应。
     */
    fun playNext(track: Track) {
        val c = controller
        if (c == null || c.mediaItemCount == 0 || c.currentMediaItemIndex < 0) { playQueue(listOf(track), 0); return }
        val target = c.currentMediaItemIndex + 1
        if (target < c.mediaItemCount && c.getMediaItemAt(target).mediaId == track.mediaId) return
        c.addMediaItem(target, mediaItemFor(withPreferredBitrate(track)))
    }

    /**
     * 队列内移动。用 moveMediaItem 而不是重建列表：重建会换掉 MediaSource，
     * 正在播的那一首会被重新解析、明显卡一下。移动不改变当前曲目身份，播放不中断。
     */
    fun moveInQueue(from: Int, to: Int) {
        val c = controller ?: return
        if (from !in 0 until c.mediaItemCount || to !in 0 until c.mediaItemCount || from == to) return
        if (!c.availableCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return
        c.moveMediaItem(from, to)
    }

    fun moveInQueueBy(index: Int, delta: Int) = moveInQueue(index, index + delta)

    fun removeFromQueue(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.removeMediaItem(index)
    }

    /** 持久化当前队列与进度；暂停、切歌时立即写，其余时间按节流周期写。 */
    private fun persistPlayback(force: Boolean) {
        val ui = _ui.value
        if (ui.queue.isEmpty() || ui.currentIndex !in ui.queue.indices) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastPersistAt < PERSIST_INTERVAL_MS) return
        lastPersistAt = now
        PlaybackPersistence.save(getApplication(), ui.queue, ui.currentIndex, controller?.currentPosition ?: 0L)
    }

    /**
     * 冷启动还原上次播放：把队列与位置摆好，**不自动出声**。
     * 刻意不调用 prepare()——在线曲目一 prepare 就会去解析直链，无网时冷启动会直接弹播放错误。
     * 保持在 IDLE 态，用户点播放时 togglePlayPause 的 prepare 分支会接着从这里开始。
     */
    private fun restoreSaved(c: MediaController) {
        val saved = PlaybackPersistence.load(getApplication()) ?: return
        if (saved.tracks.isEmpty()) return
        c.setMediaItems(saved.tracks.map { mediaItemFor(withPreferredBitrate(it)) }, saved.index, saved.positionMs)
        _ui.value = PlayerUiState(queue = saved.tracks, currentIndex = saved.index,
            repeatMode = c.repeatMode, shuffle = c.shuffleModeEnabled,
            // 恢复态必须自解释：否则播放键显示「播放」、按下去又没声音，用户会以为播放坏了。
            restored = true)
        _progress.value = PlayerProgress(saved.positionMs, saved.tracks[saved.index].durationMs,
            mediaId = saved.tracks[saved.index].mediaId,
            recordingIdentity = lyricIdentity(saved.tracks[saved.index]))
    }

    fun togglePlayPause() { controller?.let {
        // 出错后 Media3 仍保持 playWhenReady=true,旧写法先判 isPlaying/playWhenReady,
        // 于是第一下点击永远被当成"暂停",重试分支实际不可达,用户要点两下才重试。
        // 因此必须把错误判断提到最前;但仍在自动恢复(2xxx-4xxx)时,第一下点击应保持"暂停"语义。
        val error = it.playerError
        when {
            error != null && it.playWhenReady && error.errorCode in 2000..4999 -> it.pause()
            error != null -> it.sendCustomCommand(
                androidx.media3.session.SessionCommand(PlaybackService.RETRY_COMMAND, Bundle.EMPTY), Bundle.EMPTY)
            it.isPlaying || (it.playWhenReady && it.playbackState == Player.STATE_BUFFERING) -> it.pause()
            else -> { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() }
        }
    } }
    fun next() { controller?.let { val failed = it.playerError != null; it.seekToNextMediaItem(); if (failed) it.prepare() } }
    fun previous() { controller?.let { val failed = it.playerError != null; it.seekToPreviousMediaItem(); if (failed) it.prepare() } }
    fun playAt(index: Int) { controller?.let { c ->
        if (index in 0 until c.mediaItemCount) {
            // 与 playQueue 对齐：用户明确点了某一首，就不能让还在跑的上一次 playQueue
            // 随后用 setMediaItems 把这次选择覆盖掉。
            queueJob?.cancel()
            c.seekTo(index, 0); c.prepare(); c.play()
        }
    } }

    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs.coerceAtLeast(0)); syncProgress() }
    fun stop() { controller?.stop(); PlaybackPersistence.clear(getApplication()) }

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
        // 定时落盘（内部按 PERSIST_INTERVAL_MS 节流），保证进程被杀后还能接着上次的进度。
        persistPlayback(force = false)
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

    /**
     * 播放速度。这是「这一次听」的临时偏好，不写进设置，重启回落到 1.0×。
     *
     * 速度交给 Media3 的 PlaybackParameters，进度外推（PlayerProgress.speed）与
     * 动态视界的歌词时钟都会跟着走，所以不需要另外补偿任何时间轴。
     */
    fun setSpeed(speed: Float) {
        val value = speed.coerceIn(SPEED_MIN, SPEED_MAX)
        controller?.setPlaybackParameters(androidx.media3.common.PlaybackParameters(value))
        _ui.value = _ui.value.copy(speed = value)
    }

    fun searchLocal(all: List<Track>, q: String): List<Track> =
        sortForDisplay(filterTracks(all, q))

    internal companion object {
        const val SPEED_MIN = 0.5f
        const val SPEED_MAX = 2.0f
        /** 播放页提供的固定档位；不放开任意值，避免用户拖到一个音频一听就废的速度。 */
        val SPEED_STEPS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}
