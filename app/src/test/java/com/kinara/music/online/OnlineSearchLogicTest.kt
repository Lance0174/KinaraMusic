/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 */
package com.kinara.music.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSearchLogicTest {

    @Test fun `response arrival order never changes platform priority`() {
        var state = OnlineSearchState(keyword = "song")
        val reversed = listOf("other", "gd", "solara", "bilibili", "kuwo", "kugou", "qq", "netease")
        reversed.forEach { state = OnlineSearchState.reduce(state, ok(it, 2), 1, 1) }
        assertEquals(SOURCE_PRIORITY + "other", state.displayTracks.map { it.platform }.distinct())
        assertEquals(listOf("1", "2"), state.displayTracks.filter { it.platform == "qq" }.map { it.id })
    }

    @Test fun `failed selected platform shows next available result and gd remains selectable`() {
        val state = OnlineSearchState(results = listOf(fail("qq"), fail("kugou"), ok("kuwo", 2), ok("gd", 1)))
        assertEquals("kuwo", state.displayPlatform("qq", SOURCE_PRIORITY))
        assertEquals("gd", state.displayPlatform("qq", listOf("qq", "gd")))
        assertEquals("all", state.displayPlatform("all", SOURCE_PRIORITY))
        assertEquals("qq", OnlineSearchState(results = listOf(ok("qq", 0))).displayPlatform("qq", SOURCE_PRIORITY))
    }

    private fun ok(platform: String, n: Int) = OnlineResult(
        platform, (1..n).map { OnlineTrack(platform, "$it", "t$it", "a", "al") },
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
}
