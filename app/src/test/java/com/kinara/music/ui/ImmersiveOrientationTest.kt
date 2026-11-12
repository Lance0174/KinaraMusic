/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 方向锁不能泄漏。
 *
 * 这是一次对抗性审查在我自己上一轮的代码里挖出来的真实缺陷：锁横屏会重建 Activity
 * （manifest 没有 configChanges），重建后系统已经把 requestedOrientation 恢复成
 * SENSOR_LANDSCAPE。旧实现每次进 effect 都"现读当前值当原状"，于是记住的是自己刚写下的锁，
 * 退出时"复原"成 SENSOR_LANDSCAPE 等于什么都没做——用户退出动态视界后整机仍锁横屏，
 * 转到竖屏毫无反应，直到进程结束。
 *
 * 这里把修复后的不变式钉死：**已记录就不再覆盖**。
 */
class ImmersiveOrientationTest {

    @Test
    fun `first entry records the real prior orientation`() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            recordOrientationOnce(ORIENTATION_UNSET, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED))
    }

    @Test
    fun `a later entry never overwrites the recorded value`() {
        // 关键回归：Activity 重建后读到的已经是自己写下去的锁，绝不能把它当成原状记住。
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            recordOrientationOnce(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE))
    }

    @Test
    fun `an explicitly chosen orientation is preserved`() {
        // 用户自己锁定过方向（例如平板分屏），退出后要回到它，而不是一律回到 UNSPECIFIED。
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            recordOrientationOnce(ORIENTATION_UNSET, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT))
    }

    @Test
    fun `nothing recorded means nothing is written back`() {
        // 第二个真实缺陷：从来没有锁过的时候，旧实现会把哨兵值 -99 直接写进 requestedOrientation。
        assertNull(orientationToRestore(ORIENTATION_UNSET))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            orientationToRestore(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED))
    }
}
