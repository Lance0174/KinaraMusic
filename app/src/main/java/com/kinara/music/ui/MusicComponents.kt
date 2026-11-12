/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinara.music.data.Track
import com.kinara.music.ui.theme.KinaraGlassPanel
import com.kinara.music.ui.theme.LocalKinaraTheme
import com.kinara.music.ui.theme.kinaraShape

/** Read by every scrolling page so the final item can scroll above the floating playback dock. */
val LocalDockInset = staticCompositionLocalOf { 96.dp }

fun sourceLabel(source: String): String = when (source) {
    "local" -> "本地音乐"; "netease" -> "网易云音乐"; "qq" -> "QQ音乐"; "kuwo" -> "酷我音乐"
    "kugou" -> "酷狗音乐"; "bilibili" -> "哔哩哔哩"; "gd" -> "聚合音源"; "solara" -> "Solara"; else -> source
}

@Composable
fun PageTitle(title: String, subtitle: String, trailing: @Composable RowScope.() -> Unit = {}) {
    val c = LocalKinaraTheme.current.colors
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 18.dp, bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (LocalKinaraTheme.current.kind == com.kinara.music.ui.theme.KinaraThemeKind.INDUSTRIAL) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(18.dp, 4.dp).background(c.accentBright))
                    Spacer(Modifier.width(8.dp))
                    Text("KINARA / AUDIO", color = c.textDim, fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, letterSpacing = 1.5.sp)
                }
                Spacer(Modifier.height(6.dp))
            }
            Text(title, color = c.text, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            // subtitle 是调用方一直在传的参数,但之前从未渲染,页面标题区因此显得空旷。
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = c.textDim, fontSize = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.padding(top = 3.dp))
            }
        }
        trailing()
    }
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = LocalKinaraTheme.current.colors
    val focus = LocalFocusManager.current
    top.yukonga.miuix.kmp.basic.TextField(value, onValueChange, modifier = modifier.fillMaxWidth(), singleLine = true,
            label = placeholder, useLabelAsPlaceholder = true, cornerRadius = 16.dp,
            backgroundColor = c.panel, labelColor = c.textDim,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            leadingIcon = { Box(Modifier.padding(start = 16.dp, end = 10.dp)) { KinaraIcon(Glyph.SEARCH, modifier = Modifier.size(20.dp), tint = c.textDim) } },
            trailingIcon = if (value.isNotEmpty()) {{ IconButton(onClick = { onValueChange("") }) { KinaraIcon(Glyph.CLOSE, "清空搜索") } }} else null)
}

@Composable
fun TrackRow(track: Track, active: Boolean = false, favorite: Boolean = false, onClick: () -> Unit,
             onFavorite: (() -> Unit)? = null, onAdd: (() -> Unit)? = null, onRemove: (() -> Unit)? = null,
             onPlayNext: (() -> Unit)? = null, onMoveUp: (() -> Unit)? = null, onMoveDown: (() -> Unit)? = null,
             canMoveUp: Boolean = true, canMoveDown: Boolean = true,
             removeLabel: String = "从歌单移除", showSource: Boolean = true) {
    val c = LocalKinaraTheme.current.colors
    var menu by remember(track.mediaId) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(kinaraShape(16.dp))
        .background(if (active) c.accent.copy(alpha = 0.09f) else Color.Transparent)
        // 正在播放此前只用颜色区分,TalkBack 读不出哪一行在播,补一个可朗读的状态描述。
        .semantics { if (active) stateDescription = "正在播放" }
        .clickable(onClick = onClick).padding(start = 8.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        KinaraArtwork(track, 48.dp, corner = 10.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = if (active) c.accent else c.text, fontSize = 16.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(buildString {
                append(track.artist.ifBlank { "未知艺术家" })
                if (track.onlineKey != null && showSource) append(" · ${sourceLabel(track.source)}")
                else if (track.album.isNotBlank() && !showSource) append(" · ${track.album}")
                else if (track.durationMs > 0) append(" · ${formatMs(track.durationMs)}")
            }, color = c.textDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (favorite) KinaraIcon(Glyph.HEART_FILLED, "已收藏", Modifier.size(14.dp).padding(1.dp), c.accent)
        if (onFavorite != null || onAdd != null || onRemove != null ||
            onPlayNext != null || onMoveUp != null || onMoveDown != null) Box {
            IconButton(onClick = { menu = true }) { KinaraIcon(Glyph.MORE, "更多操作：${track.title}", tint = c.textDim) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (onPlayNext != null) DropdownMenuItem(text = { Text("下一首播放") },
                    leadingIcon = { KinaraIcon(Glyph.PLAY) }, onClick = { menu = false; onPlayNext() })
                if (onFavorite != null) DropdownMenuItem(text = { Text(if (favorite) "取消收藏" else "收藏歌曲") },
                    leadingIcon = { KinaraIcon(if (favorite) Glyph.HEART_FILLED else Glyph.HEART) }, onClick = { menu = false; onFavorite() })
                if (onAdd != null) DropdownMenuItem(text = { Text("加入歌单") }, leadingIcon = { KinaraIcon(Glyph.QUEUE) }, onClick = { menu = false; onAdd() })
                // 首行/末行不再把菜单项抽掉：项目数量随位置变化会让菜单高度跳动，
                // TalkBack 用户也会以为这个功能时有时无。改成可见但禁用。
                if (onMoveUp != null) DropdownMenuItem(text = { Text("上移一位") }, enabled = canMoveUp,
                    onClick = { menu = false; onMoveUp() })
                if (onMoveDown != null) DropdownMenuItem(text = { Text("下移一位") }, enabled = canMoveDown,
                    onClick = { menu = false; onMoveDown() })
                if (onRemove != null) DropdownMenuItem(text = { Text(removeLabel) }, onClick = { menu = false; onRemove() })
            }
        } else Spacer(Modifier.width(12.dp))
    }
}

/** 为可能重复的 mediaId 生成稳定且唯一的列表 key（下标不能进 key，否则重排后整张表会重建）。 */
internal fun stableRowKeys(ids: List<String>): List<String> {
    val seen = HashMap<String, Int>(ids.size)
    return ids.map { id ->
        val n = seen.getOrElse(id) { 0 }
        seen[id] = n + 1
        "$id#$n"
    }
}

@Composable
fun EmptyState(icon: Glyph, title: String, message: String, action: String? = null, onAction: () -> Unit = {}, modifier: Modifier = Modifier) {
    val c = LocalKinaraTheme.current.colors
    Column(modifier.fillMaxSize().padding(horizontal = 32.dp).padding(bottom = LocalDockInset.current),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        KinaraGlassPanel(modifier = Modifier.size(80.dp), shape = RoundedCornerShape(26.dp), contentAlignment = Alignment.Center) {
            KinaraIcon(icon, modifier = Modifier.size(34.dp), tint = c.accent)
        }
        Spacer(Modifier.height(22.dp))
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = c.text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(message, color = c.textDim, fontSize = 14.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(22.dp))
            Button(onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
        }
    }
}

@Composable
fun CollectionActions(count: Int, onPlay: () -> Unit, onShuffle: (() -> Unit)? = null) {
    val c = LocalKinaraTheme.current.colors
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$count 首歌曲", color = c.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onPlay, enabled = count > 0) {
            KinaraIcon(Glyph.PLAY, tint = if (count > 0) c.accent else c.textDim, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); Text("播放全部")
        }
        if (onShuffle != null) IconButton(onClick = onShuffle, enabled = count > 0) { KinaraIcon(Glyph.SHUFFLE, "随机播放", tint = c.textDim) }
    }
}

@Composable
fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalKinaraTheme.current.colors
    FilterChip(selected, onClick, label = { Text(text, fontSize = 14.sp) }, modifier = modifier.heightIn(min = 48.dp),
        shape = kinaraShape(24.dp),
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.accent, selectedLabelColor = if (c.isDark) c.bgBottom else Color.White,
            containerColor = c.panelRaised.copy(alpha = 0.5f), labelColor = c.textDim), border = null)
}
