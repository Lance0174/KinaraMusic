/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0.
 * Now playing: cover/lyrics, release-to-seek progress, accessible controls and real queue.
 */
package com.kinara.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinara.music.online.LrcParser
import com.kinara.music.online.LyricLine
import com.kinara.music.player.PlayerUiState
import com.kinara.music.player.PlayerViewModel
import com.kinara.music.data.KinaraSettings
import com.kinara.music.data.Track
import com.kinara.music.data.PlayerVisualMode
import com.kinara.music.data.LyricRenderer
import com.kinara.music.data.lyricIdentity
import com.kinara.music.data.lyricDelayFor
import com.kinara.music.online.LyricTiming
import com.kinara.music.ui.theme.LocalKinaraTheme
import com.kinara.music.ui.theme.kinaraSpec
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 当前是否应当走"动态视界全屏渲染"分支。
 *
 * 抽成纯函数是为了能用单测钉住这条产品约束：只有"正在看歌词页"且"渲染器是动态视界"时
 * 才铺满整屏；其余渲染器仍然待在翻页容器里，否则左右滑动切封面的手势会失效。
 */
internal fun isFullscreenVisual(mode: PlayerVisualMode, renderer: LyricRenderer): Boolean =
    mode != PlayerVisualMode.COVER && renderer == LyricRenderer.FOLIA_LUMIERE

/**
 * 速度文案。只输出有意义的小数位：1.0× / 1.25× / 1.5×，
 * 不能直接 toString——浮点数会漏出 1.2500001× 这种尾巴。
 */
internal fun speedLabel(value: Float): String {
    val hundredths = Math.round(value * 100f)
    return when {
        hundredths % 100 == 0 -> "${hundredths / 100}.0×"
        hundredths % 10 == 0 -> "${hundredths / 100}.${(hundredths % 100) / 10}×"
        else -> "${hundredths / 100}.${hundredths % 100}×"
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PlayScreen(player: PlayerUiState, lyrics: List<LyricLine>, onTogglePlay: () -> Unit,
    onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit, onClose: () -> Unit = {},
    favorite: Boolean = false, onFavorite: () -> Unit = {}, onQueuePick: (Int) -> Unit = {},
    onAdd: () -> Unit = {}, onDownload: () -> Unit = {}, onTimer: () -> Unit = {},
    onDiagnostics: () -> Unit = {}, onMode: () -> Unit = {}, settings: KinaraSettings = KinaraSettings(),
    onVisualMode: ((PlayerVisualMode) -> Unit)? = null, onLyricSettings: () -> Unit = {},
    onLyricOffset: (String, Long) -> Unit = { _, _ -> }, onPlayNext: (Track) -> Unit = {},
    onMoveInQueue: (Int, Int) -> Unit = { _, _ -> }, onRemoveFromQueue: (Int) -> Unit = {},
    onSpeed: (Float) -> Unit = {}, lockedLandscape: Boolean = false,
    onEnterLandscape: () -> Unit = {}, onExitLandscape: () -> Unit = {}, initialQueue: Boolean = false) {
    val c = LocalKinaraTheme.current.colors
    val track = player.current ?: return
    val actual = player.actualTrack ?: track
    val lyricSettings = remember(settings, actual) { settings.copy(lyricDelayMs = settings.lyricDelayFor(actual)) }
    var standaloneMode by rememberSaveable { mutableStateOf(PlayerVisualMode.COVER) }
    val initialMode = if (onVisualMode == null) standaloneMode else settings.playerVisualMode
    val pager = rememberPagerState(initialPage = initialMode.ordinal) { 2 }
    val scope = rememberCoroutineScope()
    val saveMode by rememberUpdatedState(onVisualMode)
    // 权威的模式状态。
    // 原因：动态视界全屏渲染时翻页容器不挂载，而 PagerState 在"没有挂载 Pager"的情况下
    // 根本不会推进（DetachedPagerStateTest 实测 scrollToPage / animateScrollToPage 都不动），
    // 若仍以 pager.currentPage 作为唯一事实来源，"返回封面"和底部模式入口会直接失效。
    // 因此这里用独立状态掌握当前模式，pager 只负责"用户滑动"这一路输入。
    var modeState by rememberSaveable { mutableStateOf(initialMode) }
    var modeBeforeLandscape by rememberSaveable { mutableStateOf(initialMode) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            val mode = PlayerVisualMode.entries[page]
            modeState = mode
            if (saveMode == null) standaloneMode = mode else saveMode?.invoke(mode)
        }
    }
    val visualMode = modeState
    val showLyrics = visualMode != PlayerVisualMode.COVER
    val dynamic = showLyrics && settings.lyricRenderer != LyricRenderer.CLASSIC
    // 动态视界不再挤在翻页容器里：它直接铺满整个窗口（含状态栏/导航栏区域）渲染，
    // 顶栏与底部控制条作为半透明浮层叠在上面。其余渲染器仍留在翻页容器内。
    val fullscreenVisual = isFullscreenVisual(visualMode, settings.lyricRenderer)
    // Visual lyrics use bright controls over the renderer, regardless of the application theme.
    val immersive = fullscreenVisual || lockedLandscape
    com.kinara.music.ui.theme.KinaraSystemBars(immersive || c.isDark)
    val chromeSpec = if (immersive) kinaraSpec(LocalKinaraTheme.current.kind, true) else null
    // 从全屏退回时翻页容器会重新挂载，此时把 pager 落到权威模式对应的页，
    // 否则会出现"已经是封面模式、界面却还停在歌词页"的错位。
    LaunchedEffect(fullscreenVisual, visualMode) {
        if (!fullscreenVisual && pager.currentPage != visualMode.ordinal) pager.scrollToPage(visualMode.ordinal)
    }
    val changeVisualMode: (PlayerVisualMode) -> Unit = { mode ->
        modeState = mode
        if (saveMode == null) standaloneMode = mode else saveMode?.invoke(mode)
        // 只有翻页容器在挂载状态时才用动画翻页；全屏时它推不动，靠 modeState 生效即可。
        if (!isFullscreenVisual(mode, settings.lyricRenderer)) scope.launch { pager.animateScrollToPage(mode.ordinal) }
    }
    // Back restores the mode from before landscape; a second Back can leave visual lyrics.
    BackHandler(enabled = fullscreenVisual || lockedLandscape) {
        if (lockedLandscape) { changeVisualMode(modeBeforeLandscape); onExitLandscape() }
        else if (showLyrics) changeVisualMode(PlayerVisualMode.COVER) else onClose()
    }
    var queue by rememberSaveable { mutableStateOf(initialQueue) }
    var menu by remember { mutableStateOf(false) }
    var calibration by remember { mutableStateOf(false) }
    var speedPicker by remember { mutableStateOf(false) }
    LaunchedEffect(lockedLandscape) {
        if (lockedLandscape) { queue = false; menu = false; calibration = false; speedPicker = false }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > 600.dp && maxWidth > maxHeight
        val compact = maxHeight < 620.dp && !landscape
        val visual = fullscreenVisual || lockedLandscape
        // One stable renderer call site for portrait and immersive landscape. Insets and chrome
        // change around it; the WebView and its WebGL context stay mounted.
        if (visual) {
            FoliaLyrics(lyrics, player, lyricSettings, onSeek, onCanvasTap = onTogglePlay, immersive = lockedLandscape)
        } else {
            Box(Modifier.fillMaxSize().background(if (!c.isDark &&
                LocalKinaraTheme.current.kind == com.kinara.music.ui.theme.KinaraThemeKind.DEFAULT)
                Color(0xFFF2EFE9) else c.bgBottom))
        }
        if (lockedLandscape) {
            CompositionLocalProvider(LocalKinaraTheme provides kinaraSpec(LocalKinaraTheme.current.kind, true)) {
                Box(Modifier.fillMaxSize().testTag("immersive_visual")) {
                    ImmersiveTimeline(player, onSeek, Modifier.align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .fillMaxWidth(0.86f).padding(bottom = 18.dp))
                }
            }
        } else {
            if (fullscreenVisual) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.28f), 0.2f to Color.Transparent,
                0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.68f))))
            val page: @Composable () -> Unit = {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ if (showLyrics) changeVisualMode(PlayerVisualMode.COVER) else onClose() }) {
                            KinaraIcon(if (showLyrics) Glyph.BACK else Glyph.DOWN,
                                if (showLyrics) "返回封面" else "收起播放器")
                        }
                        Text("正在播放", fontSize = 12.sp, color = LocalKinaraTheme.current.colors.textDim,
                            textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                        if (settings.lyricRenderer == LyricRenderer.FOLIA_LUMIERE) {
                            TextButton({ modeBeforeLandscape = visualMode; changeVisualMode(PlayerVisualMode.LYRICS); onEnterLandscape() },
                                modifier = Modifier.semantics { contentDescription = "进入横屏动态视界" }) { Text("横屏", fontSize = 12.sp) }
                        }
                        Box {
                            IconButton({ menu = true }) { KinaraIcon(Glyph.MORE, "播放选项") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("播放队列 · ${player.queue.size} 首") }, { menu = false; queue = true })
                                DropdownMenuItem({ Text("歌词显示设置") }, { menu = false; onLyricSettings() })
                                DropdownMenuItem({ Text("歌词校准") }, { menu = false; calibration = true })
                                HorizontalDivider()
                                DropdownMenuItem({ Text("加入歌单") }, { menu = false; onAdd() })
                                if (track.onlineKey != null && !track.isLocal) DropdownMenuItem({ Text("下载音频") }, { menu = false; onDownload() })
                                DropdownMenuItem({ Text("定时关闭") }, { menu = false; onTimer() })
                                DropdownMenuItem({ Text("播放速度 · ${speedLabel(player.speed)}") }, { menu = false; speedPicker = true })
                                DropdownMenuItem({ Text("播放诊断") }, { menu = false; onDiagnostics() })
                            }
                        }
                    }
                    val artwork: @Composable () -> Unit = {
                        if (fullscreenVisual) Box(Modifier.fillMaxSize())
                        else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().testTag("player_pages"), key = { it }) { pageIndex ->
                                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    if (pageIndex == 0) {
                                        val extent = minOf(maxWidth - 56.dp, maxHeight - 20.dp, 420.dp).coerceAtLeast(1.dp)
                                        KinaraArtwork(track, extent, Modifier.size(extent).clickable(onClickLabel = "查看歌词") {
                                            changeVisualMode(PlayerVisualMode.LYRICS)
                                        }.semantics { contentDescription = "查看歌词" }, corner = 20.dp)
                                    } else when (settings.lyricRenderer) {
                                        LyricRenderer.CLASSIC -> LiveLyrics(lyrics, player, onSeek, lyricSettings)
                                        LyricRenderer.GLOW -> DynamicLyrics(lyrics, player, lyricSettings, onSeek)
                                        LyricRenderer.FOLIA_LUMIERE -> Text("动态视界", color = c.textDim)
                                    }
                                }
                            }
                            PlayerModeSelector(visualMode, changeVisualMode)
                        }
                    }
                    val controls: @Composable () -> Unit = {
                        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().navigationBarsPadding()
                            .padding(horizontal = 28.dp, vertical = if (compact) 8.dp else 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            PlayerTrackDetails(track, favorite, onFavorite)
                            PlaybackControls(player, onTogglePlay, onPrevious, onNext, onSeek, onMode, { queue = true })
                            if (fullscreenVisual) PlayerModeSelector(visualMode, changeVisualMode)
                            else Row(Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onLyricSettings) { Text(settings.lyricRenderer.label, fontSize = 12.sp) }
                                Text(player.playbackSource?.let(::sourceLabel) ?: sourceLabel(track.source), color = c.textDim, fontSize = 11.sp)
                                TextButton({ menu = true }) { Text("更多", fontSize = 12.sp) }
                            }
                        }
                    }
                    if (landscape && !fullscreenVisual) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).fillMaxHeight()) { artwork() }
                        Box(Modifier.weight(1f).verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) { controls() }
                    } else if (compact && !fullscreenVisual) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.height(210.dp)) { artwork() }; controls()
                    } else Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f)) { artwork() }; controls()
                    }
                }
            }
            if (chromeSpec != null) CompositionLocalProvider(LocalKinaraTheme provides chromeSpec) { page() } else page()
        }
    }
    if (calibration) {
        val identity = lyricIdentity(actual)!!
        val offset = settings.lyricOffsets[identity] ?: 0L
        AlertDialog(onDismissRequest = { calibration = false }, title = { Text("当前歌曲歌词校准") }, text = {
            Column {
                Text(actual.title)
                Text("${sourceLabel(actual.source)} · 仅对这个版本生效", fontSize = 12.sp, color = c.textDim)
                Spacer(Modifier.height(16.dp))
                Text("${if (offset > 0) "+" else ""}${offset / 1000f} 秒", fontSize = 24.sp)
                Text("歌词晚了选提前，歌词早了选延后。", fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ onLyricOffset(identity, offset - 500) }) { Text("提前 0.5 秒") }
                    TextButton({ onLyricOffset(identity, offset + 500) }) { Text("延后 0.5 秒") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ onLyricOffset(identity, offset - 100) }) { Text("提前 0.1 秒") }
                    TextButton({ onLyricOffset(identity, offset + 100) }) { Text("延后 0.1 秒") }
                }
                Text("全局偏移另计：${settings.lyricDelayMs} ms", fontSize = 12.sp, color = c.textDim)
            }
        }, confirmButton = { TextButton({ calibration = false }) { Text("完成") } },
            dismissButton = { TextButton({ onLyricOffset(identity, 0) }) { Text("重置此歌曲") } })
    }
    if (speedPicker) {
        // 只给固定档位，不放开任意值：介于两档之间的速度对听感没有意义，还会让"上次用了多少"变得难记。
        AlertDialog(onDismissRequest = { speedPicker = false }, title = { Text("播放速度") }, text = {
            Column {
                Text("语速变化不影响音高，歌词时钟会跟着速度走。", fontSize = 13.sp, color = c.textDim)
                Spacer(Modifier.height(12.dp))
                PlayerViewModel.SPEED_STEPS.forEach { step ->
                    val selected = kotlin.math.abs(player.speed - step) < 0.001f
                    TextButton({ onSpeed(step); speedPicker = false }, Modifier.fillMaxWidth()) {
                        Text(if (selected) "✓ ${speedLabel(step)}" else speedLabel(step),
                            color = if (selected) c.accent else c.text)
                    }
                }
            }
        }, confirmButton = { TextButton({ speedPicker = false }) { Text("关闭") } })
    }
    if (queue) ModalBottomSheet(onDismissRequest = { queue = false }, containerColor = c.panelRaised) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.72f)) {
            Text("播放队列", style = MaterialTheme.typography.titleMedium, color = c.text, modifier = Modifier.padding(horizontal = 24.dp))
            Text("${player.queue.size} 首歌曲 · 用每行右侧菜单可调整顺序", color = c.textDim,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 24.dp, top = 5.dp, bottom = 12.dp))
            // 从当前曲目开始显示，否则长队列永远停在第一首，用户看不到正在播的那首。
            val queueState = rememberLazyListState(initialFirstVisibleItemIndex = player.currentIndex.coerceAtLeast(0))
            // key 里带下标是错的：一旦重排，每行身份都会变，整表被重建、动画与点击跟着错位。
            // 这里用"曲目 id + 第几次出现"生成稳定槽位（队列允许同一首歌重复出现）。
            val keys = remember(player.queue) { stableRowKeys(player.queue.map { it.mediaId }) }
            LazyColumn(state = queueState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                itemsIndexed(player.queue, key = { index, _ -> keys.getOrElse(index) { "slot-$index" } }) { index, t ->
                    TrackRow(t, active = index == player.currentIndex,
                        onClick = { onQueuePick(index); queue = false },
                        onPlayNext = { onPlayNext(t) },
                        onMoveUp = { onMoveInQueue(index, index - 1) }, canMoveUp = index > 0,
                        onMoveDown = { onMoveInQueue(index, index + 1) }, canMoveDown = index < player.queue.lastIndex,
                        onRemove = { onRemoveFromQueue(index) }, removeLabel = "移出队列")
                }
            }
        }
    }
}

@Composable
private fun LiveLyrics(lyrics: List<LyricLine>, player: PlayerUiState, onSeek: (Long) -> Unit, settings: KinaraSettings) {
    val audio = com.kinara.music.player.rememberLyricClock(player)
    val position = remember(audio, settings.lyricDelayMs) { derivedStateOf { LyricTiming.position(audio.value, settings.lyricDelayMs) } }
    key(player.actualTrack?.onlineKey ?: player.current?.mediaId) {
        LyricView(lyrics, position, { onSeek((it + settings.lyricDelayMs).coerceAtLeast(0)) }, settings)
    }
}

@Composable
private fun LyricView(lyrics: List<LyricLine>, position: State<Long>, onSeek: (Long) -> Unit, settings: KinaraSettings) {
    val c = LocalKinaraTheme.current.colors
    if (lyrics.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("暂时没有歌词\n专心听一会儿吧", color = c.textDim, fontSize = 17.sp, lineHeight = 30.sp, textAlign = TextAlign.Center)
    } else {
        val active by remember(lyrics, position) { derivedStateOf { LrcParser.currentIndex(lyrics, position.value) } }
        val state = rememberLazyListState()
        LaunchedEffect(active) {
            val target = (active - 1).coerceAtLeast(0)
            if (settings.reduceLyricMotion || active < 0) state.scrollToItem(target) else state.animateScrollToItem(target)
        }
        LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 40.dp), modifier = Modifier.fillMaxSize()) {
            // 歌词列表在换歌时整体替换，key 让 Compose 复用而不是重建每一行。
            itemsIndexed(lyrics, key = { index, line -> "$index:${line.timeMs}" }) { index, line ->
                TextButton(onClick = { onSeek(line.timeMs) }, Modifier.fillMaxWidth()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(line.text, color = if (index == active) c.accent else c.textDim, fontSize = if (index == active) 23.sp else 18.sp,
                        fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 6.dp))
                    if (settings.showTranslation) line.translation?.let { Text(it, color = c.textDim, fontSize = 14.sp, textAlign = TextAlign.Center) }
                    if (settings.showRomanization) line.romanization?.let { Text(it, color = c.textDim, fontSize = 12.sp, textAlign = TextAlign.Center) }
                    }
                }
            }
        }
    }
}
