/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinara.music.data.Track
import com.kinara.music.player.PlayerUiState
import com.kinara.music.ui.theme.LocalKinaraTheme
import com.kinara.music.ui.theme.KinaraGlassPanel

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data object Denied : LibraryUiState
    data object Empty : LibraryUiState
    data class Failed(val message: String) : LibraryUiState
    data class Ready(val tracks: List<Track>) : LibraryUiState
}

@Composable
fun LibraryScreen(state: LibraryUiState, player: PlayerUiState, query: String, onQueryChange: (String) -> Unit,
    visibleTracks: List<Track>, onPlay: (List<Track>, Int) -> Unit, onRequestPermission: () -> Unit,
    onToggleFavorite: (Track) -> Unit = {}, favoriteIds: Set<String> = emptySet(), onAddToPlaylist: (Track) -> Unit = {},
    onPlayNext: (Track) -> Unit = {}, onOpenPlayer: () -> Unit = {}, onRefresh: () -> Unit = onRequestPermission) {
    val c = LocalKinaraTheme.current.colors
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var collection by rememberSaveable(tab, query) { mutableStateOf<String?>(null) }
    val groups = remember(visibleTracks, tab) {
        if (tab == 1) visibleTracks.groupBy { it.album.ifBlank { "未知专辑" } }
        else visibleTracks.groupBy { it.artist.ifBlank { "未知艺术家" } }
    }
    val tracks = collection?.let { groups[it] }.orEmpty().takeIf { collection != null } ?: visibleTracks
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        SearchField(query, onQueryChange, "搜索音乐库")
        Spacer(Modifier.height(14.dp))
        when (state) {
            LibraryUiState.Loading -> Box(Modifier.fillMaxSize().padding(bottom = LocalDockInset.current), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            LibraryUiState.Denied -> EmptyState(Glyph.LIBRARY, "让音乐回到身边", "允许读取音频后，就能在这里播放手机里的歌曲。", "读取本地音乐", onRequestPermission)
            is LibraryUiState.Failed -> EmptyState(Glyph.REFRESH, "音乐库暂时无法读取", state.message, "重新读取", onRequestPermission)
            LibraryUiState.Empty -> EmptyState(Glyph.NOTE, "你的音乐，从这里开始", "将音频文件保存到手机后，刷新音乐库即可收听。", "刷新音乐库", onRequestPermission)
            is LibraryUiState.Ready -> if (visibleTracks.isEmpty()) {
                EmptyState(Glyph.SEARCH, "没有找到这首歌", "试试歌曲名或艺术家名字的其他写法。", "清空搜索", { onQueryChange("") })
            } else LazyColumn(modifier = Modifier.padding(bottom = LocalDockInset.current).testTag("local_tracks"), contentPadding = PaddingValues(bottom = 8.dp)) {
                if (query.isBlank() && collection == null) {
                    item(key = "listen") {
                        val featured = player.current ?: visibleTracks.first()
                        KinaraGlassPanel(modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text(if (player.current == null) "从喜欢的一首开始" else "接着听", color = c.textDim, fontSize = 12.sp)
                                    Text(featured.title, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                                    Text(featured.artist, color = c.textDim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 5.dp))
                                    TextButton({ if (player.current == null) onPlay(visibleTracks, 0) else onOpenPlayer() },
                                        contentPadding = PaddingValues(top = 10.dp, end = 12.dp)) {
                                        KinaraIcon(Glyph.PLAY, modifier = Modifier.size(16.dp), tint = c.accent)
                                        Text(if (player.current == null) "开始播放" else "打开播放器", fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                                    }
                                }
                                KinaraArtwork(featured, 96.dp, corner = 14.dp)
                            }
                        }
                        Text("音乐拾光", fontSize = 18.sp, color = c.text, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 24.dp, bottom = 14.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(visibleTracks.take(8), key = { it.mediaId }) { track ->
                                Column(Modifier.width(112.dp).clickable { onPlay(visibleTracks, visibleTracks.indexOf(track)) }) {
                                    KinaraArtwork(track, 112.dp, corner = 14.dp)
                                    Text(track.title, color = c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 8.dp))
                                    Text(track.artist, color = c.textDim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }
                item(key = "tabs") {
                    top.yukonga.miuix.kmp.basic.TabRow(listOf("歌曲", "专辑", "歌手"), tab,
                        height = 48.dp, onTabSelected = { tab = it; collection = null })
                    if (collection != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ collection = null }) { KinaraIcon(Glyph.BACK, "返回分类") }
                        Text(collection.orEmpty(), color = c.text, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    else if (tab == 0) Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { CollectionActions(tracks.size, { onPlay(tracks, 0) }, { onPlay(tracks.shuffled(), 0) }) }
                        IconButton(onRefresh) { KinaraIcon(Glyph.REFRESH, "刷新音乐库", tint = c.textDim, modifier = Modifier.size(19.dp)) }
                    }
                }
                if (tab == 0 || collection != null) itemsIndexed(tracks, key = { _, t -> t.mediaId }) { index, track ->
                    TrackRow(track, active = player.current?.mediaId == track.mediaId, favorite = track.mediaId in favoriteIds,
                        onClick = { onPlay(tracks, index) }, onFavorite = { onToggleFavorite(track) }, onAdd = { onAddToPlaylist(track) },
                        onPlayNext = { onPlayNext(track) })
                } else items(groups.keys.toList(), key = { it }) { name ->
                    val group = groups.getValue(name)
                    KinaraGlassPanel(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { collection = name }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            KinaraArtwork(group.first(), 56.dp, corner = 12.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                Text(name, color = c.text, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${group.size} 首歌曲", color = c.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp))
                            }
                            KinaraIcon(Glyph.RIGHT, tint = c.textDim)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 时长格式化。超过一小时必须走 h:mm:ss——旧实现只会输出「分:秒」，
 * 于是 75 分钟的音频会显示成 "75:12"，读起来像 75 秒而不是 1 小时 15 分。
 */
fun formatMs(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
