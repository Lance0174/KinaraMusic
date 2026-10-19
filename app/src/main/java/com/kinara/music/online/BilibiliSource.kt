/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.online

import com.kinara.music.account.BiliApi
import kotlinx.coroutines.CancellationException

/** Private builds use the configured service; public builds use anonymous official APIs. */
object BilibiliSource {
    suspend fun search(keyword: String): OnlineResult {
        val start = System.currentTimeMillis()
        if (BiliWorkerClient.BASES.isNotEmpty()) try {
            return OnlineResult("bilibili", BiliWorkerClient.search(keyword), elapsedMs = System.currentTimeMillis() - start)
        } catch (e: Exception) { if (e is CancellationException) throw e }
        return try {
            OnlineResult("bilibili", BiliApi.shared.search(keyword), elapsedMs = System.currentTimeMillis() - start)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            OnlineResult("bilibili", errorMessage = sourceErrorText(e.message), elapsedMs = System.currentTimeMillis() - start)
        }
    }

    suspend fun resolveUrl(bvid: String, cid: String = "", bitrate: String = "320"): ResolvedUrl {
        if (BiliWorkerClient.BASES.isNotEmpty()) {
            val result = BiliWorkerClient.resolve(bvid, cid, bitrate)
            if (result.stage == ResolveStage.OK) return result
        }
        return try {
            val audio = BiliApi.shared.resolveAudio(bvid, cid, bitrate)
            ResolvedUrl(ResolveStage.OK, audio.urls.first(), backupUrls = audio.urls.drop(1))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ResolvedUrl(ResolveStage.TRANSPORT, errorMessage = sourceErrorText(e.message))
        }
    }
}
