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

/** 单次取词的预算；超时即视为这次失败，交给退避重试与跨源兜底。 */
private const val LYRIC_BUDGET_MS = 6000L

class MainViewModel @JvmOverloads constructor(
    app: Application,
    private val access: com.kinara.music.online.OnlineAccess = com.kinara.music.online.OnlineAccess.Default,
    /** 本地媒体库扫描器。抽成参数是为了能在单测里注入假实现，验证"不重复全量扫描"。 */
    private val scanTracks: suspend (android.content.Context) -> List<Track> = { com.kinara.music.data.LocalLibrary.scan(it) },
    private val networkAvailable: () -> Boolean = { com.kinara.music.data.NetworkState.isOnline(app) },
) : AndroidViewModel(app) {

    private val prefs = KinaraPrefs.get(app)

    private val _settings = MutableStateFlow(prefs.settings())
    val settings: StateFlow<KinaraSettings> = _settings

    // ---------- 本地媒体库 ----------
    // 原先这份状态挂在 MainActivity 的字段 + lifecycleScope 上：旋转屏幕时 Activity 重建，
    // 状态回到 Loading，onResume 于是又触发一次完整的 MediaStore 全量重扫，
    // 大曲库下表现为每次转屏都卡一下。放进 ViewModel 后它随配置变更存活，扫描只做一次。
    private val _library = MutableStateFlow<com.kinara.music.ui.LibraryUiState>(com.kinara.music.ui.LibraryUiState.Loading)
    val library: StateFlow<com.kinara.music.ui.LibraryUiState> = _library
    private var scanJob: Job? = null

    /**
     * 加载本地曲库。[force] 为真时强制重扫（用户主动刷新、或刚拿到权限）。
     * 已经有 Ready/Empty 结果且当前没有扫描在跑时直接复用，避免重复全量扫描。
     */
    fun loadLibrary(force: Boolean = false) {
        val current = _library.value
        val settled = current is com.kinara.music.ui.LibraryUiState.Ready || current is com.kinara.music.ui.LibraryUiState.Empty
        if (!force && settled && scanJob?.isActive != true) return
        scanJob?.cancel()
        _library.value = com.kinara.music.ui.LibraryUiState.Loading
        scanJob = viewModelScope.launch {
            try {
                val tracks = scanTracks(getApplication())
                _library.value = if (tracks.isEmpty()) com.kinara.music.ui.LibraryUiState.Empty
                    else com.kinara.music.ui.LibraryUiState.Ready(tracks)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _library.value = if (e is SecurityException) com.kinara.music.ui.LibraryUiState.Denied
                    else com.kinara.music.ui.LibraryUiState.Failed("请确认音频文件仍在手机上，然后重试。")
            }
        }
    }

    /** 没有读取音频权限：停掉在跑的扫描并进入 Denied，不再反复重扫。 */
    fun markLibraryDenied() {
        scanJob?.cancel()
        _library.value = com.kinara.music.ui.LibraryUiState.Denied
    }


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

    /**
     * 加载歌词。
     *
     * [actual] 是实际播放来源的曲目，[original] 是曲库里那首。两者在"换源回退"时会不同。
     * 身份一律取 original：旧实现用 actual，导致回退到另一个音源时 identity 一变，
     * 已经取到的歌词被 KinaraApp 按 identity 过滤掉——用户看到的就是"歌还在放，歌词突然没了"。
     * 取词顺序则是 actual 优先，失败再试 original（同一个缓存命中，几乎零成本）。
     */
    fun loadLyric(actual: Track?, original: Track? = null) {
        lyricJob?.cancel()
        val gen = ++lyricGeneration
        val identity = com.kinara.music.data.lyricIdentity(original ?: actual)
        val previous = _lyrics.value
        // 同一条录音就保留已有歌词，避免重新进入时闪一下空白。
        _lyrics.value = if (previous.identity == identity) previous else LyricState(identity)
        val candidates = listOfNotNull(actual, original).distinctBy { it.onlineKey }
        val key = candidates.firstOrNull()?.onlineKey ?: return
        lyricJob = viewModelScope.launch {
            val cached = lyricsCache.read(key)
            if (gen == lyricGeneration && cached != null) {
                _lyrics.value = LyricState(identity, cached.lines)
                return@launch
            }
            if (!networkAvailable()) return@launch
            var document = com.kinara.music.online.LyricDocument()
            for (candidate in candidates) {
                document = fetchLyric(candidate) ?: continue
                if (document.lines.isNotEmpty()) break
            }
            if (gen == lyricGeneration && document.lines.isNotEmpty()) {
                _lyrics.value = LyricState(identity, document.lines)
                lyricsCache.write(key, document)
            }
        }
    }

    /**
     * 单次取词带预算，失败退避重试一次。
     * 旧实现没有超时也没有重试：一次网络抖动（netease 富接口 + 回落老接口最坏 16s）就会被
     * 当成"这首歌没有歌词"，而且同一首歌不会自动重试，用户只能换歌再切回来。
     */
    private suspend fun fetchLyric(track: Track): com.kinara.music.online.LyricDocument? {
        repeat(2) { attempt ->
            val document = try {
                kotlinx.coroutines.withTimeoutOrNull(LYRIC_BUDGET_MS) { access.lyricDocument(track, _settings.value) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
            if (document != null && document.lines.isNotEmpty()) return document
            if (attempt == 0 && networkAvailable()) kotlinx.coroutines.delay(1200)
        }
        return null
    }
}
