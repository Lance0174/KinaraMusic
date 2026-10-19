/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 */
package com.kinara.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistStoreTest {

    private fun local(id: String, title: String = "t") = Track(
        mediaId = id, title = title, artist = "a", album = "b",
        durationMs = 1000, source = "local", localPath = "/x/$id.mp3",
    )

    private fun online(id: String) = Track(
        mediaId = id, title = "o", artist = "c", album = "d", durationMs = 2000,
        source = "netease", onlineKey = OnlineKey("netease", "N123", "flac"),
    )

    @Test
    fun `create trims and falls back for blank name`() {
        val a = createPlaylist(emptyList(), "   ", 1L, "pl1")
        assertEquals("未命名歌单", a[0].name)
        val b = createPlaylist(emptyList(), "  摇滚  ", 2L, "pl2")
        assertEquals("摇滚", b[0].name)
    }

    @Test
    fun `rename and delete work by id`() {
        var list = createPlaylist(emptyList(), "A", 1L, "pl1")
        list = createPlaylist(list, "B", 2L, "pl2")
        list = renamePlaylist(list, "pl1", "A2")
        assertEquals("A2", list.first { it.id == "pl1" }.name)
        list = deletePlaylist(list, "pl2")
        assertEquals(1, list.size)
        assertNull(list.firstOrNull { it.id == "pl2" })
    }

    @Test
    fun `add tracks dedupes by mediaId`() {
        var list = createPlaylist(emptyList(), "P", 1L, "pl1")
        list = addTracksToPlaylist(list, "pl1", listOf(local("L1"), local("L2")))
        list = addTracksToPlaylist(list, "pl1", listOf(local("L2"), local("L3")))
        assertEquals(3, list[0].tracks.size)
        assertEquals(listOf("L1", "L2", "L3"), list[0].tracks.map { it.mediaId })
    }

    @Test
    fun `remove track only affects target playlist`() {
        var list = createPlaylist(emptyList(), "P1", 1L, "pl1")
        list = createPlaylist(list, "P2", 2L, "pl2")
        list = addTracksToPlaylist(list, "pl1", listOf(local("L1")))
        list = addTracksToPlaylist(list, "pl2", listOf(local("L1")))
        list = removeTrackFromPlaylist(list, "pl1", "L1")
        assertTrue(list.first { it.id == "pl1" }.tracks.isEmpty())
        assertEquals(1, list.first { it.id == "pl2" }.tracks.size)
    }

    @Test
    fun `roundtrip preserves playlists and online keys`() {
        var list = createPlaylist(emptyList(), "混合", 100L, "pl1")
        list = addTracksToPlaylist(list, "pl1", listOf(local("L1"), online("O1")))
        val back = decodePlaylists(encodePlaylists(list))
        assertEquals(1, back.size)
        assertEquals("混合", back[0].name)
        assertEquals(100L, back[0].createdAt)
        assertEquals(2, back[0].tracks.size)
        assertEquals("netease", back[0].tracks[1].onlineKey?.platform)
        assertEquals("N123", back[0].tracks[1].onlineKey?.id)
        assertNull(back[0].tracks[1].localPath)
    }

    @Test
    fun `corrupted text yields empty list and bad lines skipped`() {
        assertEquals(0, decodePlaylists(null).size)
        assertEquals(0, decodePlaylists("").size)
        // 第二行字段不足应被跳过
        val text = encodePlaylists(createPlaylist(emptyList(), "OK", 1L, "pl1")) + "\n" + "坏行"
        assertEquals(1, decodePlaylists(text).size)
    }

    @Test
    fun `names with separators survive roundtrip`() {
        val tricky = createPlaylist(emptyList(), "名\u001F带分隔\n换行", 1L, "pl1")
        val back = decodePlaylists(encodePlaylists(tricky))
        assertEquals("名\u001F带分隔\n换行", back[0].name)
    }
}
