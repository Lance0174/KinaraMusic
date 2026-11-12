/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.player

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 队列操作对真实 player 的影响。
 *
 * 「队列可调顺序」和「下一首播放」完全建立在 Media3 的 moveMediaItem / addMediaItem 上，
 * 而它们的语义（`to` 是移动后的最终下标？当前曲目身份会不会丢？）此前只是照着别的项目推测的。
 * 这里用真实 ExoPlayer 把结论钉住，顺便证明会话播放器确实拿到了 COMMAND_CHANGE_MEDIA_ITEMS
 * ——否则 moveInQueue 的守卫会把所有重排静默吞掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class QueueOperationsTest {

    private lateinit var controller: org.robolectric.android.controller.ServiceController<PlaybackService>
    private lateinit var player: Player

    @Before
    fun setUp() {
        controller = Robolectric.buildService(PlaybackService::class.java).create()
        val session = PlaybackService::class.java.getDeclaredField("mediaSession").apply { isAccessible = true }
            .get(controller.get()) as MediaSession
        player = session.player
        player.setMediaItems(listOf("a", "b", "c").map {
            MediaItem.Builder().setMediaId(it).setUri("https://example.invalid/$it.mp3").build()
        }, 0, 0)
    }

    @After
    fun tearDown() { controller.destroy() }

    private fun ids() = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    @Test
    fun `reordering keeps the current track playing`() {
        val before = player.currentMediaItem?.mediaId
        player.moveMediaItem(2, 1)
        assertEquals(listOf("a", "c", "b"), ids())
        assertEquals("重排不能改变正在播放的那一首", before, player.currentMediaItem?.mediaId)
        assertEquals(0, player.currentMediaItemIndex)
    }

    @Test
    fun `moving the current item carries the index with it`() {
        player.seekTo(1, 0)
        assertEquals("b", player.currentMediaItem?.mediaId)
        player.moveMediaItem(1, 2)
        assertEquals(listOf("a", "c", "b"), ids())
        assertEquals("身份不变，下标跟着走", "b", player.currentMediaItem?.mediaId)
        assertEquals(2, player.currentMediaItemIndex)
    }

    @Test
    fun `moveMediaItem is actually permitted on the session player`() {
        // moveInQueue 的前置守卫就是这个；若不成立，UI 上所有排序都会静默失效。
        assertTrue(player.availableCommands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
    }

    @Test
    fun `inserting next makes the new item the following one`() {
        player.seekTo(0, 0)
        player.addMediaItem(1, MediaItem.Builder().setMediaId("next").setUri("https://example.invalid/n.mp3").build())
        assertEquals(listOf("a", "next", "b", "c"), ids())
        assertEquals("next", player.getMediaItemAt(player.currentMediaItemIndex + 1).mediaId)
    }

    @Test
    fun `removing an item leaves the current track alone`() {
        player.seekTo(1, 0)
        player.removeMediaItem(0)
        assertEquals(listOf("b", "c"), ids())
        assertEquals("b", player.currentMediaItem?.mediaId)
        assertEquals(0, player.currentMediaItemIndex)
    }
}
