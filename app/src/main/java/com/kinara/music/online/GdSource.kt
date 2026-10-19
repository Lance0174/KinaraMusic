/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * GD 聚合器（music.gdtube 类公开聚合 API，Solara 上游同款）。
 * 用途：①酷狗/QQ 曲目按"标题+歌手"在 netease 源匹配播放（matched=true，明示"聚合"）；
 *       ②其他源全挂时的搜索兜底（gdFallback 开关控制——已关闭则零请求）。
 */
package com.kinara.music.online

import org.json.JSONArray
import org.json.JSONObject

object GdSource {

    const val PLATFORM = "gd"
    private const val BASE = "https://music-api.gdstudio.xyz/api.php"

    suspend fun search(keyword: String, limit: Int = 30): OnlineResult {
        val t0 = System.currentTimeMillis()
        return try {
            val u = "$BASE?types=search&source=netease&name=${OnlineTransport.enc(keyword)}&count=$limit&pages=1"
            val resp = OnlineTransport.getText(u)
            if (resp.code != 200) return OnlineResult(PLATFORM, errorMessage = "HTTP ${resp.code}", elapsedMs = System.currentTimeMillis() - t0)
            val arr = JSONArray(resp.body)
            val tracks = arr.objects().map { s ->
                OnlineTrack(
                    platform = PLATFORM,
                    id = listOf(s.optString("id"), s.optString("url_id"), s.optString("lyric_id"), s.optString("source")).joinToString("|"),
                    title = s.optString("name"),
                    artist = (s.optJSONArray("artist")?.let { a -> (0 until a.length()).mapNotNull { runCatching { a.getString(it) }.getOrNull() } }?.joinToString("/") ?: "").ifBlank { "未知艺术家" },
                    album = s.optString("album").ifBlank { "未知专辑" },
                    matched = true,
                )
            }
            OnlineResult(PLATFORM, tracks, elapsedMs = System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            OnlineResult(PLATFORM, errorMessage = "${e.javaClass.simpleName}: ${e.message}", elapsedMs = System.currentTimeMillis() - t0)
        }
    }

    /** 按标题+歌手匹配一首（用于酷狗/QQ 播放兜底）。 */
    suspend fun matchOne(title: String, artist: String): OnlineTrack? {
        val r = search("$title $artist", limit = 8)
        return r.tracks.firstOrNull { candidate ->
            sameRecording(title, artist, candidate.title, candidate.artist)
        }
    }

    suspend fun resolveUrl(compoundId: String, br: String): ResolvedUrl { return try {
        val parts = compoundId.split("|")
        val urlId = parts.getOrNull(1) ?: parts.firstOrNull() ?: return ResolvedUrl(ResolveStage.NO_RESULT, errorMessage = "id 缺失")
        val src = parts.getOrNull(3) ?: "netease"
        val brParam = if (br == "flac") "999" else br
        val u = "$BASE?types=url&source=${OnlineTransport.enc(src)}&id=${OnlineTransport.enc(urlId)}&br=${OnlineTransport.enc(brParam)}"
        val resp = OnlineTransport.getText(u)
        val url = JSONObject(resp.body).optString("url")
        if (resp.code == 200 && url.startsWith("http")) ResolvedUrl(ResolveStage.OK, url, viaProxy = true)
        else ResolvedUrl(ResolveStage.NO_RESULT, errorMessage = "url 为空")
    } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        ResolvedUrl(ResolveStage.TRANSPORT, errorMessage = "${e.javaClass.simpleName}: ${e.message}")
    }
} // fix: close converted block body
}
