/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.online

import com.kinara.music.data.*
import kotlinx.coroutines.*

class OnlinePlayback(private val repo: PlaybackAccess) {
    suspend fun resolveForPlay(track: Track, settings: KinaraSettings): Pair<ResolvedUrl, Track> {
        val key = track.onlineKey ?: return ResolvedUrl(ResolveStage.NOT_CONFIGURED, errorMessage = "非在线曲目") to track
        return resolve(key, settings) to track
    }

    /** Called after a failed stream is closed; never splice another recording into a byte range. */
    suspend fun resolveNextForPlay(original: Track, failed: Track, settings: KinaraSettings): Pair<ResolvedUrl, Track>? {
        val key = failed.onlineKey ?: return null
        // Different B站 qualities may use different CDNs. Restart with a separate cache key.
        if (key.platform == "bilibili" && key.bitrate != "128" && settings.sources.bilibili) {
            val lower = failed.copy(onlineKey = key.copy(bitrate = "128"))
            val result = resolve(lower.onlineKey!!, settings)
            if (result.stage == ResolveStage.OK) return result to lower
        }
        for (platform in fallbackPlatforms(key.platform, settings.sources)) {
            val next = try { withTimeoutOrNull(12_000) {
                val found = repo.searchSource(platform, "${original.title} ${original.artist}", settings)
                for (candidate in found.tracks.filter { sameRecording(original, it) }.distinctBy { it.id }.take(3)) {
                    val track = candidate.toTrack(original.onlineKey?.bitrate ?: settings.preferredBitrate)
                    val result = resolve(track.onlineKey!!, settings)
                    if (result.stage == ResolveStage.OK) return@withTimeoutOrNull result to track
                }
                null
            } } catch (e: Exception) { if (e is CancellationException) throw e; null }
            if (next != null) return next
        }
        return null
    }

    private suspend fun resolve(key: OnlineKey, settings: KinaraSettings): ResolvedUrl {
        if (!settings.sources.isEnabled(key.platform)) return ResolvedUrl(ResolveStage.NOT_CONFIGURED, errorMessage = "该音源已关闭")
        return try {
            withTimeoutOrNull(15_000) { repo.resolveUrl(key, settings) }
                ?: ResolvedUrl(ResolveStage.TRANSPORT, errorMessage = "获取音频超时")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ResolvedUrl(ResolveStage.TRANSPORT, errorMessage = "获取音频失败，请检查网络")
        }
    }

    suspend fun lyricFor(track: Track, settings: KinaraSettings): String? =
        track.onlineKey?.let { (repo as? OnlineRepository)?.lyric(it, settings) }
}
