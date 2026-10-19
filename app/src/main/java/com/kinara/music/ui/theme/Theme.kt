/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 液态玻璃为默认外观；保留旧主题枚举以兼容已保存的设置。
 * 页面通过颜色与 Material 排版令牌保持一致，工业纹理仅用于工业主题。
 */
package com.kinara.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 主题种类。label 仅用于设置页选项文字。 */
enum class KinaraThemeKind(val label: String) {
    DEFAULT("液态玻璃"),
    INDUSTRIAL("终末地工业"),
}

/** 明暗模式。默认浅色（默认主题即浅蓝蓝白渐变——需求第 7 条的第一印象）。 */
enum class KinaraDarkMode(val label: String) { LIGHT("浅色"), DARK("深色"), SYSTEM("跟随系统") }

@Immutable
data class KinaraColors(
    val bgTop: Color,
    val bgBottom: Color,
    val panel: Color,
    val panelRaised: Color,
    val text: Color,
    val textDim: Color,
    val accent: Color,
    val accentBright: Color,
    val amber: Color,
    val borderOuter: Color,
    val borderInner: Color,
    val borderGap: Color,
    val ledOn: Color,
    val ledOff: Color,
    val isDark: Boolean,
)

@Immutable
data class KinaraThemeSpec(
    val kind: KinaraThemeKind,
    val colors: KinaraColors,
    /** 复古终端扫描线底纹（仅 INDUSTRIAL 开启） */
    val scanlines: Boolean,
    /** 工业网格底纹（两套均开，密度不同） */
    val grid: Boolean,
)

val LocalKinaraTheme = staticCompositionLocalOf<KinaraThemeSpec> {
    error("KinaraTheme not provided")
}

// ---------- 默认：液态玻璃，蓝紫配色 ----------
private val DefaultLight = KinaraColors(
    bgTop = Color(0xFFF4F5FB), bgBottom = Color(0xFFE9EEFA),
    panel = Color(0xFFF3F5FC), panelRaised = Color(0xFFFFFFFF),
    text = Color(0xFF202A42), textDim = Color(0xFF626F88),
    accent = Color(0xFF4260CE), accentBright = Color(0xFF6F85E8),
    amber = Color(0xFFB97F24),
    borderOuter = Color(0xFFC62828), borderInner = Color(0xFFE05252), borderGap = Color(0xFF7A1616),
    ledOn = Color(0xFF6F85E8), ledOff = Color(0xFFC2DBE6),
    isDark = false,
)

private val DefaultDark = KinaraColors(
    bgTop = Color(0xFF101521), bgBottom = Color(0xFF151D31),
    panel = Color(0xFF1B2439), panelRaised = Color(0xFF263149),
    text = Color(0xFFF0F2FC), textDim = Color(0xFFA2ACC3),
    accent = Color(0xFFAAB9FF), accentBright = Color(0xFFCBD4FF),
    amber = Color(0xFFE8A13D),
    borderOuter = Color(0xFFC93030), borderInner = Color(0xFFE55555), borderGap = Color(0xFF631414),
    ledOn = Color(0xFF35C8F0), ledOff = Color(0xFF24384A),
    isDark = true,
)

// ---------- Arknights: Endfield inspired: neutral panels, safety yellow, cut corners. ----------
private val IndustrialLight = KinaraColors(
    bgTop = Color(0xFFF0F0EA), bgBottom = Color(0xFFE6E6E0),
    panel = Color(0xFFF9F9F3), panelRaised = Color(0xFFFFFFFF),
    text = Color(0xFF22251F), textDim = Color(0xFF62655B),
    accent = Color(0xFF625A00), accentBright = Color(0xFFF7E43A),
    amber = Color(0xFF776600),
    borderOuter = Color(0xFFCACCC2), borderInner = Color(0xFFDEE0D6), borderGap = Color(0xFFB0B4A6),
    ledOn = Color(0xFFF7E43A), ledOff = Color(0xFFD2D5C8),
    isDark = false,
)

private val IndustrialDark = KinaraColors(
    bgTop = Color(0xFF141713), bgBottom = Color(0xFF1C1F1A),
    panel = Color(0xFF252922), panelRaised = Color(0xFF30362B),
    text = Color(0xFFF0F1E8), textDim = Color(0xFFAAB19F),
    accent = Color(0xFFF7E43A), accentBright = Color(0xFFF7E43A),
    amber = Color(0xFFE6CD70),
    borderOuter = Color(0xFF444C3C), borderInner = Color(0xFF333B2E), borderGap = Color(0xFF11150E),
    ledOn = Color(0xFFF7E43A), ledOff = Color(0xFF4A503E),
    isDark = true,
)

fun kinaraSpec(kind: KinaraThemeKind, dark: Boolean): KinaraThemeSpec = when (kind) {
    KinaraThemeKind.DEFAULT ->
        KinaraThemeSpec(kind, if (dark) DefaultDark else DefaultLight, scanlines = false, grid = false)
    KinaraThemeKind.INDUSTRIAL ->
        KinaraThemeSpec(kind, if (dark) IndustrialDark else IndustrialLight, scanlines = false, grid = true)
}

/** 由主题令牌推导的 Material3 配色（组件库控件用），视觉上与工业风令牌保持一致。 */
private fun m3Scheme(c: KinaraColors) = if (c.isDark) darkColorScheme(
    primary = c.accent, secondary = c.amber, background = c.bgBottom,
    surface = c.panel, surfaceVariant = c.panelRaised, onPrimary = Color(0xFF06222E),
    onBackground = c.text, onSurface = c.text, onSurfaceVariant = c.textDim,
) else lightColorScheme(
    primary = c.accent, secondary = c.amber, background = c.bgBottom,
    surface = c.panel, surfaceVariant = c.panelRaised, onPrimary = Color(0xFFFFFFFF),
    onBackground = c.text, onSurface = c.text, onSurfaceVariant = c.textDim,
)

@Composable
fun KinaraTheme(
    kind: KinaraThemeKind,
    darkMode: KinaraDarkMode = KinaraDarkMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (darkMode) {
        KinaraDarkMode.LIGHT -> false
        KinaraDarkMode.DARK -> true
        KinaraDarkMode.SYSTEM -> isSystemInDarkTheme()
    }
    val spec = androidx.compose.runtime.remember(kind, dark) { kinaraSpec(kind, dark) }
    val industrial = kind == KinaraThemeKind.INDUSTRIAL
    val shapes = if (industrial) androidx.compose.material3.Shapes(
        androidx.compose.foundation.shape.CutCornerShape(2.dp), androidx.compose.foundation.shape.CutCornerShape(4.dp),
        androidx.compose.foundation.shape.CutCornerShape(8.dp), androidx.compose.foundation.shape.CutCornerShape(12.dp),
        androidx.compose.foundation.shape.CutCornerShape(16.dp)) else androidx.compose.material3.Shapes()
    CompositionLocalProvider(LocalKinaraTheme provides spec) {
        MaterialTheme(colorScheme = m3Scheme(spec.colors), shapes = shapes, content = content)
    }
}

@Composable
fun kinaraShape(radius: androidx.compose.ui.unit.Dp): androidx.compose.ui.graphics.Shape =
    if (LocalKinaraTheme.current.kind == KinaraThemeKind.INDUSTRIAL)
        androidx.compose.foundation.shape.CutCornerShape(topEnd = 10.dp, bottomStart = 3.dp)
    else androidx.compose.foundation.shape.RoundedCornerShape(radius)

/** 背景渐变（浅蓝/蓝白），页面根布局铺底。 */
@Composable
fun kinaraBackgroundBrush(): Brush {
    val c = LocalKinaraTheme.current.colors
    return Brush.verticalGradient(listOf(c.bgTop, c.bgBottom))
}
