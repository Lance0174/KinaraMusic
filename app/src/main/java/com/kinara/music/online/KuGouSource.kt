/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 酷狗：搜索/歌词直连（mobilecdn HTTP 明文，白名单已放行 kugou.com——E020 教训）。
 * 播放地址需签名（getdata/playInfo 均空，上轮实测），本轮经自建 CF Worker 试点（领导批示 D3）。
 */
package com.kinara.music.online

import org.json.JSONObject

object KuGouSource {

    private const val SEARCH = "http://mobilecdn.kugou.com/api/v3/search/song?format=json&keyword=%s&page=1&pagesize=30"
    private const val LYRIC_CAND = "http://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=&duration=&hash=%s"
    private const val LYRIC_DL = "http://lyrics.kugou.com/download?ver=1&client=pc&id=%s&access_key=%s&fmt=lrc&charset=utf8"

    suspend fun search(keyword: String): OnlineResult {
        val t0 = System.currentTimeMillis()
        return try {
            val resp = OnlineTransport.getText(SEARCH.format(OnlineTransport.enc(keyword)))
            if (resp.code != 200) return OnlineResult("kugou", errorMessage = "HTTP ${resp.code}", elapsedMs = System.currentTimeMillis() - t0)
            val infos = JSONObject(resp.body).optJSONObject("data")?.optJSONArray("info") ?: org.json.JSONArray()
            val tracks = infos.objects().map { s ->
                OnlineTrack(
                    platform = "kugou",
                    id = s.optString("hash"), // 酷狗以 hash 定位；地址签名方案（Worker）也按 hash
                    title = s.optString("songname"),
                    artist = s.optString("singername").ifBlank { "未知艺术家" },
                    album = s.optString("album_name").ifBlank { "未知专辑" },
                    durationMs = s.optLong("duration") * 1000,
                )
            }.filter { it.id.isNotBlank() }
            OnlineResult("kugou", tracks, elapsedMs = System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            OnlineResult("kugou", errorMessage = "${e.javaClass.simpleName}: ${e.message}", elapsedMs = System.currentTimeMillis() - t0)
        }
    }

    /** 歌词：krcs 候选 → base64 LRC（上轮实测可用）。 */
    suspend fun lyric(hash: String): String? { return try {
        val cand = JSONObject(OnlineTransport.getText(LYRIC_CAND.format(OnlineTransport.enc(hash))).body)
        val c0 = cand.optJSONArray("candidates")?.optJSONObject(0) ?: return null
        val id = c0.optString("id"); val key = c0.optString("access_key")
        val dl = JSONObject(OnlineTransport.getText(LYRIC_DL.format(OnlineTransport.enc(id), OnlineTransport.enc(key))).body)
        val b64 = dl.optString("content")
        if (b64.isNotBlank()) String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8) else null
    } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null }
} // fix: close converted block body
}
