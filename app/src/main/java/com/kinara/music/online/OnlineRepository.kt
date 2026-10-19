/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.online

import com.kinara.music.data.*
import kotlinx.coroutines.*

interface PlaybackAccess {
    suspend fun searchSource(platform: String, keyword: String, settings: KinaraSettings): OnlineResult
    suspend fun resolveUrl(key: OnlineKey, settings: KinaraSettings): ResolvedUrl
}

class OnlineRepository(private val toggles: () -> SourceToggles) : PlaybackAccess {
    /** Sources run in parallel. A slow source cannot determine the display order. */
    suspend fun search(keyword: String, generation: Long, solara: Pair<String, String>?, onResult: (OnlineResult, Long) -> Unit) {
        val settings = KinaraSettings(sources = toggles(), solaraBaseUrl = solara?.first.orEmpty(), solaraSecret = solara?.second.orEmpty())
        supervisorScope {
            settings.sources.enabledInOrder().map { platform -> async {
                val result = withTimeoutOrNull(15_000) { searchSource(platform, keyword, settings) }
                    ?: OnlineResult(platform, errorMessage = "请求超时，请稍后重试")
                onResult(result, generation)
                // Covers are optional; publish the songs before waiting for artwork.
                if (platform == "netease" && result.tracks.isNotEmpty()) {
                    withTimeoutOrNull(5000) { NeteaseSource.enrichCovers(result.tracks) }
                        ?.let { onResult(result.copy(tracks = it), generation) }
                }
            } }.awaitAll()
        }
    }

    override suspend fun searchSource(platform: String, keyword: String, settings: KinaraSettings): OnlineResult {
        if (!settings.sources.isEnabled(platform)) return OnlineResult(platform, errorMessage = "该音源已关闭")
        return when (platform) {
            "netease" -> NeteaseSource.search(keyword)
            "qq" -> QQSource.search(keyword)
            "kugou" -> KuGouSource.search(keyword)
            "kuwo" -> KuwoSource.search(keyword)
            "bilibili" -> BilibiliSource.search(keyword)
            "solara" -> SolaraSource.search(keyword, settings.solaraBaseUrl, settings.solaraSecret)
            "gd" -> GdSource.search(keyword)
            else -> OnlineResult(platform, errorMessage = "未知音源")
        }
    }

    /** Resolve only this identity. Cross-source changes restart the MediaSource and cache key. */
    override suspend fun resolveUrl(key: OnlineKey, settings: KinaraSettings): ResolvedUrl {
        if (!settings.sources.isEnabled(key.platform)) return ResolvedUrl(ResolveStage.NOT_CONFIGURED, errorMessage = "该音源已关闭")
        return when (key.platform) {
            "netease" -> NeteaseSource.resolveUrl(key.id, key.bitrate)
            "qq" -> QQSource.resolveUrl(key.id, key.bitrate)
            "kugou" -> ResolvedUrl(ResolveStage.NOT_CONFIGURED, errorMessage = "酷狗暂未提供可用的公开播放地址")
            "kuwo" -> KuwoSource.resolveUrl(key.id, key.bitrate)
            "bilibili" -> key.id.split('|').let { BilibiliSource.resolveUrl(it[0], it.getOrElse(1) { "" }, key.bitrate) }
            "solara" -> SolaraSource.resolveUrl(key.id, key.bitrate, settings.solaraBaseUrl, settings.solaraSecret)
            "gd" -> GdSource.resolveUrl(key.id, key.bitrate)
            else -> ResolvedUrl(ResolveStage.NOT_CONFIGURED, errorMessage = "未知音源")
        }
    }

    suspend fun lyric(key: OnlineKey, settings: KinaraSettings): String? = if (!settings.sources.isEnabled(key.platform)) null else when (key.platform) {
        "netease" -> NeteaseSource.lyric(key.id)
        "kuwo" -> KuwoSource.lyric(key.id)
        "kugou" -> KuGouSource.lyric(key.id)
        "qq" -> QQSource.lyric(key.id)
        "solara" -> SolaraSource.lyric(key.id, settings.solaraBaseUrl, settings.solaraSecret)
        else -> null
    }
}
