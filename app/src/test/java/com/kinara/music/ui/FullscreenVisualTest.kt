/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.kinara.music.data.KinaraSettings
import com.kinara.music.online.LyricLine
import com.kinara.music.data.LyricRenderer
import com.kinara.music.data.PlayerVisualMode
import com.kinara.music.data.Track
import com.kinara.music.player.PlayerUiState
import com.kinara.music.ui.theme.KinaraDarkMode
import com.kinara.music.ui.theme.KinaraTheme
import com.kinara.music.ui.theme.KinaraThemeKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 动态视界全屏渲染的往返用例。
 *
 * 这条用例拦下的是一类真实踩坑：全屏渲染时翻页容器(HorizontalPager)不挂载，
 * 而 Compose 的 PagerState 在没有挂载 Pager 时不会推进（scrollToPage /
 * animateScrollToPage 都不动，实测）。如果继续让 pager.currentPage 当唯一事实来源，
 * 「返回封面」会变成一个点了没反应的死按钮。现在模式由独立状态掌握，这里验证它确实能退回封面。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FullscreenVisualTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val player = PlayerUiState(
        listOf(Track("fixture-fullscreen", "夜航星", "不才", "演示数据", 240_000, "local")), 0,
        playing = true, positionMs = 74_000, durationMs = 240_000,
    )

    @Test
    fun `dynamic visual renders fullscreen and can return to the cover`() {
        compose.setContent {
            KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.LIGHT) {
                PlayScreen(
                    player, listOf(LyricLine(0, "远处的光")), {}, {}, {}, {},
                    settings = KinaraSettings(
                        playerVisualMode = PlayerVisualMode.LYRICS,
                        lyricRenderer = LyricRenderer.FOLIA_LUMIERE,
                    ),
                    // 传入 onVisualMode 才会走"跟随设置"的模式来源（正式应用就是这样接的）；
                    // 不传时模式是页面内独立状态，初始固定为封面页。
                    onVisualMode = {},
                )
            }
        }
        // 全屏分支：翻页容器整体不挂载，画面直接铺满窗口。
        compose.onNodeWithTag("player_pages").assertDoesNotExist()
        // 左上角「返回封面」必须真的能把界面退回封面页。
        compose.onNodeWithContentDescription("返回封面").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("player_pages").assertExists()
    }

    @Test
    fun `immersive landscape has visual lyrics and exactly one seek control`() {
        compose.setContent {
            KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.LIGHT) {
                PlayScreen(
                    player, emptyList(), {}, {}, {}, {},
                    settings = KinaraSettings(
                        playerVisualMode = PlayerVisualMode.COVER,
                        lyricRenderer = LyricRenderer.FOLIA_LUMIERE,
                    ),
                    onVisualMode = {}, lockedLandscape = true,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("player_pages").assertDoesNotExist()
        compose.onNodeWithTag("folia_lyrics").assertExists()
        compose.onNodeWithTag("immersive_visual").assertExists()
        compose.onNodeWithContentDescription("播放进度").assertExists()
        listOf("查看歌词", "收起播放器", "返回封面", "播放选项", "暂停", "打开播放队列").forEach {
            compose.onNodeWithContentDescription(it).assertDoesNotExist()
        }
    }
}
