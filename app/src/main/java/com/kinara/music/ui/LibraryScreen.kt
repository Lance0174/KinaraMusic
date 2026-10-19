/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kinara.music.data.Track
import com.kinara.music.player.PlayerUiState

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
    onToggleFavorite: (Track) -> Unit = {}, favoriteIds: Set<String> = emptySet(), onAddToPlaylist: (Track) -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SearchField(query, onQueryChange, "搜索本地歌曲、艺术家")
        when (state) {
            LibraryUiState.Loading -> Box(Modifier.fillMaxSize().padding(bottom = LocalDockInset.current), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            LibraryUiState.Denied -> EmptyState(Glyph.LIBRARY, "让音乐回到身边", "允许读取音频后，就能在这里播放手机里的歌曲。", "读取本地音乐", onRequestPermission)
            is LibraryUiState.Failed -> EmptyState(Glyph.REFRESH, "音乐库暂时无法读取", state.message, "重新读取", onRequestPermission)
            LibraryUiState.Empty -> EmptyState(Glyph.NOTE, "你的音乐，从这里开始", "将音频文件保存到手机后，刷新音乐库即可收听。", "刷新音乐库", onRequestPermission)
            is LibraryUiState.Ready -> if (visibleTracks.isEmpty()) {
                EmptyState(Glyph.SEARCH, "没有找到这首歌", "试试歌曲名或艺术家名字的其他写法。", "清空搜索", { onQueryChange("") })
            } else LazyColumn(modifier = Modifier.testTag("local_tracks"), contentPadding = PaddingValues(bottom = LocalDockInset.current)) {
                item { CollectionActions(visibleTracks.size, { onPlay(visibleTracks, 0) }, { onPlay(visibleTracks.shuffled(), 0) }) }
                itemsIndexed(visibleTracks, key = { _, t -> t.mediaId }) { index, track ->
                    TrackRow(track, active = player.current?.mediaId == track.mediaId, favorite = track.mediaId in favoriteIds,
                        onClick = { onPlay(visibleTracks, index) }, onFavorite = { onToggleFavorite(track) }, onAdd = { onAddToPlaylist(track) })
                }
            }
        }
    }
}

fun formatMs(ms: Long): String { val s = ms.coerceAtLeast(0) / 1000; return "%d:%02d".format(s / 60, s % 60) }
