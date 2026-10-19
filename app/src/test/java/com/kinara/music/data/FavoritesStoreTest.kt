/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 */
package com.kinara.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesStoreTest {

    private fun localTrack(id: String, title: String = "t") = Track(
        mediaId = id, title = title, artist = "a", album = "al",
        durationMs = 1000, source = "local", localPath = "/sdcard/$id.mp3",
    )

    private fun onlineTrack(id: String) = Track(
        mediaId = id, title = "o", artist = "b", album = "bl",
        durationMs = 2000, source = "kugou",
        onlineKey = OnlineKey(platform = "kugou", id = "HASH123", bitrate = "320"),
    )

    @Test
    fun `roundtrip keeps local and online fields`() {
        val src = listOf(localTrack("L1", "本地一"), onlineTrack("O1"))
        val back = decodeFavorites(encodeFavorites(src))
        assertEquals(src.size, back.size)
        assertEquals("本地一", back[0].title)
        assertEquals("/sdcard/L1.mp3", back[0].localPath)
        assertNull(back[1].localPath)
        assertEquals("kugou", back[1].onlineKey?.platform)
        assertEquals("HASH123", back[1].onlineKey?.id)
    }

    @Test
    fun `toggle adds to front and removes on second call`() {
        val t = localTrack("L1")
        val added = toggleFavorite(emptyList(), t)
        assertEquals(1, added.size)
        val removed = toggleFavorite(added, t)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun `toggle keeps newest first`() {
        val a = localTrack("A"); val b = localTrack("B")
        val list = toggleFavorite(toggleFavorite(emptyList(), a), b)
        assertEquals("B", list.first().mediaId)
    }

    @Test
    fun `corrupted json yields empty list not crash`() {
        assertEquals(0, decodeFavorites("{not json").size)
        assertEquals(0, decodeFavorites(null).size)
        assertEquals(0, decodeFavorites("").size)
    }

    @Test
    fun `lines without mediaId are skipped`() {
        // 首行为空 mediaId（应跳过），次行为正常条目（应保留）
        val text = encodeFavorites(listOf(localTrack("L9"))) + "\n" + "\u001F坏行"
        val out = decodeFavorites(text)
        assertEquals(1, out.size)
        assertEquals("L9", out[0].mediaId)
    }

    @Test
    fun `field escaping survives separators and newlines`() {
        val tricky = localTrack("L7", "标题\u001F带分隔符\n带换行")
        val back = decodeFavorites(encodeFavorites(listOf(tricky)))
        assertEquals("标题\u001F带分隔符\n带换行", back[0].title)
    }
}
