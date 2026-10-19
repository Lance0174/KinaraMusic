/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 应用级 ViewModel：
 *  - 设置：点选即写回（自动保存，无保存按钮），StateFlow 下发；
 *  - 在线搜索：900ms 防抖（上轮性能专项结论）、代次号丢弃晚到结果、多源并行；
 *  - 在线播放地址由 PlaybackService 在加载时解析。
 */
package com.kinara.music

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kinara.music.data.KinaraPrefs
import com.kinara.music.data.KinaraSettings
import com.kinara.music.data.Track
import com.kinara.music.online.GdSource
import com.kinara.music.online.OnlinePlayback
import com.kinara.music.online.OnlineRepository
import com.kinara.music.online.OnlineSearchState
import com.kinara.music.online.OnlineTrack
import com.kinara.music.ui.theme.KinaraDarkMode
import com.kinara.music.ui.theme.KinaraThemeKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class MainViewModel @JvmOverloads constructor(
    app: Application,
    private val access: com.kinara.music.online.OnlineAccess = com.kinara.music.online.OnlineAccess.Default,
    private val networkAvailable: () -> Boolean = { com.kinara.music.data.NetworkState.isOnline(app) },
) : AndroidViewModel(app) {

    private val prefs = KinaraPrefs.get(app)

    private val _settings = MutableStateFlow(prefs.settings())
    val settings: StateFlow<KinaraSettings> = _settings


    private val _online = MutableStateFlow(OnlineSearchState())
    val online: StateFlow<OnlineSearchState> = _online

    private var searchJob: Job? = null
    private var importJob: Job? = null
    private var importGeneration = 0L
    private var lyricJob: Job? = null
    private var lyricGeneration = 0L
    private val lyricsCache = com.kinara.music.online.LyricsCache(java.io.File(app.filesDir, "lyrics-v1"))
    private var searchGeneration = 0L

    /** 任何设置改动：先落盘、后发流——单一事实来源在持久层。 */
    fun update(transform: (KinaraSettings) -> KinaraSettings) {
        val previous = _settings.value
        val next = transform(previous)
        if (next == previous) return
        prefs.save(next) // apply 异步写盘；主线程按用户操作顺序提交完整事务
        _settings.value = next
        if (next.sources != previous.sources && _online.value.keyword.isNotBlank()) searchOnline(_online.value.keyword)
    }

    fun setTheme(kind: KinaraThemeKind) = update { it.copy(theme = kind) }
    fun setDarkMode(mode: KinaraDarkMode) = update { it.copy(darkMode = mode) }
    fun setBitrate(bitrate: String) = update { it.copy(preferredBitrate = bitrate.takeIf { b -> b in listOf("128", "192", "320", "flac") } ?: "320") }
    fun setCacheLimit(gb: Int) = update { it.copy(cacheLimitGb = gb.coerceIn(1, 5)) }
    fun setSource(name: String, enabled: Boolean) = update { s ->
        val t = s.sources
        s.copy(sources = when (name) {
            "netease" -> t.copy(netease = enabled); "kuwo" -> t.copy(kuwo = enabled)
            "kugou" -> t.copy(kugou = enabled); "qq" -> t.copy(qq = enabled)
            "bilibili" -> t.copy(bilibili = enabled); "solara" -> t.copy(solara = enabled)
            "gdFallback" -> t.copy(gdFallback = enabled)
            else -> t
        })
    }

    /** 在线搜索（防抖在调用方或此处均可——集中此处，UI 只管打字）。 */
    fun searchOnline(keyword: String) {
        searchJob?.cancel()
        val gen = ++searchGeneration
        closeImport()
        if (keyword.isBlank()) {
            _online.value = OnlineSearchState()
            return
        }
        if (!networkAvailable()) {
            _online.value = OnlineSearchState(phase = OnlineSearchState.Phase.ERROR, keyword = keyword,
                results = listOf(com.kinara.music.online.OnlineResult("network", errorMessage = "当前已离线，请联网后重试")))
            return
        }
        _online.value = OnlineSearchState(phase = OnlineSearchState.Phase.LOADING, keyword = keyword)
        searchJob = viewModelScope.launch {
            delay(900) // 在线防抖：与本地 250ms 分离（上轮性能专项参数）
            _online.value = OnlineSearchState(phase = OnlineSearchState.Phase.LOADING, keyword = keyword)
            access.search(keyword, gen, _settings.value) { result, resultGen ->
                // 各音源从 IO 返回主协程后归约；核对实时代次，禁止旧回调覆盖新输入。
                val cur = _online.value
                _online.value = OnlineSearchState.reduce(cur, result, resultGen, searchGeneration)
            }
            // 全部源返回后 finalize
            if (gen == searchGeneration) {
                _online.value = OnlineSearchState.finalize(_online.value)
            }
        }
    }

    // ---------- 歌单导入 ----------
    data class ImportUiState(
        val importing: Boolean = false,
        val title: String = "",
        val message: String = "",
        val tracks: List<OnlineTrack> = emptyList(),
    )

    private val _import = MutableStateFlow(ImportUiState())
    val import: StateFlow<ImportUiState> = _import

    fun closeImport() {
        importJob?.cancel()
        importGeneration++
        _import.value = ImportUiState()
    }

    /** 导入可退出；取消或新搜索后，晚到的结果不得重新打开旧歌单。 */
    private fun importPlaylist(id: String, qq: Boolean) {
        closeImport()
        searchJob?.cancel()
        searchGeneration++
        val gen = importGeneration
        val title = if (qq) "QQ歌单" else "网易云歌单"
        if (!networkAvailable()) {
            _import.value = ImportUiState(title = title, message = "当前已离线，请联网后重试")
            return
        }
        _import.value = ImportUiState(importing = true, title = title, message = "读取中…")
        importJob = viewModelScope.launch {
            val r = if (qq) com.kinara.music.online.PlaylistImporter.importQQ(id)
                else com.kinara.music.online.PlaylistImporter.importNetease(id)
            if (gen == importGeneration) _import.value = ImportUiState(false, r.title, r.message, r.tracks)
        }
    }

    fun importNetease(playlistId: String) = importPlaylist(playlistId, false)
    fun importQQ(playlistId: String) = importPlaylist(playlistId, true)

    // ---------- 歌词 ----------
    data class LyricState(val identity: String? = null, val lines: List<com.kinara.music.online.LyricLine> = emptyList())
    private val _lyrics = MutableStateFlow(LyricState())
    val lyrics: StateFlow<LyricState> = _lyrics

    /** 每次曲目变化都清空歌词；取消 + 代次核对避免旧歌词覆盖新曲目。 */
    fun loadLyric(track: Track?) {
        lyricJob?.cancel()
        val gen = ++lyricGeneration
        val identity = com.kinara.music.data.lyricIdentity(track)
        _lyrics.value = LyricState(identity)
        val key = track?.onlineKey ?: return
        lyricJob = viewModelScope.launch {
            val cached = lyricsCache.read(key)
            if (gen == lyricGeneration && cached != null) _lyrics.value = LyricState(identity, cached.lines)
            if (!networkAvailable()) return@launch
            val document = try { access.lyricDocument(track, _settings.value) }
                catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; com.kinara.music.online.LyricDocument() }
            if (gen == lyricGeneration) {
                if (document.lines.isNotEmpty()) {
                    _lyrics.value = LyricState(identity, document.lines)
                    lyricsCache.write(key, document)
                }
            }
        }
    }
}
