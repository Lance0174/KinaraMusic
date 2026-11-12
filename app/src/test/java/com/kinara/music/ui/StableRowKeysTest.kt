/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 队列列表的稳定 key。
 *
 * 播放队列原先用 "下标:mediaId" 当 key。下标进了 key，任何一次重排都会让每一行的身份变化，
 * 拖动手势会整表重建、动画与点击跟着错位。这里锁住两个不变量：
 * key 与位置无关（同一条目重排后 key 不变），且队列里出现重复曲目时 key 仍然唯一。
 */
class StableRowKeysTest {

    @Test
    fun `keys are unique for the same track repeated in a queue`() {
        val keys = stableRowKeys(listOf("a", "b", "a", "a", "b"))
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `a row keeps its key after being moved`() {
        val before = stableRowKeys(listOf("a", "b", "c"))
        val after = stableRowKeys(listOf("b", "c", "a"))
        // "a" 从第 0 位挪到第 2 位，key 必须还是同一个，否则动画会当成"旧行消失 + 新行出现"。
        assertEquals(before[0], after[2])
        assertEquals(before[1], after[0])
        assertEquals(before[2], after[1])
    }

    @Test
    fun `duplicates are told apart in order of appearance`() {
        val keys = stableRowKeys(listOf("a", "a"))
        assertNotEquals(keys[0], keys[1])
    }

    @Test
    fun `empty queue yields no keys`() {
        assertEquals(emptyList<String>(), stableRowKeys(emptyList()))
    }
}
