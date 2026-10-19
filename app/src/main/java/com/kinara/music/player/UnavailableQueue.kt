/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.player

/** A failed queue entry is visited at most once until playback succeeds or the user selects again. */
internal class UnavailableQueue {
    private val failed = mutableSetOf<String>()
    fun reset() = failed.clear()

    fun next(current: Int, size: Int, idAt: (Int) -> String, following: (Int) -> Int): Int? {
        if (current !in 0 until size) return null
        failed += idAt(current)
        var cursor = current
        repeat(size) {
            cursor = following(cursor)
            if (cursor !in 0 until size) return null
            if (idAt(cursor) !in failed) return cursor
        }
        return null
    }
}
