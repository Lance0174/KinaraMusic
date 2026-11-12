/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 分屏/多窗口下 requestedOrientation 本来就会被系统忽略，硬锁只会白藏系统栏，
 * 退出时还可能把哨兵值当真写回去。这里验证"一行都不写"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MultiWindowLandscapeTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `split screen neither locks the orientation nor writes the sentinel back`() {
        val before = compose.activity.requestedOrientation
        shadowOf(compose.activity).setInMultiWindowMode(true)
        val locked = mutableStateOf(true)
        compose.setContent { LandscapeImmersive(locked.value) }
        compose.waitForIdle()
        assertEquals("分屏下不许接管方向", before, compose.activity.requestedOrientation)

        compose.runOnIdle { locked.value = false }
        compose.waitForIdle()
        assertEquals(before, compose.activity.requestedOrientation)
    }
}
