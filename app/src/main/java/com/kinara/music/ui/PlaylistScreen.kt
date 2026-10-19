/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinara.music.data.Playlist
import com.kinara.music.data.Track
import com.kinara.music.ui.theme.LocalKinaraTheme

@Composable
fun PlaylistScreen(playlists: List<Playlist>, activeId: String?, onPlay: (List<Track>, Int) -> Unit,
    onCreate: (String) -> Unit, onRename: (String, String) -> Unit, onDelete: (String) -> Unit,
    onRemoveTrack: (String, String) -> Unit, onImportNetease: (String) -> Unit = {}, onImportQQ: (String) -> Unit = {},
    onImportLocal: (String?) -> Unit = {}, onImportJson: () -> Unit = {}, onExport: (Playlist) -> Unit = {}) {
    val c = LocalKinaraTheme.current.colors
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val opened = playlists.firstOrNull { it.id == openedId }
    BackHandler(opened != null) { openedId = null }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (opened == null) Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button({ naming = true }, Modifier.weight(1f).heightIn(min = 48.dp)) { KinaraIcon(Glyph.PLUS, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("新建歌单") }
            OutlinedButton({ importing = true }, Modifier.weight(1f).heightIn(min = 48.dp)) { KinaraIcon(Glyph.IMPORT, modifier = Modifier.size(18.dp), tint = c.accent); Spacer(Modifier.width(6.dp)); Text("导入歌单") }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ openedId = null }) { KinaraIcon(Glyph.BACK, "返回歌单列表") }
            Text(opened.name, color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton({ menu = true }) { KinaraIcon(Glyph.MORE, "歌单选项") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("导入本地歌曲") }, { menu = false; onImportLocal(opened.id) })
                    DropdownMenuItem({ Text("导出 JSON 歌单") }, { menu = false; onExport(opened) })
                    if (opened.id != com.kinara.music.data.LibraryRepository.FAVORITES) {
                        DropdownMenuItem({ Text("重命名") }, { menu = false; naming = true })
                        DropdownMenuItem({ Text("删除歌单") }, { menu = false; deleting = true })
                    }
                }
            }
        }
        if (opened == null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ onImportLocal(null) }) { Text("导入本地音频") }
                TextButton(onImportJson) { Text("导入 JSON 文件") }
            }
            if (playlists.isEmpty()) EmptyState(Glyph.QUEUE, "为每一种心情建个歌单", "新建一张歌单，或导入你在\n网易云音乐、QQ音乐里的收藏。")
            else LazyColumn(contentPadding = PaddingValues(bottom = LocalDockInset.current)) {
                items(playlists, key = { it.id }) { playlist ->
                    Row(Modifier.fillMaxWidth().clickable { openedId = playlist.id }.padding(vertical = 12.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        KinaraArtwork(playlist.tracks.firstOrNull(), 68.dp, corner = 18.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${playlist.tracks.size} 首歌曲", fontSize = 13.sp, color = c.textDim, modifier = Modifier.padding(top = 6.dp))
                        }
                        KinaraIcon(Glyph.RIGHT, tint = c.textDim)
                    }
                }
            }
        } else if (opened.tracks.isEmpty()) EmptyState(Glyph.NOTE, "把喜欢的歌放进来", "打开任意歌曲的更多菜单，\n选择「加入歌单」即可添加。")
        else LazyColumn(contentPadding = PaddingValues(bottom = LocalDockInset.current)) {
            item { CollectionActions(opened.tracks.size, { onPlay(opened.tracks, 0) }, { onPlay(opened.tracks.shuffled(), 0) }) }
            itemsIndexed(opened.tracks, key = { _, t -> t.mediaId }) { index, track ->
                TrackRow(track, active = activeId == track.mediaId, onClick = { onPlay(opened.tracks, index) }, onRemove = { onRemoveTrack(opened.id, track.mediaId) })
            }
        }
    }
    if (naming) NameDialog(if (opened == null) "新建歌单" else "重命名歌单", opened?.name.orEmpty(), { naming = false }) { name ->
        if (opened == null) onCreate(name) else onRename(opened.id, name)
        naming = false
    }
    if (deleting && opened != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除「${opened.name}」？") },
        text = { Text("只删除歌单列表，不会删除音频文件。") },
        confirmButton = { TextButton({ onDelete(opened.id); openedId = null; deleting = false }) { Text("删除") } },
        dismissButton = { TextButton({ deleting = false }) { Text("取消") } })
    if (importing) PlaylistImportDialog({ importing = false }) { qq, id ->
        importing = false
        if (qq) onImportQQ(id) else onImportNetease(id)
    }
}

@Composable
fun NameDialog(title: String, initial: String = "", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it.take(80) }, singleLine = true, label = { Text("歌单名称") }) },
        confirmButton = { TextButton({ onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text("确定") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } })
}

@Composable
private fun PlaylistImportDialog(onDismiss: () -> Unit, onConfirm: (Boolean, String) -> Unit) {
    var qq by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val id = remember(input) { parsePlaylistInput(input) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("导入歌单") }, text = {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("网易云音乐", !qq, { qq = false })
                ChoiceChip("QQ音乐", qq, { qq = true })
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(input, { input = it }, label = { Text("歌单链接或数字 ID") }, singleLine = true,
                isError = input.isNotBlank() && id == null,
                supportingText = { Text(if (input.isNotBlank() && id == null) "请粘贴完整歌单链接或数字 ID" else "导入后可预览，再保存到音乐库") })
        }
    }, confirmButton = { TextButton({ id?.let { onConfirm(qq, it) } }, enabled = id != null) { Text("预览歌单") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } })
}

internal fun parsePlaylistInput(input: String): String? {
    val value = input.trim()
    if (value.matches(Regex("[1-9][0-9]{0,19}"))) return value
    return Regex("(?:[?&](?:id|disstid)=|/playlist/)([1-9][0-9]{0,19})(?:[^0-9]|$)")
        .find(value)?.groupValues?.get(1)
}
