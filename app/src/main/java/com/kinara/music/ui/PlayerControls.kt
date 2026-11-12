/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0.
 * A quiet information hierarchy and a compact transport, inspired by music player conventions.
 */
package com.kinara.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.kinara.music.data.PlayerVisualMode
import com.kinara.music.data.Track
import com.kinara.music.player.LocalPlayerProgress
import com.kinara.music.player.PlayerUiState
import com.kinara.music.ui.theme.KinaraThemeKind
import com.kinara.music.ui.theme.LocalKinaraTheme
import com.kinara.music.ui.theme.kinaraShape

@Composable
internal fun PlayerTrackDetails(track: Track, favorite: Boolean, onFavorite: () -> Unit) {
    val c = LocalKinaraTheme.current.colors
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(track.title, color = c.text, fontSize = 23.sp, lineHeight = 30.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Text(listOf(track.artist, track.album).filter { it.isNotBlank() }.distinct().joinToString(" · "),
                color = c.textDim, fontSize = 13.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onFavorite) {
            KinaraIcon(if (favorite) Glyph.HEART_FILLED else Glyph.HEART,
                if (favorite) "取消收藏" else "收藏歌曲", Modifier.size(23.dp), if (favorite) c.accent else c.textDim)
        }
    }
}

/** Progress is read in this small subtree, leaving metadata and transport independent of ticks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerTimeline(player: PlayerUiState, onSeek: (Long) -> Unit, showStatus: Boolean = true) {
    val c = LocalKinaraTheme.current.colors
    var dragging by remember(player.current?.mediaId) { mutableStateOf(false) }
    var dragged by remember(player.current?.mediaId) { mutableLongStateOf(0) }
    val progress = LocalPlayerProgress.current?.value
    val duration = (progress?.durationMs ?: player.durationMs).coerceAtLeast(0)
    val shown = (if (dragging) dragged else progress?.positionMs ?: player.positionMs).coerceIn(0, duration)
    // A fixed, small status slot keeps errors/buffering from displacing the seek and play targets.
    // heightIn (not height) so a long message can wrap instead of being clipped at large font scales.
    if (showStatus) Box(Modifier.fillMaxWidth().heightIn(min = 24.dp), contentAlignment = Alignment.BottomStart) {
        // 四种状态互斥，按优先级取第一个成立的：换源 > 错误 > 加载 > 恢复提示。
        // 「已恢复上次播放」必须自解释，否则播放键显示"播放"、按下去又没声音，用户会以为播坏了。
        val status = when {
            player.recovering -> "正在尝试其他来源…"
            player.error != null -> player.error.orEmpty()
            player.buffering -> "正在加载音频…"
            player.restored -> "已恢复上次播放 · 点按继续"
            else -> ""
        }
        Text(status, color = c.textDim,
            fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    Slider(value = if (duration > 0) shown.toFloat() / duration else 0f, enabled = duration > 0,
        onValueChange = { dragging = true; dragged = (it * duration).toLong() },
        onValueChangeFinished = { if (dragging) onSeek(dragged.coerceIn(0, duration)); dragging = false },
        modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = "播放进度" },
        thumb = {
            Box(Modifier.size(8.dp).background(if (duration > 0) c.text else c.textDim, CircleShape))
        },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                val middle = size.height / 2f
                drawLine(c.text.copy(alpha = 0.14f), Offset(0f, middle), Offset(size.width, middle),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(c.text.copy(alpha = 0.9f), Offset(0f, middle), Offset(size.width * state.value, middle),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        })
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatMs(shown), color = c.textDim, fontSize = 11.sp, letterSpacing = 0.5.sp)
        Text(formatMs(duration), color = c.textDim, fontSize = 11.sp, letterSpacing = 0.5.sp)
    }
}

/** Landscape deliberately exposes a single transport control. Seeks commit on release. */
@Composable
internal fun ImmersiveTimeline(player: PlayerUiState, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.semantics { contentDescription = "横屏播放进度" }) {
        PlayerTimeline(player, onSeek, showStatus = false)
    }
}

@Composable
internal fun PlaybackControls(player: PlayerUiState, onToggle: () -> Unit, onPrevious: () -> Unit,
    onNext: () -> Unit, onSeek: (Long) -> Unit, onMode: () -> Unit, onQueue: () -> Unit) {
    val theme = LocalKinaraTheme.current
    val c = theme.colors
    PlayerTimeline(player, onSeek)
    val modeLabel = when {
        player.shuffle -> "随机播放"
        player.repeatMode == Player.REPEAT_MODE_ONE -> "单曲循环"
        player.repeatMode == Player.REPEAT_MODE_ALL -> "列表循环"
        else -> "顺序播放"
    }
    val modeGlyph = when {
        player.shuffle -> Glyph.SHUFFLE
        player.repeatMode == Player.REPEAT_MODE_ONE -> Glyph.REPEAT_ONE
        player.repeatMode == Player.REPEAT_MODE_ALL -> Glyph.REPEAT
        else -> Glyph.ORDER
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onMode, Modifier.semantics { stateDescription = modeLabel }) {
            KinaraIcon(modeGlyph, "切换播放顺序", Modifier.size(21.dp), c.textDim)
        }
        IconButton(onPrevious) { KinaraIcon(Glyph.PREVIOUS, "上一曲", Modifier.size(27.dp)) }
        val playShape = if (theme.kind == KinaraThemeKind.INDUSTRIAL) kinaraShape(32.dp) else CircleShape
        val ink = c.accent
        IconButton(onToggle, Modifier.size(64.dp).clip(playShape)
            .background(ink)) {
            KinaraIcon(if (player.recovering || player.playing) Glyph.PAUSE else if (player.error != null) Glyph.REFRESH else Glyph.PLAY,
                if (player.recovering || player.playing) "暂停" else if (player.error != null) "重试播放" else "播放", Modifier.size(29.dp),
                if (theme.kind == KinaraThemeKind.INDUSTRIAL) androidx.compose.ui.graphics.Color(0xff16191c) else androidx.compose.ui.graphics.Color.White)
        }
        // 列表循环/随机模式下,最后一首之后仍会回到开头,所以不能只看 index 是否到了末尾,
        // 否则"下一曲"会在一种正常播放模式下变灰失效。
        val canNext = player.queue.size > 1 &&
            (player.currentIndex < player.queue.lastIndex || player.shuffle ||
                player.repeatMode == Player.REPEAT_MODE_ALL)
        IconButton(onNext, enabled = canNext) {
            KinaraIcon(Glyph.NEXT, "下一曲", Modifier.size(27.dp), if (canNext) c.text else c.textDim.copy(alpha = 0.35f))
        }
        IconButton(onQueue) { KinaraIcon(Glyph.QUEUE, "打开播放队列", Modifier.size(22.dp), c.textDim) }
    }
}

@Composable
internal fun PlayerModeSelector(mode: PlayerVisualMode, onChange: (PlayerVisualMode) -> Unit) {
    val c = LocalKinaraTheme.current.colors
    val shape = kinaraShape(24.dp)
    Row(Modifier.widthIn(max = 190.dp).fillMaxWidth().heightIn(min = 48.dp).selectableGroup()
        .pointerInput(mode) {
            var distance = 0f
            detectHorizontalDragGestures(
                onDragStart = { distance = 0f },
                onHorizontalDrag = { change, delta -> change.consume(); distance += delta },
                onDragEnd = {
                    if (kotlin.math.abs(distance) > 24.dp.toPx()) onChange(if (distance < 0) PlayerVisualMode.LYRICS else PlayerVisualMode.COVER)
                })
        },
        verticalAlignment = Alignment.CenterVertically) {
        PlayerVisualMode.entries.forEach { item ->
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(shape)
                .selectable(item == mode, role = Role.Tab, onClick = { onChange(item) }), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(if (item == mode) 6.dp else 4.dp).background(
                        if (item == mode) c.text else c.textDim.copy(alpha = 0.4f), CircleShape))
                    Text(item.label, fontSize = 11.sp, fontWeight = if (item == mode) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (item == mode) c.text else c.textDim)
                }
            }
        }
    }
}
