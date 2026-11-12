/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.data

import com.kinara.music.ui.theme.KinaraDarkMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置解析缺省值的"自洽性"用例。
 *
 * 背景：parseSettings 是运行期真正生效的解析路径（KinaraPrefs.settings() 走它），
 * 因此它的缺省值直接决定新装用户的首次观感。这里**不**固定断言具体是哪种深色模式——
 * 那是产品取向（缺省 LIGHT 还是跟随系统）——只锁住两条不会因产品调整而失效的不变量：
 * 缺失键与非法值必须落到同一个缺省，显式存储的值必须优先。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsDefaultTest {

    private fun snapshot(map: Map<String, Any?>) = PrefsSnapshot { key -> map[key] }

    @Test fun `an existing glass choice survives the new surface setting`() {
        val glass = com.kinara.music.ui.theme.KinaraSurfaceStyle.GLASS
        val simple = com.kinara.music.ui.theme.KinaraSurfaceStyle.SIMPLE
        assertEquals(glass, parseSettings(snapshot(mapOf("theme" to "DEFAULT"))).surfaceStyle)
        assertEquals(simple, parseSettings(snapshot(emptyMap())).surfaceStyle)
        assertEquals(simple, parseSettings(snapshot(mapOf("theme" to "DEFAULT", "surface_style" to "SIMPLE"))).surfaceStyle)
    }

    @Test
    fun `missing key and corrupted value resolve to the same default`() {
        val missing = parseSettings(snapshot(emptyMap())).darkMode
        val corrupted = parseSettings(snapshot(mapOf("dark_mode" to "nonsense"))).darkMode
        assertEquals("缺失与非法值必须落到同一缺省，否则行为会随脏数据漂移", missing, corrupted)
    }

    @Test
    fun `an explicit stored value always wins`() {
        assertEquals(KinaraDarkMode.DARK, parseSettings(snapshot(mapOf("dark_mode" to "DARK"))).darkMode)
        assertEquals(KinaraDarkMode.LIGHT, parseSettings(snapshot(mapOf("dark_mode" to "LIGHT"))).darkMode)
        assertEquals(KinaraDarkMode.SYSTEM, parseSettings(snapshot(mapOf("dark_mode" to "SYSTEM"))).darkMode)
    }

    @Test
    fun `a stored system choice is never overridden by the default`() {
        // 用户显式选了"跟随系统"就必须真的跟随系统，不能被缺省值吃掉。
        val s = parseSettings(snapshot(mapOf("dark_mode" to "SYSTEM")))
        assertEquals(KinaraDarkMode.SYSTEM, s.darkMode)
    }
}
