/* Kinara UI verification fixture. Excluded from every release build. */
package com.kinara.music.ui

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.kinara.music.data.*
import com.kinara.music.online.*
import com.kinara.music.player.*
import com.kinara.music.ui.theme.*
import kotlinx.coroutines.delay

/** Uses the production screens with original fixture text and offline artwork placeholders. */
class MiuixProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedOrientation = if (intent.getStringExtra("orientation") == "landscape")
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        val songs = List(60) { i -> Track("ui-fixture-$i", listOf("沿着光的方向", "把此刻留在旋律里", "银河与晚风", "未完的来信")[i % 4],
            "Kinara 演示 ${i % 4 + 1}", "夜航 ${i % 3 + 1}", 200_000, "local") }
        setContent {
            var settings by remember { mutableStateOf(KinaraSettings(
                theme = if (intent.getStringExtra("theme") == "industrial") KinaraThemeKind.INDUSTRIAL else KinaraThemeKind.DEFAULT,
                darkMode = if (intent.getStringExtra("theme") == "light") KinaraDarkMode.LIGHT else KinaraDarkMode.DARK,
                lyricRenderer = LyricRenderer.FOLIA_LUMIERE, playerVisualMode = PlayerVisualMode.COVER)) }
            var route by rememberSaveable { mutableStateOf(KinaraNav.entries.firstOrNull { it.name.lowercase() == intent.getStringExtra("page") } ?: KinaraNav.LIBRARY) }
            var query by rememberSaveable { mutableStateOf("") }
            var playerOpen by rememberSaveable { mutableStateOf(intent.getStringExtra("page") == "player") }
            var landscape by rememberSaveable { mutableStateOf(false) }
            var playing by remember { mutableStateOf(false) }
            var seek by remember { mutableLongStateOf(6100) }
            val progress = remember { mutableStateOf(PlayerProgress()) }
            LaunchedEffect(playing, seek) {
                val started = SystemClock.elapsedRealtime()
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    progress.value = PlayerProgress((seek + if (playing) now - started else 0) % 200_000,
                        200_000, now, playing, mediaId = songs.first().mediaId, recordingIdentity = lyricIdentity(songs.first()))
                    delay(100)
                }
            }
            val player = PlayerUiState(songs, 0, playing = playing, durationMs = 200_000, positionMs = 6100)
            KinaraTheme(settings.theme, settings.darkMode, surfaceStyle = settings.surfaceStyle) {
                CompositionLocalProvider(LocalPlayerProgress provides progress) {
                LandscapeImmersive(landscape)
                if (playerOpen) PlayScreen(player, listOf(LyricLine(0,"沿着光的方向"), LyricLine(5000,"把此刻留在旋律里"), LyricLine(10000,"让每一个字都有回响")),
                    { playing = !playing }, {}, {}, { seek = it }, onClose = { playerOpen = false }, settings = settings,
                    onVisualMode = { settings = settings.copy(playerVisualMode = it) }, lockedLandscape = landscape,
                    onEnterLandscape = { landscape = true }, onExitLandscape = { landscape = false })
                else KinaraShell(route, { route = it }, player, { playing = !playing }, {}, { playerOpen = true }) {
                    Column(Modifier.fillMaxSize()) {
                        PageTitle(route.label, "你的音乐，始终在这里")
                        when (route) {
                            KinaraNav.LIBRARY -> LibraryScreen(LibraryUiState.Ready(songs), player, query, { query = it }, filterTracks(songs, query),
                                { _, _ -> playing = true }, {}, onOpenPlayer = { playerOpen = true })
                            KinaraNav.SETTINGS -> SettingsScreen(settings, { settings = settings.copy(theme = it) },
                                { settings = settings.copy(darkMode = KinaraDarkMode.valueOf(it)) }, { _, _ -> },
                                onPreferences = { settings = it(settings) }, onPreviewLyrics = { settings = settings.copy(playerVisualMode = PlayerVisualMode.LYRICS); playerOpen = true })
                            KinaraNav.PLAYLISTS -> PlaylistScreen(listOf(Playlist("liked", "我喜欢的音乐", 0, songs.take(12)),
                                Playlist("night", "晚风与夜航", 0, songs.take(20)), Playlist("walk", "散步时听", 0, songs.take(8))),
                                null, { _, _ -> }, {}, { _, _ -> }, {}, { _, _ -> })
                            KinaraNav.SEARCH -> {
                                SearchField(query, { query = it }, "歌曲、歌手、专辑")
                                OnlineSearchScreen(OnlineSearchState(OnlineSearchState.Phase.DATA,
                                    listOf(OnlineResult("netease", songs.take(8).mapIndexed { i,t -> OnlineTrack("netease", "fixture-$i", t.title, t.artist, t.album) })), "光"),
                                    { _, _ -> }, {})
                            }
                            KinaraNav.DOWNLOADS -> EmptyState(Glyph.DOWNLOAD, "喜欢的音乐，随身携带", "下载后，离线也能听见。")
                        }
                    }
                }
                }
            }
        }
    }
}
