/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import com.kinara.music.data.KinaraSettings
import com.kinara.music.data.LyricRenderer
import com.kinara.music.data.LyricVisualStyle
import com.kinara.music.data.PlayerVisualMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "动态视界全屏渲染"这条产品约束的回归测试。
 *
 * 之所以不直接渲染 PlayScreen 来验证：动态视界分支内部是 WebView，
 * 在 Robolectric 里会挂起并让用例变慢/不稳定。把判定抽成纯函数后，
 * 这条约束就能被确定性地钉住。
 */
class PlayScreenVisualModeTest {

    @Test
    fun `dynamic visual goes fullscreen only on the lyrics page`() {
        assertTrue(isFullscreenVisual(PlayerVisualMode.LYRICS, LyricRenderer.FOLIA_LUMIERE))
        // 停在封面页时不能全屏：否则左右滑动切换封面的手势就没有落点了。
        assertFalse(isFullscreenVisual(PlayerVisualMode.COVER, LyricRenderer.FOLIA_LUMIERE))
    }

    @Test
    fun `classic and glow renderers stay inside the pager`() {
        listOf(LyricRenderer.CLASSIC, LyricRenderer.GLOW).forEach { renderer ->
            assertFalse(renderer.name, isFullscreenVisual(PlayerVisualMode.LYRICS, renderer))
            assertFalse(renderer.name, isFullscreenVisual(PlayerVisualMode.COVER, renderer))
        }
    }

    @Test
    fun `visual style varies per track by default`() {
        // 产品决策：动态视觉默认"随曲变动"，因此不额外提供风格选择器。
        assertEquals(LyricVisualStyle.AUTO, KinaraSettings().lyricVisualStyle)
    }

    @Test
    fun `landscape requires an explicit request and the dynamic visual renderer`() {
        assertTrue(locksLandscape(PlayerEntry.EXPLICIT_LANDSCAPE, LyricRenderer.FOLIA_LUMIERE))
        assertFalse(locksLandscape(PlayerEntry.MINI_PLAYER, LyricRenderer.FOLIA_LUMIERE))
        assertFalse(locksLandscape(PlayerEntry.SETTINGS_PREVIEW, LyricRenderer.FOLIA_LUMIERE))
        // 没有开启动态视界时，从迷你播放器进入也不锁。
        listOf(LyricRenderer.CLASSIC, LyricRenderer.GLOW).forEach { renderer ->
            assertFalse(renderer.name, locksLandscape(PlayerEntry.EXPLICIT_LANDSCAPE, renderer))
        }
    }
}
