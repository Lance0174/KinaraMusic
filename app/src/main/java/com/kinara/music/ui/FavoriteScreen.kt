/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kinara.music.data.Track

@Composable
fun FavoriteScreen(favorites: List<Track>, activeId: String?, onPlay: (List<Track>, Int) -> Unit,
    onGoOnline: () -> Unit, onFavorite: (Track) -> Unit = {}, onAdd: (Track) -> Unit = {}) {
    if (favorites.isEmpty()) EmptyState(Glyph.HEART, "留住喜欢的旋律", "在歌曲菜单或播放页点按收藏，\n喜欢的音乐就会出现在这里。", "去搜索音乐", onGoOnline)
    else LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = LocalDockInset.current)) {
        item { CollectionActions(favorites.size, { onPlay(favorites, 0) }, { onPlay(favorites.shuffled(), 0) }) }
        itemsIndexed(favorites, key = { _, t -> t.mediaId }) { index, t -> TrackRow(t, t.mediaId == activeId, true,
            { onPlay(favorites, index) }, { onFavorite(t) }, { onAdd(t) }) }
    }
}
