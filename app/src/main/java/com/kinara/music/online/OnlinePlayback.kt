/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.online

import com.kinara.music.data.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** Owned by one selection. Cancelled race losers remain eligible if the selected stream later fails. */
class PlaybackAttempts {
    internal data class Ready(val at: Long, val value: Pair<ResolvedUrl, Track>)
    internal val tried = ConcurrentHashMap.newKeySet<OnlineKey>()
    internal val candidates = ConcurrentHashMap<String, List<OnlineTrack>>()
    internal val exhausted = ConcurrentHashMap.newKeySet<String>()
    internal val ready = ConcurrentHashMap<OnlineKey, Ready>()
}

class OnlinePlayback(private val repo: PlaybackAccess,
    private val verify: suspend (ResolvedUrl) -> ResolvedUrl? = { it }) {
    suspend fun resolveForPlay(track: Track, settings: KinaraSettings): Pair<ResolvedUrl, Track> {
        val key = track.onlineKey ?: return ResolvedUrl(ResolveStage.NOT_CONFIGURED, errorMessage = "非在线曲目") to track
        return resolve(key, settings) to track
    }

    /** All enabled platforms search/resolve together; their timeouts never accumulate. */
    suspend fun resolveNextForPlay(original: Track, failed: Track, settings: KinaraSettings,
        attempts: PlaybackAttempts): Pair<ResolvedUrl, Track>? {
        val key = failed.onlineKey ?: return null
        original.onlineKey?.let { attempts.tried += it }
        attempts.tried += key
        attempts.ready.keys.removeAll(attempts.tried)
        val platforms = settings.sources.enabledInOrder().filter {
            it != original.onlineKey?.platform || (it == "bilibili" && key.platform == it && key.bitrate != "128")
        }.filter { it !in attempts.exhausted }
        val winner = preferredRace(platforms.map { platform -> suspend {
            val next = try { withTimeoutOrNull(SOURCE_BUDGET_MS) {
                suspend fun candidate(track: Track): Pair<ResolvedUrl, Track>? {
                    val candidateKey = track.onlineKey!!
                    if (candidateKey in attempts.tried) return null
                    attempts.ready[candidateKey]?.takeIf { System.nanoTime() - it.at < 90_000_000_000L }?.let { return it.value }
                    val resolved = resolve(candidateKey, settings)
                    val checked = if (resolved.stage == ResolveStage.OK) verify(resolved) else null
                    if (checked != null) return (checked to track).also {
                        attempts.ready[candidateKey] = PlaybackAttempts.Ready(System.nanoTime(), it)
                    }
                    attempts.tried += candidateKey
                    return null
                }
                // This alternative runs beside the other sources, never ahead of all of them.
                if (platform == "bilibili" && key.platform == platform && key.bitrate != "128") {
                    candidate(failed.copy(onlineKey = key.copy(bitrate = "128")))?.let { return@withTimeoutOrNull it }
                }
                if (platform == original.onlineKey?.platform) return@withTimeoutOrNull null
                val found = attempts.candidates[platform] ?: repo.searchSource(platform,
                    "${original.title} ${original.artist}", settings).tracks
                    .filter { it.platform == platform && sameRecording(original, it) }
                    .distinctBy { it.id }.take(3).also { attempts.candidates[platform] = it }
                for (item in found) {
                    candidate(item.toTrack(original.onlineKey?.bitrate ?: settings.preferredBitrate))
                        ?.let { return@withTimeoutOrNull it }
                }
                null
            } } catch (e: Exception) { currentCoroutineContext().ensureActive(); null }
            if (next == null) attempts.exhausted += platform
            next
        } })
        winner?.second?.onlineKey?.let { attempts.tried += it; attempts.ready.remove(it) }
        return winner
    }

    private suspend fun resolve(key: OnlineKey, settings: KinaraSettings): ResolvedUrl {
        if (!settings.sources.isEnabled(key.platform)) return ResolvedUrl(ResolveStage.NOT_CONFIGURED, errorMessage = "该音源已关闭")
        return try {
            withTimeoutOrNull(RESOLVE_BUDGET_MS) { repo.resolveUrl(key, settings) }
                ?: ResolvedUrl(ResolveStage.TRANSPORT, errorMessage = "获取音频超时")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ResolvedUrl(ResolveStage.TRANSPORT, errorMessage = "获取音频失败，请检查网络")
        }
    }

    suspend fun lyricFor(track: Track, settings: KinaraSettings): String? =
        track.onlineKey?.let { (repo as? OnlineRepository)?.lyric(it, settings) }

    companion object {
        internal const val SOURCE_BUDGET_MS = 6000L
        internal const val RESOLVE_BUDGET_MS = 4000L
    }
}
