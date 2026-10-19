/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0.
 * Optional texture for the retained industrial theme. Default glass has no grid or scanlines.
 */
package com.kinara.music.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun KinaraTextureOverlay(modifier: Modifier = Modifier) {
    val spec = LocalKinaraTheme.current
    val c = spec.colors
    if (!spec.grid && !spec.scanlines) return
    Canvas(modifier.fillMaxSize()) {
        val step = 72.dp.toPx()
        val gridColor = c.text.copy(alpha = 0.035f)
        var x = 0f
        while (x <= size.width) {
            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 1f)
            x += step
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1f)
            y += step
        }
        if (spec.scanlines) {
            val lineColor = if (c.isDark) Color.Black.copy(alpha = 0.09f) else Color.Black.copy(alpha = 0.045f)
            var sy = 0f
            while (sy <= size.height) {
                drawLine(lineColor, Offset(0f, sy), Offset(size.width, sy), 1f)
                sy += 4.dp.toPx()
            }
        }
        // 暗角：中心透明 → 边缘收暗（暗色收黑，浅色收蓝灰），聚焦面板
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.Transparent,
                    if (c.isDark) Color.Black.copy(alpha = 0.10f) else Color(0xFF7FA8BC).copy(alpha = 0.04f),
                ),
                center = Offset(size.width / 2f, size.height / 2f),
                radius = maxOf(size.width, size.height) * 0.72f,
            ),
        )
    }
}

