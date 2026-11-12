/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kinara.music.data.Track
import com.kinara.music.download.*
import com.kinara.music.ui.theme.LocalKinaraTheme

@Composable
fun DownloadsScreen(downloads: Downloads, onPlay: (List<Track>, Int) -> Unit, onAdd: (Track) -> Unit,
    onPlayNext: (Track) -> Unit = {}) {
    val entries by downloads.entries.collectAsStateWithLifecycle()
    val c = LocalKinaraTheme.current.colors
    // 下载记录是构造时异步从磁盘解码的，首帧 entries 还是空集合。
    // 直接按空集合渲染会让"把音乐带到离线"闪一下，看起来像是一条下载都没有。
    // 已有数据时（多数情况）同步就绪，不显示加载态，避免每次进页面都闪一次。
    var loaded by remember { mutableStateOf(entries.isNotEmpty()) }
    LaunchedEffect(Unit) { downloads.awaitReady(); downloads.refresh(); loaded = true }
    if (!loaded) Box(Modifier.fillMaxSize().padding(bottom = LocalDockInset.current), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
    else if (entries.isEmpty()) EmptyState(Glyph.IMPORT, "把音乐带到离线", "在播放器的更多菜单中选择下载。\n文件保存在手机的 Music/Kinara 文件夹。")
    else LazyColumn(modifier = Modifier.padding(bottom = LocalDockInset.current), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        items(entries, key = { it.key }) { entry ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                TrackRow(entry.localTrack() ?: entry.track, onClick = {
                    val playable = entries.mapNotNull { it.localTrack() }
                    val index = playable.indexOfFirst { it.mediaId == entry.track.mediaId }
                    if (entry.phase == DownloadPhase.COMPLETE && index >= 0) onPlay(playable, index)
                }, onAdd = if (entry.phase == DownloadPhase.COMPLETE) ({ entry.localTrack()?.let(onAdd) }) else null,
                    // 已下载完成的项可以直接排到下一首；未完成的本地文件还不存在，不提供入口。
                    onPlayNext = if (entry.phase == DownloadPhase.COMPLETE) ({ onPlayNext(entry.localTrack() ?: entry.track) }) else null)
                Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(when (entry.phase) {
                        DownloadPhase.RESOLVING -> "正在获取音频…"
                        DownloadPhase.RUNNING -> "${entry.message} · ${formatBytes(entry.bytes)} / ${if (entry.total > 0) formatBytes(entry.total) else "大小未知"}"
                        else -> entry.message.ifBlank { "下载中断，请重试" }
                    }, style = MaterialTheme.typography.bodySmall, color = c.textDim, modifier = Modifier.weight(1f))
                    if (entry.phase in setOf(DownloadPhase.RESOLVING, DownloadPhase.RUNNING)) TextButton({ downloads.cancel(entry.key) }) { Text("取消") }
                    if (entry.phase in setOf(DownloadPhase.FAILED, DownloadPhase.CANCELLED)) TextButton({ downloads.retry(entry) }) { Text("重试") }
                }
                if (entry.phase == DownloadPhase.RUNNING && entry.total > 0)
                    LinearProgressIndicator(progress = { (entry.bytes.toFloat() / entry.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(8.dp))
            }
        }
    }
}

fun formatBytes(bytes: Long): String = if (bytes >= 1_000_000_000) "%.2f GB".format(bytes / 1_000_000_000.0)
    else "%.1f MB".format(bytes / 1_000_000.0)

@Composable
fun DownloadQualityDialog(track: Track, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var bitrate by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("下载音频") }, text = {
        Column {
            Text(track.title)
            Text("选择请求的音质，实际音质由音源提供。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
            listOf("128", "192", "320", "flac").forEach { quality ->
                ChoiceChip(if (quality == "flac") "FLAC" else "$quality K", bitrate == quality, { bitrate = quality }, Modifier.fillMaxWidth())
            }
        }
    }, confirmButton = { TextButton({ onConfirm(bitrate) }) { Text("下载") } }, dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
