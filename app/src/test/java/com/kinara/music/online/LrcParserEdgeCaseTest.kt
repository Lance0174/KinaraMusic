/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.online

import com.kinara.music.data.OnlineKey
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

/**
 * LRC 解析的边界用例 + 歌词缓存不留垃圾。
 *
 * 用 Robolectric 跑：LyricsCache 依赖 org.json，纯 JVM 单测里那是空桩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LrcParserEdgeCaseTest {

    @Test
    fun `multiple leading tags share the trailing text`() {
        val lines = LrcParser.parse("[00:01.00][00:05.00]同一句")
        assertEquals(listOf(1_000L, 5_000L), lines.map { it.timeMs })
        assertTrue(lines.all { it.text == "同一句" })
    }

    @Test
    fun `trailing tag does not swallow the whole line`() {
        // 少数 LRC 把时间标签写到行尾。按"最后一个标签之后"取文本会得到空串，
        // 旧实现于是把整行丢掉，歌词凭空少一句。
        val lines = LrcParser.parse("[00:01.00]这句不能被丢掉[00:02.00]")
        assertEquals(1, lines.size)
        assertEquals("这句不能被丢掉", lines.first().text)
    }

    @Test
    fun `a line that is only tags still yields nothing`() {
        assertTrue(LrcParser.parse("[00:01.00][00:02.00]").isEmpty())
    }

    @Test
    fun `same timestamp with different text keeps both lines`() {
        // 静态审查曾断言 distinct() 会把同时间戳的双语行吃掉。实测不会：
        // LyricLine 是 data class，时间相同但文本不同就不相等，两句都会保留。
        val lines = LrcParser.parse("[00:03.00]原文\n[00:03.00]translation")
        assertEquals(2, lines.size)
        assertEquals(setOf("原文", "translation"), lines.map { it.text }.toSet())
    }

    @Test
    fun `identical duplicates are collapsed`() {
        assertEquals(1, LrcParser.parse("[00:03.00]同一句\n[00:03.00]同一句").size)
    }

    @Test
    fun `write leaves no temp file behind and reads back`() = runTest {
        val dir = Files.createTempDirectory("kinara-lyrics").toFile()
        try {
            val cache = LyricsCache(dir)
            val key = OnlineKey("netease", "42", "320")
            cache.write(key, LyricDocument(listOf(LyricLine(0, "第一句"), LyricLine(1_000, "第二句"))))

            val names = dir.listFiles()?.map { it.name }.orEmpty()
            assertTrue("应当写出 .json：$names", names.any { it.endsWith(".json") })
            // 淘汰逻辑按 extension == "json" 过滤，".json.tmp" 的扩展名是 "tmp"，
            // 一旦残留就永远不会被清掉，所以写完必须不留 .tmp。
            assertTrue("不应残留 .tmp：$names", names.none { it.endsWith(".tmp") })

            val back = cache.read(key)
            assertNotNull(back)
            assertEquals(2, back!!.lines.size)
            assertEquals("第一句", back.lines.first().text)
        } finally {
            dir.deleteRecursively()
        }
    }
}
