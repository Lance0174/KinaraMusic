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

    /**
     * 按曲目取歌词：先问它自己的音源，取不到再按"同一条录音"去其他已启用音源上找。
     *
     * 为什么必须要有这一步：bilibili 与 gd 没有任何歌词接口（上面的 when 落到 else -> null），
     * 而这两个平台恰恰是"别的源播不动"时最常用的兜底播放源——于是用户看到的就是
     * "这首歌能放，但永远没有歌词"。跨源兜底把这些歌重新接回有歌词的音源。
     * 匹配一律复用 sameRecording（标题归一化 + 歌手集合 + 时长 ±5s），避免张冠李戴。
     */
    suspend fun lyricFor(track: Track, settings: KinaraSettings): String? {
        val key = track.onlineKey ?: return null
        lyric(key, settings)?.takeIf { it.isNotBlank() }?.let { return it }
        return crossSourceLyric(track, settings)
    }

    private suspend fun crossSourceLyric(track: Track, settings: KinaraSettings): String? {
        val ownPlatform = track.onlineKey?.platform
        val keyword = "${track.title} ${track.artist}".trim()
        if (keyword.isEmpty()) return null
        for (platform in settings.sources.enabledInOrder()) {
            if (platform == ownPlatform) continue
            // 没有歌词接口的平台不必问（问了也只是白等一个超时）。
            if (platform !in LYRIC_PLATFORMS) continue
            val hits = withTimeoutOrNull(LYRIC_SEARCH_BUDGET_MS) {
                try { searchSource(platform, keyword, settings) } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
            }?.tracks?.filter { sameRecording(track, it) }?.distinctBy { it.id }?.take(2).orEmpty()
            for (hit in hits) {
                val text = withTimeoutOrNull(LYRIC_FETCH_BUDGET_MS) {
                    try { lyric(OnlineKey(platform, hit.id, track.onlineKey?.bitrate ?: settings.preferredBitrate), settings) }
                    catch (e: Exception) { if (e is CancellationException) throw e; null }
                }
                if (!text.isNullOrBlank()) return text
            }
        }
        return null
    }

    private companion object {
        /** 有歌词接口的平台；bilibili / gd 不在其中，只能通过跨源兜底拿词。 */
        val LYRIC_PLATFORMS = setOf("netease", "qq", "kugou", "kuwo", "solara")
        const val LYRIC_SEARCH_BUDGET_MS = 3500L
        const val LYRIC_FETCH_BUDGET_MS = 4000L
    }
}
