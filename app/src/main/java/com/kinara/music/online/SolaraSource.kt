/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * Solara 补充音源（默认关闭；只有本机构建注入配置后才启用）。
 * 契约按兼容的 Solara 服务实现；服务地址和凭据均由运行时外部提供：
 *  - 鉴权：Cookie auth=<base64(密钥)>，可离线计算，无需登录接口；
 *  - search/url/lyric/pic 走 /proxy?types=...；上游仅支持 netease/joox；
 *  - 300s CF 缓存可能回过期直链（网易直链约30分钟失效），取地址必须带 nocache=true；
 *  - 不可用时 url 为 ""，按失败处理；
 *  - 未授权访问会 302 跳 /login（旧版中间件），原生端判 302/401 为密钥错误。
 */
package com.kinara.music.online

import android.util.Base64
import org.json.JSONObject

object SolaraSource {

    const val PLATFORM = "solara"

    private fun authCookie(secret: String): String =
        "auth=" + Base64.encodeToString(secret.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private fun base(url: String): String = BiliWorkerClient.normalizeBase(url)
        ?: throw TransportException(ResolveStage.TRANSPORT, "服务地址必须是 HTTPS 域名，不能包含路径、参数或凭据")

    suspend fun search(keyword: String, baseUrl: String, secret: String): OnlineResult {
        val t0 = System.currentTimeMillis()
        if (baseUrl.isBlank()) return OnlineResult(PLATFORM, errorMessage = "未配置服务地址", elapsedMs = 0)
        return try {
            val u = "${base(baseUrl)}/proxy?types=search&source=netease&name=${OnlineTransport.enc(keyword)}&count=30&pages=1&nocache=true"
            val resp = OnlineTransport.getText(u, headers = mapOf("Cookie" to authCookie(secret)))
            if (resp.code != 200) {
                OnlineResult(PLATFORM, errorMessage = "HTTP ${resp.code}（检查地址/密钥）", elapsedMs = System.currentTimeMillis() - t0)
            } else {
                val arr = org.json.JSONArray(resp.body)
                val tracks = arr.objects().map { s ->
                    OnlineTrack(
                        platform = PLATFORM,
                        id = listOf(
                            s.optString("id"), s.optString("url_id"), s.optString("lyric_id"),
                            s.optString("pic_id"), s.optString("source"),
                        ).joinToString("|"),
                        title = s.optString("name"),
                        artist = (s.optJSONArray("artist")?.let { a -> (0 until a.length()).mapNotNull { runCatching { a.getString(it) }.getOrNull() } }?.joinToString("/")
                            ?: s.optString("artist")).ifBlank { "未知艺术家" },
                        album = s.optString("album").ifBlank { "未知专辑" },
                    )
                }
                OnlineResult(PLATFORM, tracks, elapsedMs = System.currentTimeMillis() - t0)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            OnlineResult(PLATFORM, errorMessage = "${e.javaClass.simpleName}: ${e.message}", elapsedMs = System.currentTimeMillis() - t0)
        }
    }

    /** 复合 id：neteaseId|urlId|lyricId|picId|source */
    suspend fun resolveUrl(compoundId: String, br: String, baseUrl: String, secret: String): ResolvedUrl { return try {
        val parts = compoundId.split("|")
        val urlId = parts.getOrNull(1) ?: parts.firstOrNull() ?: return ResolvedUrl(ResolveStage.NO_RESULT, errorMessage = "id 缺失")
        val src = parts.getOrNull(4) ?: "netease"
        val brParam = if (br == "flac") "999" else br
        val u = "${base(baseUrl)}/proxy?types=url&source=${OnlineTransport.enc(src)}&id=${OnlineTransport.enc(urlId)}&br=${OnlineTransport.enc(brParam)}&nocache=true"
        val resp = OnlineTransport.getText(u, headers = mapOf("Cookie" to authCookie(secret)))
        val url = JSONObject(resp.body).optString("url")
        if (resp.code == 200 && url.startsWith("http")) ResolvedUrl(ResolveStage.OK, url, viaProxy = true)
        else ResolvedUrl(ResolveStage.NO_RESULT, errorMessage = "url 为空（上游源可能不可用）")
    } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        ResolvedUrl(ResolveStage.TRANSPORT, errorMessage = "${e.javaClass.simpleName}: ${e.message}")
    }
} // fix: close converted block body

    suspend fun lyric(compoundId: String, baseUrl: String, secret: String): String? { return try {
        val parts = compoundId.split("|")
        val lyricId = parts.getOrNull(2) ?: parts.firstOrNull() ?: return null
        val src = parts.getOrNull(4) ?: "netease"
        val u = "${base(baseUrl)}/proxy?types=lyric&source=${OnlineTransport.enc(src)}&id=${OnlineTransport.enc(lyricId)}"
        val resp = OnlineTransport.getText(u, headers = mapOf("Cookie" to authCookie(secret)))
        JSONObject(resp.body).optString("lyric").takeIf { it.isNotBlank() && it != "暂无歌词" }
    } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null }
} // fix: close converted block body
}
