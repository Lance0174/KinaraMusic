/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.dp
import com.kinara.music.data.Track
import com.kinara.music.online.LyricLine
import com.kinara.music.player.PlayerUiState
import com.kinara.music.ui.theme.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val song = Track("sample", "春日影", "CRYCHIC", "测试曲目", 240000, "local")
    private val player = PlayerUiState(listOf(song), 0, playing = true, positionMs = 74000, durationMs = 240000)

    private fun render(dark: Boolean, playback: Boolean) {
        compose.setContent {
            KinaraTheme(KinaraThemeKind.DEFAULT, if (dark) KinaraDarkMode.DARK else KinaraDarkMode.LIGHT) {
                val haze = rememberKinaraBackdrop()
                CompositionLocalProvider(LocalKinaraBackdrop provides haze) {
                    Box(Modifier.fillMaxSize().testTag("screen")) {
                        Box(Modifier.fillMaxSize().kinaraBackdropSource(haze)) {
                            KinaraDepthBackground()
                            KinaraTextureOverlay()
                        }
                        if (playback) {
                            PlayScreen(player, listOf(LyricLine(0, "远处的光"), LyricLine(73000, "照亮此刻的旋律")), {}, {}, {}, {})
                        } else KinaraShell(KinaraNav.LIBRARY, {}, player, {}, {}, {}) {
                            Column(Modifier.fillMaxSize()) {
                                PageTitle("音乐库", "KINARA · 为喜欢留一个位置")
                                LibrarySections(LibrarySection.SONGS, {})
                                LibraryScreen(LibraryUiState.Ready(listOf(song)), player, "", {}, listOf(song), { _, _ -> }, {})
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        if (!playback) {
            compose.onNodeWithContentDescription("下一曲").assertIsDisplayed()
            compose.onAllNodesWithText("音乐库")[0].assertIsDisplayed()
            compose.onNodeWithText("搜索本地歌曲、艺术家").assertIsDisplayed()
        } else {
            compose.onNodeWithText("1:14").assertIsDisplayed()
            compose.onNodeWithText("4:00").assertIsDisplayed()
        }
        val dir = File("build/reports/glass-screens").apply { mkdirs() }
        val file = File(dir, "api${android.os.Build.VERSION.SDK_INT}-${if (playback) "player" else "library"}-${if (dark) "dark" else "light"}.png")
        compose.runOnIdle {
            val image = drawHardwareFrame()
            file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun drawHardwareFrame(): android.graphics.Bitmap {
        val view = compose.activity.window.decorView
        val node = android.graphics.RenderNode("glass-test")
        node.setPosition(0, 0, view.width, view.height)
        repeat(2) {
            val canvas = node.beginRecording(view.width, view.height)
            view.draw(canvas)
            node.endRecording()
        }
        val method = android.graphics.HardwareRenderer::class.java.getDeclaredMethod("createHardwareBitmap",
            android.graphics.RenderNode::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        method.isAccessible = true
        return (method.invoke(null, node, view.width, view.height) as android.graphics.Bitmap)
            .copy(android.graphics.Bitmap.Config.ARGB_8888, false)
    }

    @Test fun fullGlassActuallyBlursBackdropComparedWithLite() {
        compose.setContent {
            KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.LIGHT) {
                val backdrop = rememberKinaraBackdrop()
                CompositionLocalProvider(LocalKinaraBackdrop provides backdrop) {
                    Box(Modifier.fillMaxSize()) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().kinaraBackdropSource(backdrop)) {
                            for (x in 0 until size.width.toInt() step 8) drawRect(
                                if ((x / 8) % 2 == 0) androidx.compose.ui.graphics.Color.Black else androidx.compose.ui.graphics.Color.White,
                                topLeft = androidx.compose.ui.geometry.Offset(x.toFloat(), 0f),
                                size = androidx.compose.ui.geometry.Size(8f, size.height))
                        }
                        KinaraGlassPanel(modifier = Modifier.offset(20.dp, 40.dp).size(340.dp, 120.dp)) {}
                        KinaraGlassPanel(modifier = Modifier.offset(20.dp, 200.dp).size(340.dp, 120.dp), level = KinaraGlassLevel.LITE) {}
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val image = drawHardwareFrame()
            fun contrast(y: Int) = (60 until 320).map { x ->
                kotlin.math.abs(android.graphics.Color.red(image.getPixel(x, y)) - android.graphics.Color.red(image.getPixel(x + 1, y)))
            }.average()
            org.junit.Assert.assertTrue("Real backdrop blur must reduce stripe contrast: full=${contrast(100)}, lite=${contrast(260)}",
                contrast(100) < contrast(260) * 0.35)
            File("build/reports/glass-screens/api${android.os.Build.VERSION.SDK_INT}-blur-proof.png")
                .also { it.parentFile.mkdirs() }.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun cachedGlassStillUpdatesWhenTheBackdropChanges() {
        var swapped by mutableStateOf(false)
        compose.setContent {
            KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.DARK) {
                val backdrop = rememberKinaraBackdrop()
                CompositionLocalProvider(LocalKinaraBackdrop provides backdrop) {
                    Box(Modifier.fillMaxSize()) {
                        androidx.compose.foundation.Canvas(Modifier.fillMaxSize().kinaraBackdropSource(backdrop)) {
                            drawRect(if (swapped) androidx.compose.ui.graphics.Color.Green else androidx.compose.ui.graphics.Color.Red)
                        }
                        KinaraGlassPanel(modifier = Modifier.offset(20.dp, 80.dp).size(340.dp, 160.dp)) {}
                    }
                }
            }
        }
        compose.waitForIdle()
        var before = 0
        compose.runOnIdle { before = drawHardwareFrame().getPixel(190, 160); swapped = true }
        compose.waitForIdle()
        compose.runOnIdle {
            val after = drawHardwareFrame().getPixel(190, 160)
            org.junit.Assert.assertTrue(android.graphics.Color.green(after) > android.graphics.Color.green(before) + 40)
            org.junit.Assert.assertTrue("live source must reach the cached glass display list: before=${Integer.toHexString(before)}, after=${Integer.toHexString(after)}",
                android.graphics.Color.red(before) > android.graphics.Color.red(after) + 40)
        }
    }

    @Test fun lightLibraryKeepsPlayerAndNavigationVisible() = render(false, false)
    @Test fun darkLibraryKeepsPlayerAndNavigationVisible() = render(true, false)
    @Test fun lightPlayerShowsProgressAndControls() = render(false, true)
    @Test fun darkPlayerShowsProgressAndControls() = render(true, true)
}
