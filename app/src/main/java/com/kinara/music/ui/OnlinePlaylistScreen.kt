/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinara.music.online.OnlineTrack
import com.kinara.music.online.toTrack
import com.kinara.music.ui.theme.LocalKinaraTheme

@Composable
fun PlaylistResultScreen(title: String, message: String, tracks: List<OnlineTrack>, onPlay: (OnlineTrack) -> Unit,
    onClose: () -> Unit, onSave: () -> Unit, importing: Boolean = false) {
    val c = LocalKinaraTheme.current.colors
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClose) { KinaraIcon(Glyph.BACK, "关闭预览") }
            Text(title, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
        Text(message, color = c.textDim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
        if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (tracks.isNotEmpty()) Button(onSave, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("保存到音乐库 · ${tracks.size} 首") }
        LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = LocalDockInset.current)) {
            items(tracks, key = { it.platform + it.id }) { t -> TrackRow(t.toTrack("320"), onClick = { onPlay(t) }) }
        }
    }
}
