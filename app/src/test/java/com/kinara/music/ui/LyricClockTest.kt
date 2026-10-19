package com.kinara.music.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.os.SystemClock
import com.kinara.music.data.Track
import com.kinara.music.player.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34])
class LyricClockTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun lyricsAdvanceBetweenPollsThenFreezeOnPauseAndRejectPreviousTrackSamples() {
        compose.mainClock.autoAdvance = false
        ShadowSystemClock.advanceBy(Duration.ofMillis(1000))
        val track = Track("current", "song", "artist", "album", 10000, "local")
        var player by mutableStateOf(PlayerUiState(listOf(track), 0, playing = true))
        val progress = mutableStateOf(PlayerProgress(1000, 10000, SystemClock.elapsedRealtime(), true, mediaId = track.mediaId))
        var clock: State<Long>? = null
        compose.setContent { CompositionLocalProvider(LocalPlayerProgress provides progress) { clock = rememberLyricClock(player) } }
        compose.mainClock.advanceTimeBy(16)
        ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { assertTrue("must cross the lyric boundary before the 500ms poll", clock!!.value >= 1300) }
        compose.runOnIdle {
            player = player.copy(playing = false)
            progress.value = progress.value.copy(positionMs = 1400, advancing = false)
        }
        compose.mainClock.advanceTimeBy(32)
        ShadowSystemClock.advanceBy(Duration.ofMillis(1000))
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { assertEquals(1400L, clock!!.value) }
        compose.runOnIdle { progress.value = progress.value.copy(mediaId = "previous", positionMs = 7000) }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { assertEquals(0L, clock!!.value) }
    }
}
