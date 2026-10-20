package com.kinara.music.player

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackServiceRecoveryTest {
    @Test fun realLoaderFailuresAdvanceAndStopEvenInRepeatOne() = verifyQueue(online = false)

    @Test fun disabledOnlineSourcesAdvanceWithoutRetryingTheSameFailure() = verifyQueue(online = true)

    private fun verifyQueue(online: Boolean) {
        val service = Robolectric.buildService(PlaybackService::class.java).create()
        val session = PlaybackService::class.java.getDeclaredField("mediaSession").apply { isAccessible = true }
            .get(service.get()) as MediaSession
        val player = session.player
        var errors = 0
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { errors++ }
        })
        try {
            if (online) com.kinara.music.data.KinaraPrefs.get(service.get()).save(com.kinara.music.data.KinaraSettings(
                sources = com.kinara.music.data.SourceToggles(netease = false, qq = false, kugou = false,
                    kuwo = false, bilibili = false, solara = false, gdFallback = false)))
            player.setMediaItems((0..1).map { MediaItem.Builder().setMediaId("missing-$it")
                .setUri(if (online) android.net.Uri.parse(com.kinara.music.online.OnlineMediaUri.encode(
                    com.kinara.music.data.Track("missing-$it", "Song $it", "Artist", "Album", 1000, "netease",
                        onlineKey = com.kinara.music.data.OnlineKey("netease", "$it"))))
                    else android.net.Uri.fromFile(java.io.File(service.get().cacheDir, "missing-$it.mp3"))).build() })
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.prepare(); player.play()
            val expectedErrors = 2
            val deadline = System.nanoTime() + 10_000_000_000
            while ((errors < expectedErrors || player.playWhenReady) && System.nanoTime() < deadline) {
                shadowOf((player as androidx.media3.exoplayer.ExoPlayer).playbackLooper).idle()
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
                Thread.sleep(10)
            }
            assertEquals(PlaybackDiagnostics.snapshot(), expectedErrors, errors)
            assertEquals(1, player.currentMediaItemIndex)
            assertFalse(player.playWhenReady)
        } finally { service.destroy() }
    }
}
