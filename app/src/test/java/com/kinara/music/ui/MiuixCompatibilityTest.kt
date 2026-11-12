package com.kinara.music.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import com.kinara.music.data.*
import com.kinara.music.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Composable
internal fun SettingsTestHost(settings: KinaraSettings, onTheme: (KinaraThemeKind) -> Unit, onDark: (String) -> Unit,
    onSource: (String, Boolean) -> Unit, onPreferences: ((KinaraSettings) -> KinaraSettings) -> Unit = {}, onPreviewLyrics: (() -> Unit)? = null) {
    top.yukonga.miuix.kmp.basic.Scaffold {
        CompositionLocalProvider(LocalDockInset provides 0.dp) {
            SettingsScreen(settings, onTheme, onDark, onSource, onPreferences = onPreferences, onPreviewLyrics = onPreviewLyrics)
        }
    }
}

/**
 * The API 24 run guards the narrowly scoped manifest compatibility override.
 * Legacy Robolectric graphics cannot hit-test Miuix's animated dialog reliably, so use its
 * actual accessibility click action here. Native pointer input is covered on API 31/34.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], qualifiers = "w390dp-h844dp-mdpi")
class MiuixCompatibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun officialSettingsMenusPersistAndChangeTheRealRenderer() {
        var settings by mutableStateOf(KinaraSettings())
        compose.setContent {
            KinaraTheme(settings.theme, settings.darkMode, settings.surfaceStyle) {
                SettingsTestHost(settings, {}, {}, { _, _ -> }, onPreferences = { settings = it(settings) })
            }
        }
        compose.onNodeWithText("歌词视觉模式").performScrollTo().performClick()
        compose.onNodeWithText("动态视界").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(LyricRenderer.FOLIA_LUMIERE, settings.lyricRenderer)
            assertEquals(PlayerVisualMode.LYRICS, settings.playerVisualMode)
        }
        compose.onNodeWithText("视觉编排").performScrollTo().performClick()
        compose.onNodeWithText("诗行").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { assertEquals(LyricVisualStyle.SONNET, settings.lyricVisualStyle) }
        compose.onNodeWithText("界面质感").performScrollTo().performClick()
        compose.onNodeWithText("液态玻璃").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle {
            assertEquals(KinaraSurfaceStyle.GLASS, settings.surfaceStyle)
            assertEquals(settings, parseSettings { settings.toPrefsMap()[it] })
        }
    }
}
