/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 横屏锁的真实副作用：进入写 SENSOR_LANDSCAPE，退出写回进入前的方向。
 *
 * 1.4.5 的锁会泄漏，是因为复原分支只在"enabled 变 false 时旧 effect 的 onDispose"里跑，
 * 而那个闭包捕获的是旧值 true——这里用一个会真的翻转 enabled 的小宿主把它钉住。
 *
 * Robolectric 只记录 requestedOrientation 的写入，不会真的旋转窗口，所以这里只断整数，
 * 不断布局。这也正是本用例想验证的那件事：谁在什么时候写了什么方向。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [ChangingConfigurationsShadow::class])
class LandscapeLockEffectTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun resetShadow() {
        ChangingConfigurationsShadow.changingConfigurations = false
    }

    @Test
    fun `entering the lock writes landscape and leaving restores the recorded orientation`() {
        val before = compose.activity.requestedOrientation
        var locked by mutableStateOf(true)
        compose.setContent { LandscapeImmersive(locked) }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, compose.activity.requestedOrientation)

        compose.runOnIdle { locked = false }
        compose.waitForIdle()
        assertEquals("退出播放页必须把方向还回去", before, compose.activity.requestedOrientation)
    }

    @Test
    fun `a host that never locked writes nothing when it leaves`() {
        // 旧实现在这里写 -99（哨兵值），把方向交给系统去猜。
        val before = compose.activity.requestedOrientation
        var visible by mutableStateOf(true)
        compose.setContent {
            if (visible) LandscapeImmersive(false) else Unit
        }
        compose.waitForIdle()
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        assertEquals(before, compose.activity.requestedOrientation)
    }

    @Test
    fun `recreation keeps the recorded orientation instead of the applied lock`() {
        // 锁横屏会重建 Activity（manifest 没有 configChanges）：旧实例 isChangingConfigurations
        // 为真，所以它刻意不复原；新实例拿到的 requestedOrientation 已经是"自己写下去的锁"。
        // 如果这时把当前值当成原状记住，退出时就会"复原"成横屏——1.4.5 的泄漏就是这样来的。
        // 没有下面这个影子，Robolectric 里这条分支永远走不到，用例就成了假绿。
        val before = compose.activity.requestedOrientation
        val restoration = StateRestorationTester(compose)
        var locked by mutableStateOf(true)
        restoration.setContent { LandscapeImmersive(locked) }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, compose.activity.requestedOrientation)

        ChangingConfigurationsShadow.changingConfigurations = true
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        ChangingConfigurationsShadow.changingConfigurations = false

        compose.runOnIdle { locked = false }
        compose.waitForIdle()
        assertEquals("重建不得把锁当成原状", before, compose.activity.requestedOrientation)
    }
}
