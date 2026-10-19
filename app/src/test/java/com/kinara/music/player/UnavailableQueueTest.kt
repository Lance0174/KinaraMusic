package com.kinara.music.player

import org.junit.Assert.*
import org.junit.Test

class UnavailableQueueTest {
    @Test fun allUnavailableLoopAndSingleRepeatTerminateAfterOnePass() {
        for (size in 1..4) {
            val queue = UnavailableQueue()
            var cursor = 0
            repeat(size - 1) {
                cursor = queue.next(cursor, size, { "song-$it" }, { (it + 1) % size })!!
            }
            assertNull(queue.next(cursor, size, { "song-$it" }, { (it + 1) % size }))
        }
    }

    @Test fun skipsDuplicatesRespectsShuffleAndManualSelectionAllowsRetry() {
        val ids = listOf("bad", "bad", "good", "third")
        val order = listOf(0, 3, 1, 2)
        val queue = UnavailableQueue()
        fun next(index: Int) = queue.next(index, ids.size, ids::get, { order[(order.indexOf(it) + 1) % order.size] })
        assertEquals(3, next(0)); assertEquals(2, next(3)); assertNull(next(2))
        queue.reset()
        assertEquals(0, next(2))
    }

    @Test fun sequentialEndAndEmptyQueueStop() {
        val queue = UnavailableQueue()
        assertNull(queue.next(0, 0, { error("empty") }, { error("empty") }))
        assertNull(queue.next(2, 3, { "$it" }, { -1 }))
    }
}
