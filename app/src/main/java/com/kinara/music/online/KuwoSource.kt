/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 酷我直连。上轮实测结论：
 *  - 搜索 HTTP 明文（search.kuwo.cn），manifest 明文白名单已放行 kuwo.cn；
 *  - 地址接口 antiserver 响应体直接是地址文本，不是 JSON——按 JSON 解析必失败；
 *  - 地址 Range 206 且魔数 ID3，真音频。
 */
package com.kinara.music.online

import org.json.JSONObject

object KuwoSource {

    private const val SEARCH = "http://search.kuwo.cn/r.s?all=%s&ft=music&itemset=web_2016&client=kt&pn=0&rn=30&rformat=json&encoding=utf8"
    private const val URL_API = "http://antiserver.kuwo.cn/anti.s?type=convert_url&rid=MUSIC_%s&format=%s"
    private const val LYRIC_API = "http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=%s"

    suspend fun search(keyword: String): OnlineResult {
        val t0 = System.currentTimeMillis()
        return try {
            val resp = OnlineTransport.getText(SEARCH.format(OnlineTransport.enc(keyword)))
            if (resp.code != 200) return OnlineResult("kuwo", errorMessage = "HTTP ${resp.code}", elapsedMs = System.currentTimeMillis() - t0)
            val abslist = JSONObject(resp.body).optJSONArray("abslist") ?: org.json.JSONArray()
            val tracks = abslist.objects().map { s ->
                OnlineTrack(
                    platform = "kuwo",
                    id = s.optString("DC_TARGETID").ifBlank { s.optString("MVPID") },
                    title = s.optString("NAME"),
                    artist = s.optString("ARTIST").ifBlank { "未知艺术家" },
                    album = s.optString("ALBUM").ifBlank { "未知专辑" },
                    durationMs = s.optLongSafe("DURATION") * 1000, // 实测单位为秒
                )
            }.filter { it.id.isNotBlank() }
            OnlineResult("kuwo", tracks, elapsedMs = System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            OnlineResult("kuwo", errorMessage = "${e.javaClass.simpleName}: ${e.message}", elapsedMs = System.currentTimeMillis() - t0)
        }
    }

    suspend fun resolveUrl(id: String, br: String): ResolvedUrl { return try {
        val fmt = if (br == "flac") "flac" else "mp3"
        val resp = OnlineTransport.getText(URL_API.format(id, fmt))
        val url = resp.body.trim().lines().firstOrNull().orEmpty()
        if (resp.code == 200 && url.startsWith("http")) {
            ResolvedUrl(ResolveStage.OK, url)
        } else {
            ResolvedUrl(ResolveStage.NO_RESULT, errorMessage = "响应非地址文本: ${resp.body.take(60)}")
        }
    } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        ResolvedUrl(ResolveStage.TRANSPORT, errorMessage = "${e.javaClass.simpleName}: ${e.message}")
    }
} // fix: close converted block body

    suspend fun lyric(id: String): String? { return try {
        val resp = OnlineTransport.getText(LYRIC_API.format(id))
        if (resp.code != 200) return null
        parseLyric(JSONObject(resp.body))
    } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null }
} // fix: close converted block body

    internal fun parseLyric(root: JSONObject): String? {
        val arr = root.optJSONObject("data")?.optJSONArray("lrclist") ?: root.optJSONArray("lrclist") ?: return null
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val seconds = o.optString("time").toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..86_400.0 } ?: continue
            val ms = kotlin.math.round(seconds * 1000).toLong()
            sb.append("[${ms / 60_000}:${(ms / 1000 % 60).toString().padStart(2, '0')}.${(ms % 1000).toString().padStart(3, '0')}]${o.optString("lineLyric")}\n")
        }
        return sb.toString().ifBlank { null }
    }
}
