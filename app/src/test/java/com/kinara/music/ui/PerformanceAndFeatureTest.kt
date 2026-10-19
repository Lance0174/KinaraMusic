/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.kinara.music.data.*
import com.kinara.music.player.*
import com.kinara.music.ui.theme.*
import org.junit.Assert.*
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
class PerformanceAndFeatureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val songs = (1..3000).map { Track("song-$it", "歌曲 $it", "艺术家", "专辑", 240_000, "netease", onlineKey = OnlineKey("netease", "$it")) }
    private val player = PlayerUiState(songs, 0, playing = true, positionMs = 74_000, durationMs = 240_000)

    @Test fun progressTicksDoNotRecomposeLibraryOrRoot() {
        val clock = mutableStateOf(PlayerProgress(0, 240_000))
        var roots = 0; var pages = 0
        compose.setContent {
            SideEffect { roots++ }
            KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.LIGHT) {
                CompositionLocalProvider(LocalPlayerProgress provides clock) {
                    KinaraShell(KinaraNav.LIBRARY, {}, player, {}, {}, {}) {
                        SideEffect { pages++ }
                        Column { PageTitle("音乐库", ""); LibraryScreen(LibraryUiState.Ready(songs), player, "", {}, songs, { _, _ -> }, {}) }
                    }
                }
            }
        }
        compose.waitForIdle()
        val originalRoots = roots; val originalPages = pages
        repeat(20) { tick -> compose.runOnIdle { clock.value = PlayerProgress((tick + 1) * 500L, 240_000) }; compose.waitForIdle() }
        assertEquals(originalRoots, roots); assertEquals(originalPages, pages)
        File("build/reports/performance/progress-api${android.os.Build.VERSION.SDK_INT}.txt").apply { parentFile!!.mkdirs() }
            .writeText("tracks=3000\nticks=20\nroot_recompositions=${roots - originalRoots}\nlibrary_recompositions=${pages - originalPages}\n")
    }

    @Test fun errorAndBufferingDoNotMovePlayerButtons() {
        var state by mutableStateOf(player)
        compose.setContent { KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.LIGHT) { PlayerBackdrop(emptyList()) { PlayScreen(state, emptyList(), {}, {}, {}, {}) } } }
        val before = compose.onNodeWithContentDescription("上一曲").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state = player.copy(error = "播放失败，当前音源没有可播放地址，请更换音源后重试") }
        val after = compose.onNodeWithContentDescription("上一曲").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        compose.runOnIdle { state = player.copy(buffering = true) }
        assertEquals(before, compose.onNodeWithContentDescription("上一曲").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun playerMenuOffersWorkingFeatureCallbacks() {
        var additions = 0; var downloads = 0; var timers = 0; var diagnostics = 0
        compose.setContent { KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.DARK) {
            PlayerBackdrop(emptyList()) { PlayScreen(player, emptyList(), {}, {}, {}, {}, onAdd = { additions++ },
                onDownload = { downloads++ }, onTimer = { timers++ }, onDiagnostics = { diagnostics++ }) }
        } }
        for (action in listOf("加入歌单", "下载音频", "定时关闭", "播放诊断")) {
            compose.onNodeWithContentDescription("播放选项").performClick(); compose.onNodeWithText(action).performClick()
        }
        assertEquals(listOf(1, 1, 1, 1), listOf(additions, downloads, timers, diagnostics))
        capture("player-glass-dark")
    }

    @Test fun playlistFileActionsKeepTheirTargetAndFavoritesCannotBeDeleted() {
        var imported: String? = null; var exported: Playlist? = null
        val playlist = Playlist(LibraryRepository.FAVORITES, "我喜欢", 0, songs.take(4))
        compose.setContent { KinaraTheme(KinaraThemeKind.INDUSTRIAL, KinaraDarkMode.LIGHT) {
            PlaylistScreen(listOf(playlist), null, { _, _ -> }, {}, { _, _ -> }, {}, { _, _ -> },
                onImportLocal = { imported = it }, onExport = { exported = it })
        } }
        compose.onNodeWithText("我喜欢").performClick()
        compose.onNodeWithContentDescription("歌单选项").performClick()
        compose.onNodeWithText("删除歌单").assertDoesNotExist()
        compose.onNodeWithText("导入本地歌曲").performClick(); assertEquals(playlist.id, imported)
        compose.onNodeWithContentDescription("歌单选项").performClick()
        compose.onNodeWithText("导出 JSON 歌单").performClick(); assertEquals(playlist, exported)
    }

    @Test fun industrialLibraryHasEndfieldPaletteInBothModes() {
        var dark by mutableStateOf(true)
        compose.setContent { KinaraTheme(KinaraThemeKind.INDUSTRIAL, if (dark) KinaraDarkMode.DARK else KinaraDarkMode.LIGHT) {
            KinaraShell(KinaraNav.LIBRARY, {}, player, {}, {}, {}) {
                Column { PageTitle("音乐库", ""); LibrarySections(LibrarySection.SONGS, {})
                    LibraryScreen(LibraryUiState.Ready(songs), player, "", {}, songs, { _, _ -> }, {}) }
            }
        } }
        compose.onNodeWithText("KINARA / AUDIO").assertIsDisplayed()
        capture("endfield-dark")
        compose.runOnIdle { dark = false }
        capture("endfield-light")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val node = android.graphics.RenderNode("kinara")
            node.setPosition(0, 0, view.width, view.height)
            repeat(2) { val canvas = node.beginRecording(view.width, view.height); view.draw(canvas); node.endRecording() }
            val method = android.graphics.HardwareRenderer::class.java.getDeclaredMethod("createHardwareBitmap", android.graphics.RenderNode::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            val bitmap = (method.invoke(null, node, view.width, view.height) as android.graphics.Bitmap).copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            File("build/reports/v110-screens/api${android.os.Build.VERSION.SDK_INT}-$name.png").apply { parentFile!!.mkdirs() }
                .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
