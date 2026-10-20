/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.kinara.music.data.*
import com.kinara.music.online.*
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
class DynamicLyricsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val track = Track("lyric-demo", "把此刻留在旋律里", "Kinara", "夜航", 200_000, "netease", onlineKey = OnlineKey("netease", "fixture"))
    private val player = PlayerUiState(listOf(track), 0, positionMs = 3500, durationMs = 200_000)
    private val lyrics = listOf(LyricLine(0, "沿着夜色，慢慢靠近"),
        LyricLine(3000, "让每一个字，都有回响", 5000,
            listOf(LyricWord(3000, 3800, "让每一个字，"), LyricWord(3800, 5000, "都有回响")), "Let every word resonate", "ràng měi yí gè zì"),
        LyricLine(6000, "光落在你眼底"), LyricLine(9000, "音乐，继续流动"))

    @Test fun horizontalSwipeAndTapSwitchPagesWithoutSeekingOrChangingRenderer() {
        var settings by mutableStateOf(KinaraSettings(lyricRenderer = LyricRenderer.GLOW))
        var seeks = 0; var plays = 0
        compose.setContent { KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.DARK) {
            PlayScreen(player, lyrics, { plays++ }, {}, {}, { seeks++ }, settings = settings,
                onVisualMode = { settings = settings.copy(playerVisualMode = it) })
        } }
        compose.onNodeWithTag("player_pages").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("dynamic_lyrics").assertIsDisplayed()
        assertEquals(PlayerVisualMode.LYRICS, settings.playerVisualMode)
        compose.onNodeWithTag("player_pages").performTouchInput { swipeRight() }
        compose.onNodeWithContentDescription("查看歌词").assertIsDisplayed()
        compose.onNodeWithText("歌词").performClick()
        compose.onNodeWithTag("dynamic_lyrics").assertIsDisplayed()
        compose.onNodeWithText("动态").assertDoesNotExist()
        assertEquals(LyricRenderer.GLOW, settings.lyricRenderer)
        assertEquals(0, seeks); assertEquals(0, plays)
    }

    @Test fun visualDirectorsAndQualityAreSelectedInSettingsAndRemembered() {
        var settings by mutableStateOf(KinaraSettings())
        compose.setContent { KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.LIGHT) {
            SettingsScreen(settings, {}, {}, { _, _ -> }, onPreferences = { settings = it(settings) })
        } }
        compose.onNodeWithText("动态视界").performScrollTo().performClick()
        assertEquals(LyricRenderer.FOLIA_LUMIERE, settings.lyricRenderer)
        compose.onNodeWithText("动态排字").performScrollTo().performClick()
        assertEquals(LyricVisualStyle.SONNET, settings.lyricVisualStyle)
        compose.onNodeWithText("凝彩").performScrollTo().performClick()
        assertEquals(LyricVisualStyle.TEMPERA, settings.lyricVisualStyle)
        compose.onNodeWithText("省电").performScrollTo().performClick()
        assertEquals(LumiereQuality.LOW, settings.lumiereQuality)
        assertEquals(settings, parseSettings { settings.toPrefsMap()[it] })
        compose.onNodeWithText("视觉编排").performScrollTo()
        capture("folia-settings")
        compose.onNodeWithText("应用更新").performScrollTo()
        compose.onNodeWithText("检查更新").performScrollTo().assertIsDisplayed()
        capture("update-settings")
        compose.onNodeWithText("Folia 流光").assertDoesNotExist()
    }

    @Test fun savedSettingsOpenTheSelectedDirectorEvenWithReducedMotionAndCanRetryFailure() {
        val prefs = KinaraPrefs.get(compose.activity)
        prefs.save(KinaraSettings(reduceLyricMotion = true))
        val vm = com.kinara.music.MainViewModel(compose.activity.application)
        var preview by mutableStateOf(false)
        compose.setContent {
            val settings by vm.settings.collectAsState()
            KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.DARK) {
                if (preview) PlayScreen(player, lyrics, {}, {}, {}, {}, settings = settings,
                    onVisualMode = { mode -> vm.update { it.copy(playerVisualMode = mode) } })
                else SettingsScreen(settings, {}, {}, { _, _ -> }, onPreferences = vm::update,
                    onPreviewLyrics = { preview = true })
            }
        }
        compose.onNodeWithText("动态视界").performScrollTo().performClick()
        compose.onNodeWithText("动态排字").performScrollTo().performClick()
        compose.onNodeWithText("查看当前歌曲效果").performScrollTo().performClick()
        compose.onNodeWithTag("folia_lyrics").assertIsDisplayed()
        compose.onNodeWithTag("dynamic_lyrics").assertDoesNotExist()
        assertEquals(PlayerVisualMode.LYRICS, prefs.settings().playerVisualMode)
        assertEquals(LyricVisualStyle.SONNET, prefs.settings().lyricVisualStyle)
        fun web(view: android.view.View = compose.activity.window.decorView): android.webkit.WebView? {
            if (view is android.webkit.WebView) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) web(view.getChildAt(i))?.let { return it }
            return null
        }
        lateinit var first: android.webkit.WebView
        compose.runOnIdle {
            first = web()!!
            first.webViewClient.onRenderProcessGone(first, null)
        }
        compose.onNodeWithText("重新加载动态视界").performClick()
        compose.onNodeWithTag("dynamic_lyrics").assertDoesNotExist()
        compose.runOnIdle { assertNotSame(first, web()); vm.update { it.copy(lyricVisualStyle = LyricVisualStyle.TEMPERA) } }
        compose.onNodeWithTag("folia_lyrics").assertIsDisplayed()
        compose.onNodeWithTag("dynamic_lyrics").assertDoesNotExist()
        assertEquals(LyricVisualStyle.TEMPERA, prefs.settings().lyricVisualStyle)
    }

    @Test fun calibrationUsesActualRecordingAndAppliesToLyricSeekImmediately() {
        val actual = track.copy(mediaId = "other", onlineKey = OnlineKey("qq", "other"), source = "qq")
        var settings by mutableStateOf(KinaraSettings(playerVisualMode = PlayerVisualMode.LYRICS, reduceLyricMotion = true))
        var sought = -1L
        compose.setContent { KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.DARK) {
            PlayScreen(player.copy(actualTrack = actual), lyrics, {}, {}, {}, { sought = it }, settings = settings,
                onVisualMode = { settings = settings.copy(playerVisualMode = it) },
                onLyricOffset = { identity, offset -> settings = settings.withLyricOffset(identity, offset) })
        } }
        compose.onNodeWithContentDescription("播放选项").performClick()
        compose.onNodeWithText("歌词校准").performClick()
        compose.onNodeWithText("提前 0.5 秒").performClick()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("让每一个字，都有回响").performClick()
        assertEquals(2500L, sought)
        assertEquals(-500L, settings.lyricDelayFor(actual))
        assertEquals(0L, settings.lyricDelayFor(track))
    }

    @Test fun modesSwitchWithoutPlaybackCallbacksAndSurvivePlayerReopen() {
        var settings by mutableStateOf(KinaraSettings())
        var visible by mutableStateOf(true)
        var plays = 0; var seeks = 0
        compose.setContent { KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.DARK) {
            if (visible) PlayScreen(player, lyrics, { plays++ }, {}, {}, { seeks++ }, settings = settings,
                onVisualMode = { settings = settings.copy(playerVisualMode = it) })
        } }
        capture("cover-glass-dark")
        compose.onNodeWithText("歌词").performClick()
        compose.onNodeWithTag("dynamic_lyrics").assertIsDisplayed()
        compose.onNodeWithText("让每一个字，都有回响").assertIsDisplayed()
        assertEquals(PlayerVisualMode.LYRICS, settings.playerVisualMode)
        compose.runOnIdle { visible = false }; compose.runOnIdle { visible = true }
        compose.onNodeWithTag("dynamic_lyrics").assertIsDisplayed()
        capture("dynamic-glass-dark")
        compose.onNodeWithContentDescription("返回封面").performClick()
        compose.onNodeWithContentDescription("查看歌词").assertIsDisplayed()
        assertEquals(0, plays); assertEquals(0, seeks)
    }

    @Test fun wordProgressChangesDrawingWithoutRecomposingPlayer() {
        val clock = mutableStateOf(PlayerProgress(3200, 200_000))
        var roots = 0
        compose.setContent {
            SideEffect { roots++ }
            KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.DARK) {
                CompositionLocalProvider(LocalPlayerProgress provides clock) {
                    PlayScreen(player, lyrics, {}, {}, {}, {}, settings = KinaraSettings(playerVisualMode = PlayerVisualMode.LYRICS), onVisualMode = {})
                }
            }
        }
        compose.waitForIdle()
        val count = roots
        val before = capture("words-before")
        repeat(10) { i ->
            compose.runOnIdle { clock.value = PlayerProgress(3300 + i * 100L, 200_000) }
            compose.waitForIdle()
            if (android.os.Build.VERSION.SDK_INT == 34) capture("word-frame-$i")
        }
        val after = capture("words-after")
        assertEquals(count, roots)
        val changed = (0 until before.width).sumOf { x -> (50 until before.height / 2).count { y -> before.getPixel(x, y) != after.getPixel(x, y) } }
        assertTrue("word drawing must change independently: changed=$changed", changed > 30)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h568dp-mdpi")
    fun smallScreenKeepsControlsAndLyricSeekUsesTheSameDelay() {
        var sought = -1L
        compose.setContent { KinaraTheme(KinaraThemeKind.INDUSTRIAL, KinaraDarkMode.DARK) {
            PlayScreen(player, lyrics, {}, {}, {}, { sought = it },
                settings = KinaraSettings(playerVisualMode = PlayerVisualMode.LYRICS, showTranslation = false,
                    showRomanization = true, reduceLyricMotion = true, lyricDelayMs = 200), onVisualMode = {})
        } }
        compose.onNodeWithContentDescription("播放").assertIsDisplayed()
        compose.onNodeWithText("Let every word resonate").assertDoesNotExist()
        compose.onNodeWithText("让每一个字，都有回响").performClick()
        assertEquals(3200L, sought)
        capture("dynamic-industrial-small")
    }

    @Test fun manualBrowsingDoesNotGetPulledAwayByTheNextLyric() {
        val clock = mutableStateOf(PlayerProgress(3500, 200_000))
        val longLyrics = (0..40).map { LyricLine(it * 1000L, "第 $it 句歌词") }
        compose.setContent { KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.DARK) {
            CompositionLocalProvider(LocalPlayerProgress provides clock) {
                PlayScreen(player, longLyrics, {}, {}, {}, {},
                    settings = KinaraSettings(playerVisualMode = PlayerVisualMode.LYRICS), onVisualMode = {})
            }
        } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("dynamic_lyric_list").performTouchInput { swipeUp(durationMillis = 500) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("回到当前歌词").assertIsDisplayed()
        compose.runOnIdle { clock.value = PlayerProgress(4500, 200_000) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("回到当前歌词").assertIsDisplayed()
        compose.onNodeWithText("第 4 句歌词").assertIsNotDisplayed()
        compose.onNodeWithText("回到当前歌词").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("第 4 句歌词").assertIsDisplayed()
        compose.onNodeWithText("回到当前歌词").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h568dp-mdpi")
    fun largeTextKeepsTheTransportAndModeSwitchReachable() {
        var modes = 0; var plays = 0
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
            KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.LIGHT) {
                PlayScreen(player, lyrics, { plays++ }, {}, {}, {}, onMode = { modes++ })
            }
        } }
        compose.onNodeWithContentDescription("播放").performScrollTo().performClick()
        compose.onNodeWithContentDescription("切换播放顺序").performScrollTo().performClick()
        assertEquals(1, plays); assertEquals(1, modes)
        compose.onNodeWithText("歌词").performScrollTo()
        capture("cover-glass-light-large-text")
        compose.onNodeWithText("歌词").performScrollTo().performClick()
        compose.onNodeWithContentDescription("播放").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("播放选项").assertIsDisplayed()
    }

    private fun capture(name: String): android.graphics.Bitmap {
        compose.waitForIdle()
        var result: android.graphics.Bitmap? = null
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val node = android.graphics.RenderNode("lyrics-preview")
            node.setPosition(0, 0, view.width, view.height)
            repeat(2) { val canvas = node.beginRecording(view.width, view.height); view.draw(canvas); node.endRecording() }
            val method = android.graphics.HardwareRenderer::class.java.getDeclaredMethod("createHardwareBitmap", android.graphics.RenderNode::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            result = (method.invoke(null, node, view.width, view.height) as android.graphics.Bitmap).copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            File("build/reports/v121-screens/api${android.os.Build.VERSION.SDK_INT}-$name.png").apply { parentFile!!.mkdirs() }
                .outputStream().use { result!!.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        return result!!
    }
}
