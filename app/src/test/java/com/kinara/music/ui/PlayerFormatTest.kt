/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 播放页的纯函数。
 *
 * [speedLabel] 不能用浮点 toString：1.25f 会印成 "1.25"（可以），但 1.5f 之类很容易漏出
 * "1.5000001" 这种尾巴；[formatMs] 旧实现只输出「分:秒」，75 分钟会显示成 "75:12"，
 * 读起来像 75 秒。
 */
class PlayerFormatTest {

    @Test
    fun `speed labels keep only meaningful decimals`() {
        assertEquals("1.0×", speedLabel(1f))
        assertEquals("1.25×", speedLabel(1.25f))
        assertEquals("1.5×", speedLabel(1.5f))
        assertEquals("0.75×", speedLabel(0.75f))
        assertEquals("2.0×", speedLabel(2f))
    }

    @Test
    fun `duration switches to hours past sixty minutes`() {
        assertEquals("0:59", formatMs(59_000))
        assertEquals("1:02", formatMs(62_000))
        // 75 分 12 秒：旧实现会给出 "75:12"。
        assertEquals("1:15:12", formatMs(4_512_000))
        assertEquals("1:02:05", formatMs(3_725_000))
    }

    @Test
    fun `negative durations never produce negative timestamps`() {
        assertEquals("0:00", formatMs(-1_000))
    }
}
