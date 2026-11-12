/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 歌单文件是用户自选的外部输入，属于不可信数据。
 *
 * 背景：`PortablePlaylist.decode` 只限制了文件长度（5 MB），随后直接交给 org.json 的
 * `nextValue()`。该实现对数组/对象是递归展开的，一个全是 `[` 的文件会抛 StackOverflowError。
 * 由于 StackOverflowError 是 Error 而非 Exception，调用处的 `catch (e: Exception)` 兜不住，
 * 会直接把进程带走。现在解析前先用 [jsonNestingDepth] 做一次线性深度扫描。
 *
 * 用 Robolectric 跑：纯 JVM 单测里 org.json 是 android.jar 的空桩，
 * `nextValue()` 会直接抛 "not mocked"，只有 Robolectric 才提供真实现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortablePlaylistDepthTest {

    @Test
    fun `counts bracket nesting depth`() {
        assertEquals(1, jsonNestingDepth("""{"a":1}"""))
        assertEquals(3, jsonNestingDepth("[[[]]]"))
        assertEquals(3, jsonNestingDepth("""[{"a":[1]}]"""))
    }

    @Test
    fun `does not count brackets inside string literals`() {
        assertEquals(1, jsonNestingDepth("""{"a":"[[[[[[[[[[[["}"""))
        // 转义引号不应提前结束字符串
        assertEquals(1, jsonNestingDepth("""{"a":"say \"[[[[\" ok"}"""))
    }

    @Test
    fun `deeply nested input is rejected instead of overflowing the stack`() {
        val bomb = "[".repeat(5_000)
        val error = runCatching { PortablePlaylist.decode(bomb) }.exceptionOrNull()
        assertTrue("期望 IllegalArgumentException，实际 ${error?.javaClass}", error is IllegalArgumentException)
    }

    @Test
    fun `a normal playlist still decodes`() {
        val json = """{"meta":{"name":"我的歌单"},"items":[
            {"source":"netease","id":"123","name":"歌名","artist":"歌手","duration":200000}
        ]}"""
        val (name, tracks) = PortablePlaylist.decode(json)
        assertEquals("我的歌单", name)
        assertEquals(1, tracks.size)
        assertEquals("netease:123", tracks.first().onlineKey?.let { "${it.platform}:${it.id}" })
    }

    @Test
    fun `unknown platform rows are dropped rather than trusted`() {
        val json = """{"items":[
            {"source":"evil","id":"1","name":"x"},
            {"source":"netease","id":"2","name":"y"}
        ]}"""
        val (_, tracks) = PortablePlaylist.decode(json)
        assertEquals(1, tracks.size)
        assertEquals("2", tracks.first().onlineKey?.id)
    }
}
