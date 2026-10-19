/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinara.music.player.PlayerUiState
import com.kinara.music.ui.theme.KinaraGlassPanel
import com.kinara.music.ui.theme.LocalKinaraTheme

@Composable
fun MiniPlayer(player: PlayerUiState, onTogglePlay: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val current = player.current ?: return
    val c = LocalKinaraTheme.current.colors
    val progress = com.kinara.music.player.LocalPlayerProgress.current
    KinaraGlassPanel(modifier = modifier.fillMaxWidth().testTag("mini_player"), shape = RoundedCornerShape(24.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClickLabel = "打开正在播放", onClick = onOpen)
                .padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                KinaraArtwork(current, 46.dp, corner = 13.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(current.title, color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Text(if (player.recovering) "正在尝试其他来源…" else if (player.error != null) "播放失败，点按重试" else if (player.buffering) "正在加载…" else current.artist,
                        color = c.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onTogglePlay, Modifier.clip(CircleShape).background(c.accent.copy(alpha = 0.10f))) {
                    KinaraIcon(if (player.recovering || player.playing) Glyph.PAUSE else if (player.error != null) Glyph.REFRESH else Glyph.PLAY,
                        if (player.recovering || player.playing) "暂停" else if (player.error != null) "重试播放" else "播放", tint = c.accent)
                }
                IconButton(onNext, enabled = player.currentIndex < player.queue.lastIndex) { KinaraIcon(Glyph.NEXT, "下一曲", tint = c.textDim) }
            }
            if (player.buffering || player.recovering) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = c.accent, trackColor = c.accent.copy(alpha = 0.1f))
            else LinearProgressIndicator(progress = { val tick = progress?.value
                ((tick?.positionMs ?: player.positionMs).toFloat() / (tick?.durationMs ?: player.durationMs).coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp), color = c.accent, trackColor = c.accent.copy(alpha = 0.08f))
        }
    }
}
