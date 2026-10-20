/* Kinara WebView verification fixture — AGPL-3.0. Never included in release APKs. */
package com.kinara.music.ui

import android.os.Bundle
import android.os.SystemClock
import android.content.pm.ActivityInfo
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.kinara.music.data.*
import com.kinara.music.online.LyricLine
import com.kinara.music.online.LyricWord
import com.kinara.music.player.*
import com.kinara.music.ui.theme.*
import kotlinx.coroutines.delay

/** Real production player and WebView, deterministic local lyrics, no network or audio credentials. */
class FoliaProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedOrientation = if (intent.getStringExtra("orientation") == "landscape")
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        WebView.setWebContentsDebuggingEnabled(true)
        val style = LyricVisualStyle.entries.firstOrNull { it.wireName == intent.getStringExtra("style") }
            ?: LyricVisualStyle.LUMIERE
        val quality = LumiereQuality.entries.firstOrNull { it.wireName == intent.getStringExtra("quality") }
            ?: LumiereQuality.BALANCED
        val playing = intent.getBooleanExtra("playing", false)
        val startMs = intent.getLongExtra("position", 6100).coerceIn(0, 60000)
        val text = listOf("光落在你眼底", "让每一个字都有回响", "沿着夜色慢慢靠近", "音乐继续流动")
        val track = Track("folia-probe", text[0], "Kinara", "视觉测试", 60000, "local", localPath = "/fixture.wav")
        val lyrics = if (intent.getBooleanExtra("empty", false)) emptyList() else List(12) { index ->
            val start = index * 4000L
            val words = if (intent.getBooleanExtra("lrc", false)) emptyList() else
                listOf(LyricWord(start, start + 3600, text[index % 4]))
            LyricLine(start, text[index % 4], start + 3600, words)
        }
        setContent {
            var settings by remember { mutableStateOf(KinaraSettings(lyricRenderer = LyricRenderer.FOLIA_LUMIERE,
                lyricVisualStyle = style, lumiereQuality = quality, playerVisualMode = PlayerVisualMode.LYRICS,
                reduceLyricMotion = intent.getBooleanExtra("reduced", false))) }
            val progress = remember { mutableStateOf(PlayerProgress()) }
            var seek by remember { mutableLongStateOf(startMs) }
            LaunchedEffect(seek) {
                val started = SystemClock.elapsedRealtime()
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    progress.value = PlayerProgress((seek + if (playing) now - started else 0) % 60000,
                        60000, now, playing, mediaId = track.mediaId, recordingIdentity = lyricIdentity(track))
                    delay(100)
                }
            }
            KinaraTheme(KinaraThemeKind.DEFAULT, KinaraDarkMode.DARK) {
                CompositionLocalProvider(LocalPlayerProgress provides progress) {
                    PlayerBackdrop(emptyList()) {
                        PlayScreen(PlayerUiState(listOf(track), 0, playing = playing, durationMs = 60000, positionMs = seek),
                            lyrics, {}, {}, {}, { seek = it }, settings = settings,
                            onVisualMode = { settings = settings.copy(playerVisualMode = it) })
                    }
                }
            }
        }
    }
}
