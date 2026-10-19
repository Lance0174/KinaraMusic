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
        }
        trailing()
    }
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = LocalKinaraTheme.current.colors
    val focus = LocalFocusManager.current
    KinaraGlassPanel(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        TextField(value, onValueChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyLarge) },
            leadingIcon = { KinaraIcon(Glyph.SEARCH, tint = c.textDim) },
            trailingIcon = if (value.isNotEmpty()) {{ IconButton(onClick = { onValueChange("") }) { KinaraIcon(Glyph.CLOSE, "清空搜索") } }} else null,
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = c.text, unfocusedTextColor = c.text, focusedPlaceholderColor = c.textDim, unfocusedPlaceholderColor = c.textDim))
    }
}

@Composable
fun TrackRow(track: Track, active: Boolean = false, favorite: Boolean = false, onClick: () -> Unit,
             onFavorite: (() -> Unit)? = null, onAdd: (() -> Unit)? = null, onRemove: (() -> Unit)? = null,
             showSource: Boolean = true) {
    val c = LocalKinaraTheme.current.colors
    var menu by remember(track.mediaId) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(kinaraShape(16.dp))
        .background(if (active) c.accent.copy(alpha = 0.09f) else Color.Transparent)
        .clickable(onClick = onClick).padding(start = 8.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        KinaraArtwork(track, 52.dp, corner = 12.dp)
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
        if (onFavorite != null || onAdd != null || onRemove != null) Box {
            IconButton(onClick = { menu = true }) { KinaraIcon(Glyph.MORE, "更多操作：${track.title}", tint = c.textDim) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (onFavorite != null) DropdownMenuItem(text = { Text(if (favorite) "取消收藏" else "收藏歌曲") },
                    leadingIcon = { KinaraIcon(if (favorite) Glyph.HEART_FILLED else Glyph.HEART) }, onClick = { menu = false; onFavorite() })
                if (onAdd != null) DropdownMenuItem(text = { Text("加入歌单") }, leadingIcon = { KinaraIcon(Glyph.QUEUE) }, onClick = { menu = false; onAdd() })
                if (onRemove != null) DropdownMenuItem(text = { Text("从歌单移除") }, onClick = { menu = false; onRemove() })
            }
        } else Spacer(Modifier.width(12.dp))
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
