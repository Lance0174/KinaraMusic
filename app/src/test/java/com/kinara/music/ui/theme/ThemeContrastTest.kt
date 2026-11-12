/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * 次要文字对比度回归测试。
 *
 * 背景：DefaultLight 的 textDim 原本是 0xFF626F88，在 bgBottom(0xFFE9EEFA) 上只有 4.36:1，
 * 低于 WCAG AA 对正文要求的 4.5:1，而它恰好被用在 11–13sp 的小字上。
 * 这条测试把"每一套配色的次要文字都必须达到 AA"固定下来，
 * 以后改色板时若再次跌破阈值会立刻失败。
 */
class ThemeContrastTest {

    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `secondary text clears WCAG AA on its own background`() {
        val specs = KinaraThemeKind.entries.flatMap { kind ->
            listOf(false, true).map { dark -> kinaraSpec(kind, dark) }
        }
        specs.forEach { spec ->
            val ratio = contrast(spec.colors.textDim, spec.colors.bgBottom)
            assertTrue(
                "${spec.kind}/dark=${spec.colors.isDark}: textDim/bgBottom = %.2f:1, 低于 AA 4.5:1".format(ratio),
                ratio >= 4.5
            )
        }
    }

    @Test
    fun `primary text clears WCAG AA with margin`() {
        val specs = KinaraThemeKind.entries.flatMap { kind ->
            listOf(false, true).map { dark -> kinaraSpec(kind, dark) }
        }
        specs.forEach { spec ->
            val ratio = contrast(spec.colors.text, spec.colors.bgBottom)
            assertTrue("${spec.kind}/dark=${spec.colors.isDark}: text/bgBottom = %.2f:1".format(ratio), ratio >= 7.0)
        }
    }
}
