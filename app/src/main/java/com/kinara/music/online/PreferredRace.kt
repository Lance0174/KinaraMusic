/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.online

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Run a bounded list together. A ready lower priority result waits only briefly for better ones. */
internal suspend fun <T : Any> preferredRace(
    tasks: List<suspend () -> T?>, preferenceMs: Long = 200,
): T? = coroutineScope {
    if (tasks.isEmpty()) return@coroutineScope null
    val results = Channel<Pair<Int, T?>>(Channel.UNLIMITED)
    val pending = tasks.indices.toMutableSet()
    val jobs = tasks.mapIndexed { index, task -> launch {
        val value = try { task() } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            null
        }
        results.send(index to value)
    } }
    var best: Pair<Int, T>? = null
    suspend fun receive() {
        val (index, value) = results.receive()
        pending.remove(index)
        if (value != null && (best == null || index < best!!.first)) best = index to value
    }
    try {
        while (best == null && pending.isNotEmpty()) receive()
        if (best != null) withTimeoutOrNull(preferenceMs) {
            while (pending.any { it < best!!.first }) receive()
        }
        best?.second
    } finally {
        jobs.forEach { it.cancel() }
        results.cancel()
    }
}
