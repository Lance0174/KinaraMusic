/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0.
 * Now playing: cover/lyrics, release-to-seek progress, accessible controls and real queue.
 */
package com.kinara.music.ui

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
import androidx.compose.ui.platform.LocalDensity
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
import com.kinara.music.data.KinaraSettings
import com.kinara.music.data.PlayerVisualMode
import com.kinara.music.data.LyricRenderer
import com.kinara.music.data.lyricIdentity
import com.kinara.music.data.lyricDelayFor
import com.kinara.music.online.LyricTiming
import com.kinara.music.ui.theme.LocalKinaraTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayScreen(player: PlayerUiState, lyrics: List<LyricLine>, onTogglePlay: () -> Unit,
    onNext: () -> Unit, onPrevious: () -> Unit, onSeek: (Long) -> Unit, onClose: () -> Unit = {},
    favorite: Boolean = false, onFavorite: () -> Unit = {}, onQueuePick: (Int) -> Unit = {},
    onAdd: () -> Unit = {}, onDownload: () -> Unit = {}, onTimer: () -> Unit = {},
    onDiagnostics: () -> Unit = {}, onMode: () -> Unit = {}, settings: KinaraSettings = KinaraSettings(),
    onVisualMode: ((PlayerVisualMode) -> Unit)? = null, onLyricSettings: () -> Unit = {},
    onLyricOffset: (String, Long) -> Unit = { _, _ -> }) {
    val c = LocalKinaraTheme.current.colors
    val track = player.current ?: return
    val actual = player.actualTrack ?: track
    val lyricSettings = remember(settings, actual) { settings.copy(lyricDelayMs = settings.lyricDelayFor(actual)) }
    var standaloneMode by rememberSaveable { mutableStateOf(PlayerVisualMode.COVER) }
    val pager = rememberPagerState(initialPage = (if (onVisualMode == null) standaloneMode else settings.playerVisualMode).ordinal) { 2 }
    val scope = rememberCoroutineScope()
    val saveMode by rememberUpdatedState(onVisualMode)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect {
            val mode = PlayerVisualMode.entries[it]
            if (saveMode == null) standaloneMode = mode else saveMode?.invoke(mode)
        }
    }
    val visualMode = PlayerVisualMode.entries[pager.currentPage]
    val changeVisualMode: (PlayerVisualMode) -> Unit = { scope.launch { pager.animateScrollToPage(it.ordinal) } }
    val showLyrics = visualMode != PlayerVisualMode.COVER
    val dynamic = showLyrics && settings.lyricRenderer != LyricRenderer.CLASSIC
    var queue by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var calibration by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > 600.dp && maxWidth > maxHeight
        val compact = maxHeight < 500.dp || (maxHeight < 700.dp && LocalDensity.current.fontScale > 1.3f)
        if (!showLyrics || dynamic) {
            // Artwork is the page, without a separate cover card or a second artwork decode.
            KinaraArtwork(track, maxOf(maxWidth, maxHeight), Modifier.fillMaxSize(), corner = 0.dp)
            val shade = if (dynamic) Brush.verticalGradient(
                0f to c.bgBottom.copy(alpha = 0.94f), 0.45f to c.bgBottom.copy(alpha = 0.88f),
                0.75f to c.bgBottom, 1f to c.bgBottom) else Brush.verticalGradient(
                0f to c.bgTop.copy(alpha = 0.78f), 0.34f to c.bgBottom.copy(alpha = 0.12f),
                0.64f to c.bgBottom.copy(alpha = 0.94f), 0.76f to c.bgBottom, 1f to c.bgBottom)
            Box(Modifier.fillMaxSize().background(shade))
        } else Box(Modifier.fillMaxSize().background(c.bgBottom))
        if (landscape) Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to Color.Transparent, 0.42f to Color.Transparent,
            0.58f to c.bgBottom.copy(alpha = 0.94f), 1f to c.bgBottom)))
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ if (showLyrics) changeVisualMode(PlayerVisualMode.COVER) else onClose() }) {
                    KinaraIcon(if (showLyrics) Glyph.BACK else Glyph.DOWN, if (showLyrics) "返回封面" else "收起播放器")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(player.playbackSource?.let { "已切换至${sourceLabel(it)}" +
                        if (it == "bilibili" && player.playbackBitrate == "128") " · 兼容音轨" else "" }
                        ?: sourceLabel(track.source), fontSize = 12.sp, color = c.textDim)
                }
                Box {
                    IconButton({ menu = true }) { KinaraIcon(Glyph.MORE, "播放选项") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("歌词显示设置") }, { menu = false; onLyricSettings() })
                        DropdownMenuItem({ Text("歌词校准") }, { menu = false; calibration = true })
                        DropdownMenuItem({ Text("加入歌单") }, { menu = false; onAdd() })
                        if (track.onlineKey != null && !track.isLocal) DropdownMenuItem({ Text("下载音频") }, { menu = false; onDownload() })
                        DropdownMenuItem({ Text("定时关闭") }, { menu = false; onTimer() })
                        DropdownMenuItem({ Text("播放诊断") }, { menu = false; onDiagnostics() })
                    }
                }
            }
            val artwork: @Composable () -> Unit = {
                HorizontalPager(pager, Modifier.fillMaxSize().testTag("player_pages"), key = { it }) { page ->
                    Box(Modifier.fillMaxSize().then(if (page == 0) Modifier.clickable(onClickLabel = "查看歌词") { changeVisualMode(PlayerVisualMode.LYRICS) }
                        .semantics { contentDescription = "查看歌词" } else Modifier), contentAlignment = Alignment.Center) {
                        if (page == 1 && pager.currentPage == 1) when (settings.lyricRenderer) {
                            LyricRenderer.CLASSIC -> LiveLyrics(lyrics, player, onSeek, lyricSettings)
                            LyricRenderer.GLOW -> DynamicLyrics(lyrics, player, lyricSettings, onSeek)
                            LyricRenderer.FOLIA_LUMIERE -> FoliaLyrics(lyrics, player, lyricSettings, onSeek)
                        }
                    }
                }
            }
            val controls: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PlayerTrackDetails(track, favorite, onFavorite)
                    PlaybackControls(player, onTogglePlay, onPrevious, onNext, onSeek, onMode, { queue = true })
                    PlayerModeSelector(visualMode, changeVisualMode)
                    Spacer(Modifier.height(8.dp))
                }
            }
            if (landscape) Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { artwork() }
                Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) { controls() }
            } else if (compact) Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Box(Modifier.height(if (showLyrics) 240.dp else 150.dp)) { artwork() }; controls()
            } else Column(Modifier.weight(1f)) {
                Box(Modifier.weight(1f)) { artwork() }; controls()
            }
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
    if (queue) ModalBottomSheet(onDismissRequest = { queue = false }, containerColor = c.panelRaised) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.72f)) {
            Text("播放队列", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.padding(horizontal = 24.dp))
            Text("${player.queue.size} 首歌曲", color = c.textDim, modifier = Modifier.padding(start = 24.dp, top = 5.dp, bottom = 12.dp))
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                itemsIndexed(player.queue, key = { index, t -> "$index:${t.mediaId}" }) { index, t ->
                    TrackRow(t, active = index == player.currentIndex, onClick = { onQueuePick(index) })
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
            itemsIndexed(lyrics) { index, line ->
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
