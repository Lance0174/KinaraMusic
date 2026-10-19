/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kinara.music.player.PlayerUiState
import com.kinara.music.ui.theme.*

/** Scene capture contains background + scroll content. The floating dock samples that scene.
 * Content panels sample the separate background layer, so there is no recursive glass capture.
 * Screens receive the measured dock inset; their last items can always scroll above it.
 */
@Composable
fun KinaraShell(selected: KinaraNav, onSelect: (KinaraNav) -> Unit, player: PlayerUiState,
    onTogglePlay: () -> Unit, onNext: () -> Unit, onOpenPlayer: () -> Unit,
    artworkColors: List<Color> = emptyList(), content: @Composable () -> Unit) {
    val glass = LocalKinaraTheme.current.kind == KinaraThemeKind.DEFAULT
    val background = if (glass) rememberKinaraBackdrop() else null
    val scene = if (glass) rememberKinaraBackdrop() else null
    val density = LocalDensity.current
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
    var dockPixels by remember { mutableIntStateOf(0) }
    val inset = if (keyboardOpen) 24.dp else with(density) { dockPixels.toDp() } + 16.dp
    CompositionLocalProvider(LocalKinaraBackdrop provides background, LocalDockInset provides inset) {
        Box(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.fillMaxSize().then(if (scene != null) Modifier.kinaraBackdropSource(scene) else Modifier)) {
                Box(Modifier.fillMaxSize().then(if (background != null) Modifier.kinaraBackdropSource(background) else Modifier)) {
                    KinaraDepthBackground(artworkColors)
                    KinaraTextureOverlay()
                }
                Box(Modifier.fillMaxSize().statusBarsPadding()) { content() }
            }
            if (!keyboardOpen) CompositionLocalProvider(LocalKinaraBackdrop provides scene) {
                Column(Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth()
                    .onSizeChanged { dockPixels = it.height }.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MiniPlayer(player, onTogglePlay, onNext, onOpenPlayer)
                    KinaraNavBar(selected, onSelect)
                }
            }
        }
    }
}

@Composable
fun PlayerBackdrop(colors: List<Color>, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(LocalKinaraTheme.current.colors.bgBottom)
        .statusBarsPadding().navigationBarsPadding()) { content() }
}
