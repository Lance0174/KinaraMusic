/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinara.music.player.PlayerUiState
import com.kinara.music.ui.theme.*
import top.yukonga.miuix.kmp.basic.Scaffold

/** Only the optional glass surface captures the scene. Ordinary Miuix pages need no full-window textures. */
@Composable
fun KinaraShell(selected: KinaraNav, onSelect: (KinaraNav) -> Unit, player: PlayerUiState,
    onTogglePlay: () -> Unit, onNext: () -> Unit, onOpenPlayer: () -> Unit,
    artworkColors: List<Color> = emptyList(), onOpenQueue: () -> Unit = onOpenPlayer,
    onPrevious: () -> Unit = {}, onSeek: (Long) -> Unit = {}, onMode: () -> Unit = {},
    content: @Composable () -> Unit) {
    val spec = LocalKinaraTheme.current
    val glass = spec.kind == KinaraThemeKind.DEFAULT && spec.surfaceStyle == KinaraSurfaceStyle.GLASS
    val background = if (glass) rememberKinaraBackdrop() else null
    val scene = if (glass) rememberKinaraBackdrop() else null
    val density = LocalDensity.current
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
    var dockPixels by remember { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        val inset = if (wide || keyboardOpen) 24.dp else with(density) { dockPixels.toDp() } + 8.dp
        CompositionLocalProvider(LocalKinaraBackdrop provides background, LocalDockInset provides inset) {
            Scaffold(containerColor = spec.colors.bgBottom, contentWindowInsets = WindowInsets(0)) {
                Row(Modifier.fillMaxSize().imePadding()) {
                    if (wide) KinaraNavigationRail(selected, onSelect)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.fillMaxSize().then(if (scene != null) Modifier.kinaraBackdropSource(scene) else Modifier)) {
                            if (background != null) Box(Modifier.fillMaxSize().kinaraBackdropSource(background)) {
                                KinaraDepthBackground(artworkColors)
                            }
                            Box(Modifier.fillMaxSize().statusBarsPadding()) { content() }
                        }
                        if (!wide && !keyboardOpen) CompositionLocalProvider(LocalKinaraBackdrop provides scene) {
                            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .onSizeChanged { dockPixels = it.height }
                                .navigationBarsPadding().padding(top = 8.dp)) {
                                MiniPlayer(player, onTogglePlay, onNext, onOpenPlayer,
                                    Modifier.padding(horizontal = 14.dp), onOpenQueue)
                                KinaraNavBar(selected, onSelect)
                            }
                        }
                    }
                    if (wide) WideNowPlaying(player, onTogglePlay, onPrevious, onNext, onSeek, onMode, onOpenPlayer, onOpenQueue)
                }
            }
        }
    }
}

@Composable
private fun KinaraNavigationRail(selected: KinaraNav, onSelect: (KinaraNav) -> Unit) {
    val c = LocalKinaraTheme.current.colors
    Column(Modifier.width(178.dp).fillMaxHeight().background(c.panel).statusBarsPadding()
        .navigationBarsPadding().padding(horizontal = 14.dp, vertical = 24.dp)) {
        Row(Modifier.padding(start = 10.dp, bottom = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                Text("K", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
            Text("Kinara", modifier = Modifier.padding(start = 10.dp), color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        KinaraNav.entries.forEach { route ->
            val active = selected == route
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(13.dp))
                .background(if (active) c.accent.copy(alpha = 0.12f) else Color.Transparent)
                .clickable { onSelect(route) }.heightIn(min = 50.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                KinaraIcon(route.glyph, tint = if (active) c.accent else c.textDim)
                Text(route.label, Modifier.padding(start = 14.dp), color = if (active) c.accent else c.textDim,
                    fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("音乐与你，随时相伴", color = c.textDim, fontSize = 11.sp, modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun WideNowPlaying(player: PlayerUiState, onToggle: () -> Unit, onPrevious: () -> Unit,
    onNext: () -> Unit, onSeek: (Long) -> Unit, onMode: () -> Unit, onOpen: () -> Unit, onQueue: () -> Unit) {
    val c = LocalKinaraTheme.current.colors
    // 256dp of inner width preserves all five 48/64dp playback targets.
    Column(Modifier.width(304.dp).fillMaxHeight().background(c.panel).statusBarsPadding()
        .navigationBarsPadding().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("正在播放", color = c.textDim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            IconButton(onOpen) { KinaraIcon(Glyph.MORE, "展开播放页", Modifier.size(19.dp)) }
        }
        Spacer(Modifier.height(20.dp))
        val current = player.current
        if (current != null) {
            KinaraArtwork(current, 220.dp, Modifier.fillMaxWidth().aspectRatio(1f).clickable(onClick = onOpen), corner = 16.dp)
            Text(current.title, color = c.text, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 24.dp))
            Text(current.artist, color = c.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 7.dp))
            PlaybackControls(player, onToggle, onPrevious, onNext, onSeek, onMode, onQueue)
            Spacer(Modifier.weight(1f))
            top.yukonga.miuix.kmp.basic.Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    KinaraIcon(Glyph.NOTE, tint = c.accent, modifier = Modifier.size(18.dp))
                    Text("打开正在播放", color = c.accent, fontSize = 12.sp, modifier = Modifier.padding(start = 9.dp))
                }
            }
        } else {
            Spacer(Modifier.weight(1f))
            Text("选一首喜欢的歌\n让音乐开始陪伴", color = c.textDim, fontSize = 14.sp, lineHeight = 26.sp)
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun PlayerBackdrop(colors: List<Color> = emptyList(), content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(LocalKinaraTheme.current.colors.bgBottom)) { content() }
}
