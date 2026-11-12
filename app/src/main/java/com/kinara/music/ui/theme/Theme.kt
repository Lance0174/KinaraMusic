/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * Miuix 简洁卡片为默认外观；液态玻璃仍可单独选择。
 * 两套组件共用颜色与排版令牌，工业主题保留终末地风格的黄黑与切角。
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
    DEFAULT("标准"),
    INDUSTRIAL("终末地工业"),
}

/** 明暗模式。默认浅色（默认主题即浅蓝蓝白渐变——需求第 7 条的第一印象）。 */
enum class KinaraDarkMode(val label: String) { LIGHT("浅色"), DARK("深色"), SYSTEM("跟随系统") }

enum class KinaraSurfaceStyle(val label: String) { SIMPLE("简洁"), GLASS("液态玻璃") }

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
    val surfaceStyle: KinaraSurfaceStyle = KinaraSurfaceStyle.SIMPLE,
)

val LocalKinaraTheme = staticCompositionLocalOf<KinaraThemeSpec> {
    error("KinaraTheme not provided")
}

// ---------- 默认：液态玻璃，蓝紫配色 ----------
private val DefaultLight = KinaraColors(
    bgTop = Color(0xFFF7F7F7), bgBottom = Color(0xFFF7F7F7),
    panel = Color(0xFFFFFFFF), panelRaised = Color(0xFFFFFFFF),
    // textDim was 0xFF626F88, which measures 4.36:1 on bgBottom (0xFFE9EEFA) — just under the
    // WCAG AA 4.5:1 floor for the 11–13sp secondary labels this palette is used with.
    // 0xFF57637C measures 5.19:1 on the same background, so secondary text clears AA
    // without touching a single screen.
    text = Color(0xFF242424), textDim = Color(0xFF6D6D74),
    accent = Color(0xFF3482FF), accentBright = Color(0xFF6DA7FF),
    amber = Color(0xFFB97F24),
    borderOuter = Color(0xFFE3E3E7), borderInner = Color(0xFFFFFFFF), borderGap = Color(0xFFD9D9DF),
    ledOn = Color(0xFF3482FF), ledOff = Color(0xFFD5E5FF),
    isDark = false,
)

private val DefaultDark = KinaraColors(
    bgTop = Color(0xFF000000), bgBottom = Color(0xFF000000),
    panel = Color(0xFF242424), panelRaised = Color(0xFF242424),
    text = Color(0xFFF1F1F3), textDim = Color(0xFFA1A1A8),
    accent = Color(0xFF6DA7FF), accentBright = Color(0xFF9AC2FF),
    amber = Color(0xFFE8A13D),
    borderOuter = Color(0xFF333337), borderInner = Color(0xFF3C3C40), borderGap = Color(0xFF161618),
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
    surfaceStyle: KinaraSurfaceStyle = KinaraSurfaceStyle.SIMPLE,
    content: @Composable () -> Unit,
) {
    val dark = when (darkMode) {
        KinaraDarkMode.LIGHT -> false
        KinaraDarkMode.DARK -> true
        KinaraDarkMode.SYSTEM -> isSystemInDarkTheme()
    }
    val spec = androidx.compose.runtime.remember(kind, dark, surfaceStyle) { kinaraSpec(kind, dark).copy(surfaceStyle = surfaceStyle) }
    val industrial = kind == KinaraThemeKind.INDUSTRIAL
    val shapes = if (industrial) androidx.compose.material3.Shapes(
        androidx.compose.foundation.shape.CutCornerShape(2.dp), androidx.compose.foundation.shape.CutCornerShape(4.dp),
        androidx.compose.foundation.shape.CutCornerShape(8.dp), androidx.compose.foundation.shape.CutCornerShape(12.dp),
        androidx.compose.foundation.shape.CutCornerShape(16.dp)) else androidx.compose.material3.Shapes()
    CompositionLocalProvider(LocalKinaraTheme provides spec) {
        KinaraSystemBars(dark)
        val c = spec.colors
        val miuix = if (dark) top.yukonga.miuix.kmp.theme.darkColorScheme(
            primary = c.accent, onPrimary = Color(0xFF17212E), background = c.bgBottom, onBackground = c.text,
            surface = c.panel, surfaceVariant = c.panelRaised, onSurface = c.text,
            onSurfaceSecondary = c.text, onSurfaceVariantSummary = c.textDim,
            surfaceContainer = c.panel, onSurfaceContainer = c.text,
            tertiaryContainer = c.accent.copy(alpha = 0.16f), onTertiaryContainer = c.accent,
        ) else top.yukonga.miuix.kmp.theme.lightColorScheme(
            primary = c.accent, background = c.bgBottom, onBackground = c.text,
            surface = c.panel, surfaceVariant = c.panelRaised, onSurface = c.text,
            onSurfaceSecondary = c.text, onSurfaceVariantSummary = c.textDim,
            surfaceContainer = c.panel, onSurfaceContainer = c.text,
            tertiaryContainer = c.accent.copy(alpha = 0.10f), onTertiaryContainer = c.accent,
        )
        top.yukonga.miuix.kmp.theme.MiuixTheme(colors = miuix) {
            MaterialTheme(colorScheme = m3Scheme(c), shapes = shapes, content = content)
        }
    }
}

/** Keep system icons readable when the in-app theme differs from the Android system theme. */
@Composable
internal fun KinaraSystemBars(dark: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    var context: android.content.Context = view.context
    while (context is android.content.ContextWrapper && context !is android.app.Activity) context = context.baseContext
    val activity = context as? android.app.Activity ?: return
    androidx.compose.runtime.DisposableEffect(view, activity, dark) {
        val controller = androidx.core.view.WindowCompat.getInsetsController(activity.window, view)
        val status = controller.isAppearanceLightStatusBars
        val navigation = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        onDispose {
            controller.isAppearanceLightStatusBars = status
            controller.isAppearanceLightNavigationBars = navigation
        }
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
