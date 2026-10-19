/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinara.music.online.OnlineSearchState
import com.kinara.music.online.OnlineTrack
import com.kinara.music.online.toTrack
import com.kinara.music.online.SOURCE_PRIORITY
import com.kinara.music.online.sourceRank
import com.kinara.music.online.sourceErrorText
import com.kinara.music.ui.theme.LocalKinaraTheme

@Composable
fun OnlineSearchScreen(state: OnlineSearchState, onPlay: (List<OnlineTrack>, Int) -> Unit, onRetry: () -> Unit,
    offline: Boolean = false, favoriteIds: Set<String> = emptySet(), onFavorite: (OnlineTrack) -> Unit = {},
    onAddToPlaylist: (OnlineTrack) -> Unit = {},
    enabledSources: List<String> = SOURCE_PRIORITY,
    onOpenSettings: () -> Unit = {}) {
    val c = LocalKinaraTheme.current.colors
    var platform by rememberSaveable { mutableStateOf("all") }
    var showErrors by remember { mutableStateOf(false) }
    val errors = state.results.filter { it.errorMessage != null }
    val selected = state.displayPlatform(platform, enabledSources)
    val tracks = state.displayTracks.filter { it.platform in enabledSources && (selected == "all" || it.platform == selected) }
    Column(Modifier.fillMaxSize()) {
        if (state.phase != OnlineSearchState.Phase.IDLE) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("全部", selected == "all", { platform = "all" })
                enabledSources.sortedBy(::sourceRank).forEach { source ->
                    ChoiceChip(sourceLabel(source), selected == source, { platform = source })
                }
            }
            if (selected != platform && platform in enabledSources) Text(
                "${sourceLabel(platform)}暂不可用，已显示${sourceLabel(selected)}结果", color = c.textDim, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
            if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp).height(2.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.searching) "正在寻找音乐…" else "${tracks.size} 个结果", color = c.textDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                if (errors.isNotEmpty()) TextButton({ showErrors = true }) { Text("${errors.size} 个来源暂不可用", fontSize = 12.sp) }
            }
        }
        when {
            enabledSources.isEmpty() -> EmptyState(Glyph.SETTINGS, "选择你的音乐来源", "开启至少一个音乐来源，\n就可以在这里搜索歌曲。", "前往设置", onOpenSettings)
            offline -> EmptyState(Glyph.SEARCH, "当前没有网络连接", "连接网络后继续搜索。\n本地音乐和收藏仍可在音乐库中查看。", "重新连接", onRetry)
            state.phase == OnlineSearchState.Phase.IDLE -> EmptyState(Glyph.SEARCH, "下一首，想听什么？", "搜索歌名、艺术家或 B站视频。\n把新的喜欢，放进自己的音乐库。")
            tracks.isNotEmpty() -> LazyColumn(modifier = Modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = LocalDockInset.current)) {
                items(tracks, key = { it.platform + it.id }) { t ->
                    TrackRow(t.toTrack("320"), favorite = t.toTrack("320").mediaId in favoriteIds,
                        onClick = { onPlay(tracks, tracks.indexOf(t)) }, onFavorite = { onFavorite(t) }, onAdd = { onAddToPlaylist(t) })
                }
            }
            state.searching -> Box(Modifier.fillMaxSize().padding(bottom = LocalDockInset.current), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.phase == OnlineSearchState.Phase.ERROR || errors.any { it.platform == selected } -> EmptyState(Glyph.REFRESH, "暂时无法获取音乐",
                sourceErrorText(errors.firstOrNull { it.platform == selected }?.errorMessage), "重试搜索", onRetry)
            else -> EmptyState(Glyph.SEARCH, "没有找到匹配结果", "试试其他关键词，或切换音乐来源。", "重新搜索", onRetry)
        }
    }
    if (showErrors) AlertDialog(onDismissRequest = { showErrors = false }, title = { Text("来源状态") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            errors.sortedBy { sourceRank(it.platform) }.forEach { error -> Text("${sourceLabel(error.platform)}：${sourceErrorText(error.errorMessage)}") }
        } }, confirmButton = { TextButton({ showErrors = false }) { Text("知道了") } })
}
