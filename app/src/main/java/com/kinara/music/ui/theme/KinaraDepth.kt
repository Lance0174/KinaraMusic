/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

enum class KinaraDepthLevel { FULL, LITE }

/** Soft mesh built from gradients, avoiding another full-screen offscreen blur layer.
 * Actual cover colours tint the player; without artwork use a cool blue/lilac palette.
 */
@Composable
fun KinaraDepthBackground(accentColors: List<Color> = emptyList(), level: KinaraDepthLevel = KinaraDepthLevel.FULL, modifier: Modifier = Modifier) {
    val c = LocalKinaraTheme.current.colors
    if (LocalKinaraTheme.current.kind == KinaraThemeKind.INDUSTRIAL) {
        Canvas(modifier.fillMaxSize()) { drawRect(c.bgBottom) }
        return
    }
    val colors = accentColors.takeIf { it.size >= 2 } ?: if (c.isDark)
        listOf(Color(0xFF5667A2), Color(0xFF615193), Color(0xFF347B90)) else
        listOf(Color(0xFFBDCAF9), Color(0xFFD6C5F4), Color(0xFFA9DDEA))
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(c.bgTop, c.bgBottom)))
        val strength = if (level == KinaraDepthLevel.LITE) 0.28f else if (c.isDark) 0.44f else 0.64f
        val centers = listOf(Offset(size.width * 0.92f, size.height * 0.09f), Offset(size.width * 0.1f, size.height * 0.65f), Offset(size.width * 0.95f, size.height * 0.94f))
        centers.forEachIndexed { i, center ->
            drawCircle(Brush.radialGradient(listOf(colors[i % colors.size].copy(alpha = strength), Color.Transparent),
                center, size.width * 0.85f), size.width * 0.85f, center)
        }
    }
}
