/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 */
package com.kinara.music.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parses standard line`() {
        val lines = LrcParser.parse("[00:12.34]hello world")
        assertEquals(1, lines.size)
        assertEquals(12340L, lines[0].timeMs)
        assertEquals("hello world", lines[0].text)
    }

    @Test
    fun `expands multiple timestamps`() {
        val lines = LrcParser.parse("[00:01.00][01:02.00]chorus")
        assertEquals(2, lines.size)
        assertEquals(1000L, lines[0].timeMs)
        assertEquals(62000L, lines[1].timeMs)
        assertEquals("chorus", lines[1].text)
    }

    @Test
    fun `skips metadata and empty text`() {
        val lines = LrcParser.parse("[ti:title]\n[00:05.00]\n[00:06.00]real")
        assertEquals(1, lines.size)
        assertEquals("real", lines[0].text)
    }

    @Test
    fun `sorted by time`() {
        val lines = LrcParser.parse("[00:10.00]b\n[00:02.00]a\n[00:05.00]c")
        assertEquals(listOf("a", "c", "b"), lines.map { it.text })
    }

    @Test
    fun `currentIndex binary search`() {
        val lines = LrcParser.parse("[00:10.00]a\n[00:20.00]b\n[00:30.00]c")
        assertEquals(-1, LrcParser.currentIndex(lines, 5000))
        assertEquals(0, LrcParser.currentIndex(lines, 10000))
        assertEquals(0, LrcParser.currentIndex(lines, 19999))
        assertEquals(1, LrcParser.currentIndex(lines, 20000))
        assertEquals(2, LrcParser.currentIndex(lines, 999999))
    }

    @Test
    fun `worker client only exposes configured https bases`() {
        assertTrue(BiliWorkerClient.BASES.all { it.startsWith("https://") })
        assertEquals(BiliWorkerClient.BASES.distinct(), BiliWorkerClient.BASES)
    }
}
