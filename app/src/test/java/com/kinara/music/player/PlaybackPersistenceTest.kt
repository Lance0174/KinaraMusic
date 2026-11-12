/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.player

import com.kinara.music.data.OnlineKey
import com.kinara.music.data.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.content.Context

/**
 * "重进 App 继续上次播放"的落盘层。
 *
 * 锁住的是三件容易悄悄坏掉的事：往返不丢字段、单行损坏不让整条队列消失、
 * 下标与进度做了边界收敛（脏数据不能让恢复时越界崩溃）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackPersistenceTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun onlineTrack(id: String) = Track(
        mediaId = "netease:$id", title = "歌 $id", artist = "歌手$id", album = "专辑",
        durationMs = 200_000, source = "netease", onlineKey = OnlineKey("netease", id, "320"),
    )

    private fun localTrack(id: String) = Track(
        mediaId = "local:$id", title = "本地 $id", artist = "本地歌手", album = "本地专辑",
        durationMs = 123_000, source = "local", localPath = "/storage/emulated/0/Music/$id.mp3",
    )

    @Test
    fun `save then load round trips the queue`() {
        PlaybackPersistence.clear(context)
        val tracks = listOf(onlineTrack("1"), localTrack("2"), onlineTrack("3"))
        PlaybackPersistence.save(context, tracks, 1, 45_000)

        val saved = PlaybackPersistence.load(context)
        assertNotNull(saved)
        assertEquals(3, saved!!.tracks.size)
        assertEquals(tracks.map { it.mediaId }, saved.tracks.map { it.mediaId })
        assertEquals(1, saved.index)
        assertEquals(45_000, saved.positionMs)
        // 在线曲目的标识与码率必须完整保留，否则恢复后无从重新解析。
        assertEquals("netease", saved.tracks[0].onlineKey?.platform)
        assertEquals("320", saved.tracks[0].onlineKey?.bitrate)
        assertEquals("/storage/emulated/0/Music/2.mp3", saved.tracks[1].localPath)
    }

    @Test
    fun `load returns null when nothing was saved`() {
        PlaybackPersistence.clear(context)
        assertNull(PlaybackPersistence.load(context))
    }

    @Test
    fun `a corrupted line does not discard the whole queue`() {
        PlaybackPersistence.clear(context)
        PlaybackPersistence.save(context, listOf(onlineTrack("1"), onlineTrack("2")), 0, 0)
        // 手动把其中一行改坏：宽容解析应当只丢这一行。
        val prefs = context.getSharedPreferences("kinara_playback_state", Context.MODE_PRIVATE)
        prefs.edit().putString("queue", "\u0000broken\n" + prefs.getString("queue", "")).apply()

        val saved = PlaybackPersistence.load(context)
        assertNotNull(saved)
        assertTrue("坏行应被丢弃，好行仍保留", saved!!.tracks.size >= 2)
    }

    @Test
    fun `index and position are clamped`() {
        PlaybackPersistence.clear(context)
        PlaybackPersistence.save(context, listOf(onlineTrack("1"), onlineTrack("2")), 0, 1000)
        val prefs = context.getSharedPreferences("kinara_playback_state", Context.MODE_PRIVATE)
        prefs.edit().putInt("index", 99).putLong("position", -5).apply()

        val saved = PlaybackPersistence.load(context)
        assertNotNull(saved)
        assertEquals("越界下标必须收敛到最后一项", 1, saved!!.index)
        assertEquals("负数进度必须收敛到 0", 0, saved.positionMs)
    }

    @Test
    fun `clear removes the state so a fresh start has no ghost queue`() {
        PlaybackPersistence.save(context, listOf(onlineTrack("1")), 0, 10)
        assertNotNull(PlaybackPersistence.load(context))
        PlaybackPersistence.clear(context)
        assertNull(PlaybackPersistence.load(context))
    }

    @Test
    fun `empty queue is never persisted`() {
        PlaybackPersistence.clear(context)
        PlaybackPersistence.save(context, emptyList(), 0, 0)
        assertNull(PlaybackPersistence.load(context))
    }
}
