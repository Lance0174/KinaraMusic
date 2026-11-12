/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.kinara.music.ui.theme.LocalKinaraTheme
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationItem

enum class KinaraNav(val label: String, val glyph: Glyph) {
    LIBRARY("音乐", Glyph.LIBRARY), SEARCH("搜索", Glyph.SEARCH),
    PLAYLISTS("歌单", Glyph.QUEUE), DOWNLOADS("下载", Glyph.DOWNLOAD), SETTINGS("设置", Glyph.SETTINGS)
}

private val navigationItems = listOf(
    "M9,17 L9,5 L20,3 L20,15 M9,8 L20,6 M9,17 C9,20 3,21 3,18 C3,15 9,14 9,17 M20,15 C20,18 14,19 14,16 C14,13 20,12 20,15",
    "M17,10 A7,7 0,1 1,3,10 A7,7 0,1 1,17,10 M15,15 L21,21",
    "M4,5 L20,5 M4,11 L15,11 M4,17 L10,17 M17,14 L22,17 L17,20 Z",
    "M12,3 L12,15 M7,10 L12,15 L17,10 M4,17 L4,21 L20,21 L20,17",
    "M9,3 L15,3 L16,6 L19,6 L22,11 L20,13 L21,16 L18,20 L15,19 L13,22 L8,21 L7,18 L4,18 L2,13 L4,11 L3,8 L6,4 L9,5 Z M15,12 A3,3 0,1 1,9,12 A3,3 0,1 1,15,12",
).mapIndexed { index, path ->
    NavigationItem(KinaraNav.entries[index].label, ImageVector.Builder(
        name = "KinaraNav${index}", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(PathParser().parsePathString(path).toNodes(), fill = null,
        stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
        strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
        strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round).build())
}

@Composable
fun KinaraNavBar(current: KinaraNav, onSelect: (KinaraNav) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(items = navigationItems, selected = current.ordinal,
        onClick = { onSelect(KinaraNav.entries[it]) }, modifier = modifier,
        color = LocalKinaraTheme.current.colors.bgBottom,
        showDivider = false, defaultWindowInsetsPadding = false)
}
