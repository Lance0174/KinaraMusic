/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.kinara.music.account.BiliLoginState
import com.kinara.music.data.*
import com.kinara.music.online.*
import com.kinara.music.player.PlayerUiState
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
class RedesignFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val songs = listOf("春日影", "夜航星", "花海", "银河与晚风", "落日之后", "夏天的回声", "未完待续", "晴空", "回到这里", "温柔的夜")
        .mapIndexed { index, title -> Track("fixture-$index", title, listOf("CRYCHIC", "不才", "周杰伦", "Kinara 演示")[index % 4], "截图测试数据", 240_000, "local") }
    private val player = PlayerUiState(songs, 0, playing = true, positionMs = 74_000, durationMs = 240_000)

    private fun shell(route: KinaraNav = KinaraNav.LIBRARY, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            KinaraTheme(KinaraThemeKind.DEFAULT, if (dark) KinaraDarkMode.DARK else KinaraDarkMode.LIGHT) {
                KinaraShell(route, {}, player, {}, {}, {}) { Column(Modifier.fillMaxSize()) {
                    PageTitle(route.label, "KINARA")
                    content()
                } }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val node = android.graphics.RenderNode("redesign")
            node.setPosition(0, 0, view.width, view.height)
            repeat(2) { val canvas = node.beginRecording(view.width, view.height); view.draw(canvas); node.endRecording() }
            val method = android.graphics.HardwareRenderer::class.java.getDeclaredMethod("createHardwareBitmap",
                android.graphics.RenderNode::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            val bitmap = (method.invoke(null, node, view.width, view.height) as android.graphics.Bitmap).copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            File("build/reports/redesign-screens/api${android.os.Build.VERSION.SDK_INT}-$name.png").also { it.parentFile!!.mkdirs() }
                .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun songMenuDoesNotStartPlaybackAndOffersFavoriteAndPlaylist() {
        var plays = 0; var favorites = 0; var additions = 0
        shell {
            LibrarySections(LibrarySection.SONGS, {})
            LibraryScreen(LibraryUiState.Ready(songs), player, "", {}, songs, { _, _ -> plays++ }, {},
                { favorites++ }, emptySet(), { additions++ })
        }
        capture("library-light")
        val more = compose.onNodeWithContentDescription("更多操作：春日影")
        more.assertIsDisplayed()
        more.performClick()
        compose.onNodeWithText("收藏歌曲").performClick()
        more.performClick(); compose.onNodeWithText("加入歌单").performClick()
        assertEquals(0, plays); assertEquals(1, favorites); assertEquals(1, additions)
    }

    @Test fun lastSongCanScrollCompletelyAboveFloatingPlayer() {
        val many = (0..39).map { songs[it % songs.size].copy(mediaId = "item-$it", title = "歌曲 $it") }
        shell(dark = true) { LibrarySections(LibrarySection.SONGS, {})
            LibraryScreen(LibraryUiState.Ready(many), player, "", {}, many, { _, _ -> }, {}) }
        compose.onNodeWithTag("local_tracks").performScrollToIndex(40)
        val song = compose.onNodeWithText("歌曲 39").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val mini = compose.onNodeWithTag("mini_player").fetchSemanticsNode().boundsInRoot
        assertTrue("last title must be above dock: $song vs $mini", song.bottom < mini.top)
        capture("library-dark-scrolled")
    }

    @Test fun settingsAccountAndSourceSwitchAreRealActions() {
        var logins = 0
        var settings by mutableStateOf(KinaraSettings())
        shell(KinaraNav.SETTINGS) {
            SettingsScreen(settings, {}, {}, { source, enabled ->
                if (source == "bilibili") settings = settings.copy(sources = settings.sources.copy(bilibili = enabled))
            }, onBiliLogin = { logins++ })
        }
        capture("settings-light")
        compose.onNodeWithText("扫码登录").performClick(); assertEquals(1, logins)
        compose.onNode(hasText("哔哩哔哩") and isToggleable()).performScrollTo()
        // performScrollTo sees the full scene, including the area behind the floating dock.
        compose.onNodeWithTag("settings_scroll").performTouchInput { swipeUp(startY = height * 0.7f, endY = height * 0.35f) }
        compose.onNode(hasText("哔哩哔哩") and isToggleable()).performClick().assertIsOff()
        assertFalse(settings.sources.bilibili)
        compose.onNodeWithText("B站（经Worker）").assertDoesNotExist()
    }

    // Robolectric 4.14 Dialog text entry uses the default display metrics. A custom 390x844
    // qualifier loops even with a bare Material TextField; render checks retain those dimensions.
    @Test @Config(qualifiers = "w320dp-h470dp-mdpi") fun playlistImportAndCreateUseSeparateFlows() {
        var created = ""; var imported = ""; var qq = true
        shell { LibrarySections(LibrarySection.PLAYLISTS, {})
            PlaylistScreen(emptyList(), null, { _, _ -> }, { created = it }, { _, _ -> }, {}, { _, _ -> },
                { qq = false; imported = it }, { qq = true; imported = it }) }
        compose.onNodeWithText("新建歌单").performClick()
        compose.onNodeWithText("歌单名称").performTextInput("夜晚散步")
        compose.onNodeWithText("确定").performClick(); assertEquals("夜晚散步", created)
        compose.onNodeWithText("导入歌单").performClick()
        compose.onNodeWithText("歌单链接或数字 ID").performTextInput("https://music.163.com/#/playlist?id=123456")
        compose.onNodeWithText("预览歌单").performClick()
        assertEquals("123456", imported); assertFalse(qq)
    }

    @Test fun playlistDetailBackAndDeleteNeedExplicitActions() {
        var deleted = false
        shell { LibrarySections(LibrarySection.PLAYLISTS, {})
            PlaylistScreen(listOf(Playlist("p1", "夜晚散步", 0, songs)), null, { _, _ -> }, {}, { _, _ -> }, { deleted = true }, { _, _ -> }) }
        capture("playlists")
        compose.onNodeWithText("夜晚散步").performClick()
        compose.onNodeWithContentDescription("歌单选项").performClick()
        compose.onNodeWithText("删除歌单").performClick()
        assertFalse(deleted)
        compose.onNodeWithText("取消").performClick(); assertFalse(deleted)
        compose.onNodeWithContentDescription("返回歌单列表").performClick()
        compose.onNodeWithText("导入歌单").assertIsDisplayed()
    }

    @Test fun searchSourceFilterKeepsBilibiliResultsReachable() {
        val a = OnlineTrack("netease", "123", "花海", "周杰伦", "测试")
        val b = OnlineTrack("bilibili", "BV1xx411c7mD|", "音乐现场", "演示UP主", "测试")
        var played: OnlineTrack? = null
        shell(KinaraNav.SEARCH) {
            SearchField("音乐", {}, "歌曲、艺术家、B站视频", Modifier.padding(horizontal = 20.dp))
            OnlineSearchScreen(OnlineSearchState(OnlineSearchState.Phase.DATA, listOf(OnlineResult("netease", listOf(a)), OnlineResult("bilibili", listOf(b))), "音乐"),
                { list, index -> played = list[index]; assertEquals(1, list.size) }, {})
        }
        capture("search")
        compose.onNodeWithText("哔哩哔哩").performScrollTo().performClick()
        compose.onNodeWithText("花海").assertDoesNotExist()
        compose.onNodeWithText("音乐现场").performClick(); assertEquals(b, played)
    }

    @Test fun searchPriorityAndFailureFallbackMatchThePlaybackQueue() {
        val results = SOURCE_PRIORITY.reversed().map { source -> OnlineResult(source,
            listOf(OnlineTrack(source, "id", "结果-$source", "演示歌手", "测试"))) }
        var state by mutableStateOf(OnlineSearchState(OnlineSearchState.Phase.DATA, results, "音乐", searching = false))
        var queue = emptyList<OnlineTrack>()
        shell(KinaraNav.SEARCH) { OnlineSearchScreen(state, { tracks, _ -> queue = tracks }, {}) }
        compose.onNodeWithText("结果-netease").performClick()
        assertEquals(SOURCE_PRIORITY, queue.map { it.platform })
        val netease = compose.onNodeWithText("结果-netease").fetchSemanticsNode().boundsInRoot
        val qq = compose.onNodeWithText("结果-qq").fetchSemanticsNode().boundsInRoot
        assertTrue(netease.top < qq.top)
        capture("source-priority")
        compose.onNodeWithText("QQ音乐", useUnmergedTree = true).performScrollTo().performClick()
        compose.runOnIdle { state = state.copy(results = state.results.filterNot { it.platform in listOf("qq", "kugou") } +
            listOf(OnlineResult("qq", errorMessage = "QQ音乐搜索受限（500003）"), OnlineResult("kugou", errorMessage = "请求超时"))) }
        compose.onNodeWithText("QQ音乐暂不可用，已显示酷我音乐结果").assertIsDisplayed()
        compose.onNodeWithText("结果-kuwo").performClick()
        assertEquals(listOf("kuwo"), queue.map { it.platform })
        capture("source-fallback")
    }

    @Test fun playerSeekCommitsOnlyAfterReleaseAndLyricsQueueWork() {
        var seeks = 0; var picked = -1
        compose.setContent { KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.LIGHT) {
            PlayerBackdrop(emptyList()) { PlayScreen(player, listOf(LyricLine(0, "远处的光"), LyricLine(73_000, "照亮此刻的旋律")), {}, {}, {}, { seeks++ },
                onQueuePick = { picked = it }) }
        } }
        val slider = compose.onNodeWithContentDescription("播放进度")
        slider.performTouchInput { down(center); moveTo(androidx.compose.ui.geometry.Offset(width * 0.8f, centerY)) }
        assertEquals(0, seeks)
        slider.performTouchInput { up() }; assertEquals(1, seeks)
        compose.onNodeWithText("歌词").performClick(); compose.onNodeWithText("照亮此刻的旋律").assertIsDisplayed()
        compose.onNodeWithContentDescription("打开播放队列").performClick()
        compose.onNodeWithText("夜航星").performClick(); assertEquals(1, picked)
    }

    @Test fun expiredQrHasRefreshAndCancelInsteadOfStaleImage() {
        var refresh = 0; var close = 0
        compose.setContent { KinaraTheme(KinaraThemeKind.DEFAULT) { BiliLoginDialog(
            BiliLoginState(true, message = "二维码已过期", refreshable = true), { refresh++ }, { close++ }) } }
        compose.onNodeWithContentDescription("哔哩哔哩官方登录二维码").assertDoesNotExist()
        compose.onNodeWithText("刷新二维码").performClick(); assertEquals(1, refresh)
        compose.onNodeWithText("取消").performClick(); assertEquals(1, close)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun smallScreenAndLargeTextKeepAccountActionReachable() {
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(1f, 1.35f)) {
            KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.DARK) {
                KinaraShell(KinaraNav.SETTINGS, {}, player, {}, {}, {}) { Column(Modifier.fillMaxSize()) {
                    PageTitle("设置", "KINARA"); SettingsScreen(KinaraSettings(), {}, {}, { _, _ -> })
                } }
            }
        } }
        compose.onNodeWithText("扫码登录").performScrollTo().assertIsDisplayed()
        capture("settings-320-large-text")
    }

    @Test @Config(qualifiers = "w844dp-h390dp-mdpi") fun landscapePlayerControlsRemainReachable() {
        compose.setContent { KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.DARK) {
            PlayerBackdrop(emptyList()) { PlayScreen(player, emptyList(), {}, {}, {}, {}) }
        } }
        compose.onNodeWithContentDescription("暂停").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("收起播放器").assertIsDisplayed()
        capture("player-landscape")
    }
}
