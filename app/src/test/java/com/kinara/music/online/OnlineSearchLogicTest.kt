/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 */
package com.kinara.music.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSearchLogicTest {

    @Test fun `same recording merges by source priority while covers and other versions stay separate`() {
        val song = OnlineTrack("netease", "n", "Song", "Singer", "Album", 200000)
        val qq = song.copy(platform = "qq", id = "q", picUrl = "https://y.gtimg.cn/art.jpg")
        val live = qq.copy(id = "live", title = "Song Live")
        val cover = qq.copy(id = "cover", artist = "Someone else")
        val long = qq.copy(id = "long", durationMs = 230000)
        val results = listOf(OnlineResult("qq", listOf(qq, live, cover, long)), OnlineResult("netease", listOf(song)))
        val state = OnlineSearchState(results = results)
        assertEquals(listOf("n", "live", "cover", "long"), state.displayTracks.map { it.id })
        assertEquals(qq.picUrl, state.displayTracks.first().picUrl)
        assertEquals("q", state.tracksFrom(listOf("qq")).first().id)
    }

    @Test fun `response arrival order never changes platform priority`() {
        var state = OnlineSearchState(keyword = "song")
        val reversed = listOf("other", "gd", "solara", "bilibili", "kuwo", "kugou", "qq", "netease")
        reversed.forEach { state = OnlineSearchState.reduce(state, ok(it, 2), 1, 1) }
        assertEquals(SOURCE_PRIORITY + "other", state.displayTracks.map { it.platform }.distinct())
        assertEquals(listOf("1", "2"), state.displayTracks.filter { it.platform == "qq" }.map { it.id })
    }

    private fun ok(platform: String, n: Int) = OnlineResult(
        platform, (1..n).map { OnlineTrack(platform, "$it", "$platform-t$it", "a", "al") },
    )
    private fun fail(platform: String, msg: String = "boom") = OnlineResult(platform, errorMessage = msg)

    @Test
    fun `late results are dropped by generation`() {
        val s0 = OnlineSearchState(phase = OnlineSearchState.Phase.LOADING, keyword = "x")
        val merged = OnlineSearchState.reduce(s0, ok("netease", 3), generation = 1, currentGeneration = 2)
        assertEquals(s0, merged)
    }

    @Test
    fun `partial failure still shows successful sources`() {
        var s = OnlineSearchState(phase = OnlineSearchState.Phase.LOADING, keyword = "x")
        s = OnlineSearchState.reduce(s, ok("netease", 3), 1, 1)
        s = OnlineSearchState.reduce(s, fail("kuwo"), 1, 1)
        assertTrue(s.searching)
        s = OnlineSearchState.finalize(s)
        org.junit.Assert.assertFalse(s.searching)
        assertEquals(OnlineSearchState.Phase.DATA, s.phase)
        assertEquals(3, s.displayTracks.size)
        assertTrue(s.hasFailure)
    }

    @Test
    fun `all failed with no tracks becomes error`() {
        var s = OnlineSearchState(phase = OnlineSearchState.Phase.LOADING, keyword = "x")
        s = OnlineSearchState.reduce(s, fail("kuwo"), 1, 1)
        s = OnlineSearchState.reduce(s, fail("netease"), 1, 1)
        s = OnlineSearchState.finalize(s)
        assertEquals(OnlineSearchState.Phase.ERROR, s.phase)
    }

    @Test fun `failure-only responses stay loading until all sources are finalized`() {
        var s = OnlineSearchState(phase = OnlineSearchState.Phase.LOADING, keyword = "x")
        s = OnlineSearchState.reduce(s, fail("qq"), 1, 1)
        assertEquals(OnlineSearchState.Phase.LOADING, s.phase)
        assertTrue(s.searching)
    }

    @Test
    fun `no results no failure becomes empty`() {
        var s = OnlineSearchState(phase = OnlineSearchState.Phase.LOADING, keyword = "x")
        s = OnlineSearchState.reduce(s, OnlineResult("kuwo"), 1, 1)
        s = OnlineSearchState.finalize(s)
        assertEquals(OnlineSearchState.Phase.EMPTY, s.phase)
    }

    @Test
    fun `platform results replace not append`() {
        var s = OnlineSearchState(phase = OnlineSearchState.Phase.LOADING, keyword = "x")
        s = OnlineSearchState.reduce(s, ok("netease", 2), 1, 1)
        s = OnlineSearchState.reduce(s, ok("netease", 5), 1, 1)
        assertEquals(5, s.displayTracks.size)
    }

    @Test
    fun `toTrack uses online mediaId namespace`() {
        val t = OnlineTrack("netease", "123", "T", "A", "Al").toTrack("320")
        assertTrue(t.mediaId.startsWith("O"))
        assertEquals("netease", t.onlineKey?.platform)
    }

    @Test
    fun `toTrack drops untrusted artwork URLs`() {
        assertEquals(null, OnlineTrack("netease", "123", "T", "A", "Al", picUrl = "https://example.org/art.jpg")
            .toTrack("320").coverUrl)
    }
}
