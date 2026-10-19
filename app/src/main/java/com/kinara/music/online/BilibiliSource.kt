/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.online

import com.kinara.music.account.BiliApi
import com.kinara.music.account.BiliSession
import kotlinx.coroutines.CancellationException

object BilibiliSource {
    suspend fun search(keyword: String): OnlineResult {
        val start = System.currentTimeMillis()
        val authenticated = BiliSession.cookie().isNotBlank()
        return try {
            OnlineResult("bilibili", BiliApi.shared.search(keyword), elapsedMs = System.currentTimeMillis() - start)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Worker is an anonymous fallback, never supplied with the personal session.
            if (authenticated) OnlineResult("bilibili", errorMessage = e.message, elapsedMs = System.currentTimeMillis() - start)
            else try { OnlineResult("bilibili", BiliWorkerClient.search(keyword), elapsedMs = System.currentTimeMillis() - start) }
            catch (fallback: Exception) {
                if (fallback is CancellationException) throw fallback
                OnlineResult("bilibili", errorMessage = sourceErrorText(e.message), elapsedMs = System.currentTimeMillis() - start)
            }
        }
    }

    suspend fun resolveUrl(bvid: String, cid: String = "", bitrate: String = "320"): ResolvedUrl = try {
        val audio = BiliApi.shared.resolveAudio(bvid, cid, bitrate)
        ResolvedUrl(ResolveStage.OK, audio.urls.first(), backupUrls = audio.urls.drop(1))
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        if (BiliSession.cookie().isNotBlank()) ResolvedUrl(ResolveStage.TRANSPORT, errorMessage = e.message)
        // The legacy anonymous Worker resolves only the first part; never substitute another CID.
        else if (cid.isNotBlank()) ResolvedUrl(ResolveStage.TRANSPORT, errorMessage = sourceErrorText(e.message))
        else BiliWorkerClient.resolve(bvid).let { if (it.stage == ResolveStage.OK) it else it.copy(errorMessage = sourceErrorText(e.message)) }
    }
}
