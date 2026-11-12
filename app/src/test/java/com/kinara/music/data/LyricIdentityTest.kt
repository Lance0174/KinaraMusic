/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 歌词校准身份键的回归测试。
 *
 * 背景：一次静态审查断言 `PlayScreen` 里的 `lyricIdentity(actual)!!` 会在本地歌曲上抛 NPE，
 * 理由是"本地曲目没有 onlineKey"。这条断言是**错的** —— 该函数对本地曲目有
 * `local:$mediaId` 的兜底，只要 Track 非空就永远返回非空。这里用测试把这个不变量钉住，
 * 以后若有人删掉兜底分支，会立刻被测试发现，而不是等到线上崩溃。
 */
class LyricIdentityTest {

    private fun track(mediaId: String, source: String, onlineKey: OnlineKey?) = Track(
        mediaId = mediaId, title = "标题", artist = "艺术家", album = "专辑",
        durationMs = 1000, source = source, onlineKey = onlineKey,
        localPath = if (onlineKey == null) "/storage/music/a.mp3" else null,
    )

    @Test
    fun `local track still resolves an identity`() {
        val local = track("L42", "local", null)
        assertEquals("local:L42", lyricIdentity(local))
    }

    @Test
    fun `online identity ignores bitrate so calibrations follow the recording`() {
        val low = track("N1", "netease", OnlineKey("netease", "99", "128"))
        val high = track("N1", "netease", OnlineKey("netease", "99", "flac"))
        assertEquals("netease:99", lyricIdentity(low))
        assertEquals(lyricIdentity(low), lyricIdentity(high))
    }

    @Test
    fun `identity is null only when there is no track at all`() {
        assertNull(lyricIdentity(null))
    }
}
