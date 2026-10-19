/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 液态玻璃（定稿视觉 E 稿）：背景模糊、透色、边缘高光与投影。
 * 背景只录制一次 GraphicsLayer；各面板按屏幕坐标截取并使用 RenderEffect 模糊。
 * Android 12 / API 31 起启用，不沿用 Haze 0.7.3 对 API 31 的强制遮罩降级。
 * 仅处理玻璃覆盖区域；旧系统与 LITE 档保留半透明材质。
 */
package com.kinara.music.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.os.Build
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

/** 玻璃质感等级：标准（默认，26dp 模糊）/ 精简（低端机，退化为半透明实色）。 */
enum class KinaraGlassLevel { FULL, LITE }

/** 玻璃风格的令牌（暗色玻璃 tint 更深，浅色玻璃更亮）。 */
data class KinaraGlassTokens(
    val tint: Color,
    val blurRadius: Dp,
    val borderTop: Color,
    val borderBody: Color,
    val highlight: Color,
    val shadow: Color,
)

fun kinaraGlassTokens(dark: Boolean): KinaraGlassTokens = if (dark) {
    KinaraGlassTokens(
        tint = Color(0xFF192339).copy(alpha = 0.44f),
        blurRadius = 24.dp,
        borderTop = Color(0xB3C8EEFF),
        borderBody = Color(0x3DA0DCF5),
        highlight = Color(0x24FFFFFF),
        shadow = Color(0x7A000000),
    )
} else {
    KinaraGlassTokens(
        tint = Color(0xFFFFFFFF).copy(alpha = 0.40f),
        blurRadius = 24.dp,
        borderTop = Color(0xF2FFFFFF),
        borderBody = Color(0x9EFFFFFF),
        highlight = Color(0x61FFFFFF),
        shadow = Color(0x33061E2A),
    )
}

@Stable
class KinaraBackdropState internal constructor(internal val layer: GraphicsLayer) {
    internal var position by mutableStateOf(Offset.Zero)
    internal var ready by mutableStateOf(false)
}

@Composable
fun rememberKinaraBackdrop(): KinaraBackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { KinaraBackdropState(layer) }
}

val LocalKinaraBackdrop = androidx.compose.runtime.staticCompositionLocalOf<KinaraBackdropState?> { null }

/** 不包含玻璃本身，避免递归采样。图层随 Compose 生命周期自动释放。 */
fun Modifier.kinaraBackdropSource(state: KinaraBackdropState): Modifier = this
    .onGloballyPositioned { state.position = it.positionInRoot() }
    .drawWithContent {
        state.layer.record { this@drawWithContent.drawContent() }
        drawLayer(state.layer)
        state.ready = true
    }

/** 截取面板及模糊边距，防止边缘透明黑边；文字和按钮在模糊之后绘制。 */
@Composable
private fun Modifier.glassBackdrop(state: KinaraBackdropState?, tokens: KinaraGlassTokens, full: Boolean): Modifier {
    val blurred = rememberGraphicsLayer()
    var position by remember { mutableStateOf(Offset.Zero) }
    return onGloballyPositioned { position = it.positionInRoot() }.drawWithCache {
        if (full && state?.ready == true && Build.VERSION.SDK_INT >= 31) {
            val radius = tokens.blurRadius.toPx()
            val margin = ceil(radius * 2).toInt()
            val offset = position - state.position
            blurred.record(size = IntSize(size.width.toInt() + margin * 2, size.height.toInt() + margin * 2)) {
                translate(margin - offset.x, margin - offset.y) { drawLayer(state.layer) }
            }
            blurred.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
            // Keep the display list and RenderEffect until geometry/material changes. The nested
            // source layer remains live, so scrolling still updates the sampled backdrop.
            onDrawWithContent {
                scale(1.025f, pivot = center) {
                    translate(-margin.toFloat(), -margin.toFloat()) { drawLayer(blurred) }
                }
                drawRect(tokens.tint)
                drawContent()
            }
        } else {
            onDrawWithContent { drawRect(tokens.tint.copy(alpha = 0.62f)); drawContent() }
        }
    }
}

/**
 * 液态玻璃面板：
 * @param state 与本屏内容层共享的背景状态
 * @param shape 面板形状（切角/圆角均可）
 * @param level FULL 使用真实模糊；LITE 使用半透明材质
 */
@Composable
fun KinaraGlassPanel(
    state: KinaraBackdropState? = null,
    modifier: Modifier = Modifier,
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    level: KinaraGlassLevel = KinaraGlassLevel.FULL,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    val spec = LocalKinaraTheme.current
    val c = spec.colors
    if (spec.kind == KinaraThemeKind.INDUSTRIAL) {
        val cut = androidx.compose.foundation.shape.CutCornerShape(topEnd = 12.dp, bottomStart = 4.dp)
        Box(modifier.clip(cut).background(c.panel).border(1.dp, c.borderOuter, cut), contentAlignment = contentAlignment) {
            Box(Modifier.matchParentSize().drawWithContent {
                drawContent()
                drawLine(c.accentBright, Offset(0f, 0f), Offset(32.dp.toPx(), 0f), 3.dp.toPx())
            })
            content()
        }
        return
    }
    val t = remember(c.isDark) { kinaraGlassTokens(c.isDark) }
    val backdrop = state ?: LocalKinaraBackdrop.current

    Box(
        modifier
            .shadow(8.dp, shape, clip = false, ambientColor = t.shadow, spotColor = t.shadow)
            .clip(shape)
            .glassBackdrop(backdrop, t, level == KinaraGlassLevel.FULL)
            // 玻璃厚度：顶部高光边 + 外投影
            .border(1.dp, Brush.linearGradient(listOf(t.borderTop, t.borderBody, t.borderBody, t.borderTop.copy(alpha = 0.3f))), shape),
        contentAlignment = contentAlignment,
    ) {
        // 液态表面：112° 斜向高光带（液体表面的光带感）
        // 注意：必须用 matchParentSize —— fillMaxSize 会参与定尺并把面板撑满父容器（实测踩坑）
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(t.highlight, Color.Transparent, Color.Transparent, t.highlight.copy(alpha = t.highlight.alpha * 0.4f)),
                        start = Offset(0f, 0f),
                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    )
                )
        )
        content()
    }
}

