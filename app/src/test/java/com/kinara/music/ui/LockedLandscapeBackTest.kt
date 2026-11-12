/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.kinara.music.data.KinaraSettings
import com.kinara.music.data.LyricRenderer
import com.kinara.music.data.PlayerVisualMode
import com.kinara.music.data.Track
import com.kinara.music.player.PlayerUiState
import com.kinara.music.ui.theme.KinaraDarkMode
import com.kinara.music.ui.theme.KinaraTheme
import com.kinara.music.ui.theme.KinaraThemeKind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 锁横屏停在封面页时，返回键必须是"关闭播放页"。
 *
 * 旧写法（1.4.5）把返回键绑在"歌词页"上，锁的范围一改，封面页就成了返回键的死角：
 * 按下没反应，用户只能去点左上角。这里把这条语义钉住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // CoverBackdrop 的 blur(40.dp) 需要真实渲染管线
class LockedLandscapeBackTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val player = PlayerUiState(
        listOf(Track("fixture-locked-back", "夜航星", "不才", "演示数据", 240_000, "local")), 0,
        playing = true, positionMs = 74_000, durationMs = 240_000,
    )

    @Test
    fun `back exits landscape while keeping the player open`() {
        var open by mutableStateOf(true)
        var landscape by mutableStateOf(true)
        var exits = 0
        var closes = 0
        compose.setContent {
            KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.LIGHT) {
                if (open) PlayScreen(
                    player, emptyList(), {}, {}, {}, {},
                    onClose = { closes++; open = false },
                    settings = KinaraSettings(
                        playerVisualMode = PlayerVisualMode.COVER,
                        lyricRenderer = LyricRenderer.FOLIA_LUMIERE,
                    ),
                    onVisualMode = {}, lockedLandscape = landscape,
                    onExitLandscape = { exits++; landscape = false },
                )
            }
        }
        compose.onNodeWithContentDescription("播放选项").assertDoesNotExist()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, exits)
        assertEquals(0, closes)
        compose.onNodeWithContentDescription("收起播放器").assertExists()
    }
}
