/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.kinara.music.ui.theme.LocalKinaraTheme

/** One vector vocabulary, consistent strokes and no font/emoji-dependent playback controls. */
enum class Glyph(val path: String, val solid: Boolean = false) {
    LIBRARY("M4 5 L4 20 M9 3 L9 20 M14 6 L18 5 L22 19 L18 20 Z"),
    SEARCH("M18 10 A8 8 0 1 1 2 10 A8 8 0 1 1 18 10 M16 16 L22 22"),
    SETTINGS("M4 6 L20 6 M4 12 L20 12 M4 18 L20 18 M8 3 L8 9 M16 9 L16 15 M10 15 L10 21"),
    PLAY("M8 4 L21 12 L8 20 Z", true),
    PAUSE("M6 4 L10 4 L10 20 L6 20 Z M14 4 L18 4 L18 20 L14 20 Z", true),
    NEXT("M4 5 L15 12 L4 19 Z M17 5 L20 5 L20 19 L17 19 Z", true),
    PREVIOUS("M20 5 L9 12 L20 19 Z M4 5 L7 5 L7 19 L4 19 Z", true),
    HEART("M12 21 L3.7 12.3 C-2 6.2 6.2 -0.5 12 6 C17.8 -0.5 26 6.2 20.3 12.3 Z"),
    HEART_FILLED("M12 21 L3.7 12.3 C-2 6.2 6.2 -0.5 12 6 C17.8 -0.5 26 6.2 20.3 12.3 Z", true),
    MORE("M11 5 L13 5 M11 12 L13 12 M11 19 L13 19"),
    DOWN("M5 9 L12 16 L19 9"),
    BACK("M14 5 L7 12 L14 19"),
    RIGHT("M9 5 L16 12 L9 19"),
    PLUS("M12 4 L12 20 M4 12 L20 12"),
    QUEUE("M3 5 L21 5 M3 11 L16 11 M3 17 L12 17 M17 15 L17 22 L22 18.5 Z"),
    CHECK("M4 12 L9 17 L21 5"),
    CLOSE("M5 5 L19 19 M19 5 L5 19"),
    IMPORT("M12 2 L12 15 M7 10 L12 15 L17 10 M4 15 L4 21 L20 21 L20 15"),
    NOTE("M9 18 L9 5 L20 3 L20 16 M9 9 L20 7 M9 18 A3 3 0 1 1 3 18 A3 3 0 1 1 9 18 M20 16 A3 3 0 1 1 14 16 A3 3 0 1 1 20 16"),
    REFRESH("M20 7 A9 9 0 1 0 21 15 M20 2 L20 7 L15 7"),
    SHUFFLE("M3 6 L5 6 C10 6 14 18 19 18 L22 18 M18 14 L22 18 L18 22 M3 18 L5 18 C7 18 9 14 10 13 M14 8 C16 6 17 6 19 6 L22 6 M18 2 L22 6 L18 10"),
    REPEAT("M4 10 L4 8 Q4 5 7 5 L21 5 M17 1 L21 5 L17 9 M20 14 L20 16 Q20 19 17 19 L3 19 M7 15 L3 19 L7 23"),
    REPEAT_ONE("M4 10 L4 8 Q4 5 7 5 L21 5 M17 1 L21 5 L17 9 M20 14 L20 16 Q20 19 17 19 L3 19 M7 15 L3 19 L7 23 M10 10 L12 8 L12 16"),
    ORDER("M3 7 L20 7 M16 3 L20 7 L16 11 M21 17 L4 17 M8 13 L4 17 L8 21"),
    USER("M16 7 A4 4 0 1 1 8 7 A4 4 0 1 1 16 7 M4 22 L4 20 C4 12 20 12 20 20 L20 22"),
    LYRICS("M4 5 L20 5 M4 12 L17 12 M4 19 L13 19"),
}

@Composable
fun KinaraIcon(glyph: Glyph, description: String? = null, modifier: Modifier = Modifier, tint: Color = LocalKinaraTheme.current.colors.text) {
    val vector = remember(glyph) {
        ImageVector.Builder(glyph.name, 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = PathParser().parsePathString(glyph.path).toNodes(),
            fill = if (glyph.solid) SolidColor(Color.Black) else null,
            stroke = if (glyph.solid) null else SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ).build()
    }
    Icon(vector, description, modifier.size(24.dp), tint)
}
