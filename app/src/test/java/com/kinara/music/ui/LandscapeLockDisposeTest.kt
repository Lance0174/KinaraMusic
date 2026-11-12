/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 关页（组合整体离开）和 enabled 翻转是两条独立的复原路径。
 *
 * 宿主是 KinaraApp：下载跳转、歌词设置跳转、返回键关页都会让播放页从组合里消失，
 * 这时 enabled 并没有"由真变假"再从新实例里跑一遍——只有 onDispose 能救。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LandscapeLockDisposeTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `closing the page while still locked restores the recorded orientation`() {
        val before = compose.activity.requestedOrientation
        var visible by mutableStateOf(true)
        compose.setContent { if (visible) LandscapeImmersive(true) else Unit }
        compose.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, compose.activity.requestedOrientation)

        // 宿主销毁锁（播放页关掉），enabled 这个值根本没机会变成 false。
        compose.runOnIdle { visible = false }
        compose.waitForIdle()
        assertEquals("关页时必须复原，不能只靠 enabled 翻转那条路", before, compose.activity.requestedOrientation)
    }
}
