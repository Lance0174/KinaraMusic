/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 网易云直连。端点为上轮实测可用（搜索 963ms / 播放地址 244ms 真音频 / 歌词 OK）。
 * 注意：接口 HTTPS，但下发的音频 CDN（music.126.net 子域）是 HTTP——
 * 明文在播放器入口升级为 HTTPS（E019 教训：放行要看最终请求域名）。
 */
package com.kinara.music.online

import org.json.JSONObject

object NeteaseSource {

    private const val SEARCH = "https://music.163.com/api/search/get/web?s=%s&type=1&limit=30"
    private const val URL_API = "https://music.163.com/api/song/enhance/player/url?ids=%s&br=%s"
    private const val LYRIC_API = "https://music.163.com/api/song/lyric?id=%s&lv=1&kv=1&tv=-1"

    suspend fun search(keyword: String): OnlineResult {
        val t0 = System.currentTimeMillis()
        return try {
            val resp = OnlineTransport.getText(SEARCH.format(OnlineTransport.enc(keyword)))
            if (resp.code != 200) return OnlineResult("netease", errorMessage = "HTTP ${resp.code}", elapsedMs = System.currentTimeMillis() - t0)
            val json = JSONObject(resp.body)
            val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: org.json.JSONArray()
            val tracks = songs.objects().map { s ->
                val artists = s.optJSONArray("artists").objects()
                OnlineTrack(
                    platform = "netease",
                    id = s.optLongSafe("id").toString(),
                    title = s.optString("name"),
                    artist = artists.joinToString("/") { it.optString("name") }.ifBlank { "未知艺术家" },
                    album = s.optJSONObject("album")?.optString("name").orEmpty().ifBlank { "未知专辑" },
                    durationMs = s.optLongSafe("duration"),
                    // 封面：搜索接口不给完整 URL（只有数字 picId，拼不出可用地址——已实测 404）。
                    // 完整 picUrl 由 /api/song/detail 提供，见 enrichCovers()。
                )
            }
            OnlineResult("netease", tracks, elapsedMs = System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            OnlineResult("netease", errorMessage = "${e.javaClass.simpleName}: ${e.message}", elapsedMs = System.currentTimeMillis() - t0)
        }
    }

    suspend fun resolveUrl(id: String, br: String): ResolvedUrl { return try {
        val brParam = if (br == "flac") "999000" else "${br}000"
        val resp = OnlineTransport.getText(URL_API.format(OnlineTransport.enc("[$id]"), OnlineTransport.enc(brParam)))
        val code = JSONObject(resp.body).optInt("code")
        val data = JSONObject(resp.body).optJSONArray("data")?.optJSONObject(0)
        val url = data?.optString("url").orEmpty()
        when {
            data?.optJSONObject("freeTrialInfo") != null ->
                ResolvedUrl(ResolveStage.NO_RESULT, errorMessage = "网易云仅提供试听片段")
            resp.code == 200 && code == 200 && url.isNotBlank() ->
                ResolvedUrl(ResolveStage.OK, url, bitrate = data?.optLongSafe("br")?.toString())
            else -> ResolvedUrl(ResolveStage.NO_RESULT, errorMessage = "code=$code url空")
        }
    } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
        ResolvedUrl(ResolveStage.TRANSPORT, errorMessage = "${e.javaClass.simpleName}: ${e.message}")
    }
} // fix: close converted block body

    /**
     * 批量补封面：搜索接口只给数字 picId（拼不出可用地址，已实测 404），
     * 完整 picUrl 需经 /api/song/detail 获取（实测返回可下载的加密串 URL）。
     * 失败时保留原曲目（封面缺失不影响播放与列表）。
     */
    suspend fun enrichCovers(tracks: List<OnlineTrack>): List<OnlineTrack> {
        if (tracks.isEmpty()) return tracks
        return try {
            val ids = tracks.map { it.id }.take(30).joinToString(",", "[", "]")
            val resp = OnlineTransport.getText("https://music.163.com/api/song/detail?ids=" + OnlineTransport.enc(ids))
            val songs = JSONObject(resp.body).optJSONArray("songs") ?: return tracks
            val coverById = HashMap<String, String>()
            for (i in 0 until songs.length()) {
                val o = songs.optJSONObject(i) ?: continue
                val url = o.optJSONObject("album")?.optString("picUrl").orEmpty()
                if (url.isNotBlank()) coverById[o.optLongSafe("id").toString()] = url
            }
            tracks.map { tr -> coverById[tr.id]?.let { tr.copy(picUrl = it) } ?: tr }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            tracks }
    }

    /**
     * 歌词：真实结构为 {"lrc":{"lyric":"..."},"tlyric":{"lyric":"..."}}。
     * 修正记录（宿主机实测发现）：曾误读顶层 "lrc"（那是对象），导致歌词恒为空。
     */
    // 历史实现说明（已停用）：
    // 有翻译时合并输出（翻译行插入原文行之后，行内容以"译文："前缀区分）
    // 修复：翻译不再插入同时间的独立行，保留为正文的附属轨道。
    suspend fun lyric(id: String): String? = try {
        val resp = OnlineTransport.getText(LYRIC_API.format(OnlineTransport.enc(id)))
        JSONObject(resp.body).optJSONObject("lrc")?.optString("lyric")?.takeIf { resp.code == 200 && it.isNotBlank() }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }

    suspend fun lyrics(id: String): LyricDocument {
        val rich = try {
            val response = OnlineTransport.getText("https://music.163.com/api/song/lyric/v1?id=" + OnlineTransport.enc(id) +
                "&cp=false&lv=0&tv=0&rv=0&kv=0&yv=0&ytv=0&yrv=0")
            if (response.code == 200) parseLyrics(JSONObject(response.body)) else LyricDocument()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LyricDocument()
        }
        if (rich.lines.isNotEmpty()) return rich
        return try {
            val response = OnlineTransport.getText(LYRIC_API.format(OnlineTransport.enc(id)))
            if (response.code == 200) parseLyrics(JSONObject(response.body)) else LyricDocument()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            LyricDocument()
        }
    }

    /** 合并翻译歌词：按时间戳对齐，译文附在原文同时间行后（纯函数，可测）。
     * 修复补充：这里的“行后”是字段展示顺序，不能再创建独立时间行。
     */
    internal fun parseLyrics(root: JSONObject): LyricDocument {
        if (root.optInt("code", -1) != 200) return LyricDocument()
        fun text(key: String) = root.optJSONObject(key)?.optString("lyric").orEmpty()
        val yrc = LyricTiming.parseYrc(text("yrc"))
        val useWords = yrc.lines.any { it.words.isNotEmpty() }
        val original = if (useWords) yrc else LrcParser.parseDocument(text("lrc"))
        // YRC translations use their own timeline; do not force LRC timestamps onto YRC lines.
        val translated = text(if (useWords) "ytlrc" else "tlyric")
        val romanized = text(if (useWords) "yromalrc" else "romalrc")
        fun side(raw: String): List<LyricLine> {
            val parsed = LrcParser.parseDocument(raw)
            return if ("offset" in parsed.metadata) parsed.lines else parsed.lines.map { it.copy(timeMs = it.timeMs - original.fileOffsetMs) }
        }
        val withTranslation = LyricTiming.attach(original.lines, side(translated))
        return original.copy(lines = LyricTiming.attach(withTranslation, side(romanized), romanization = true))
    }

    /** Exact platform identity; only display metadata is returned, never a playback URL. */
    suspend fun detail(id: String): OnlineTrack? {
        if (!id.matches(Regex("[0-9]+"))) return null
        val response = OnlineTransport.getText("https://music.163.com/api/song/detail?ids=" + OnlineTransport.enc("[$id]"))
        if (response.code != 200) return null
        val root = JSONObject(response.body)
        if (root.optInt("code", -1) != 200) return null
        val song = root.optJSONArray("songs").objects().firstOrNull { it.optLongSafe("id").toString() == id } ?: return null
        val album = song.optJSONObject("album") ?: song.optJSONObject("al")
        return OnlineTrack("netease", id, song.optString("name"),
            (song.optJSONArray("artists") ?: song.optJSONArray("ar")).objects().joinToString("/") { it.optString("name") },
            album?.optString("name").orEmpty(), song.optLongSafe("duration").takeIf { it > 0 } ?: song.optLongSafe("dt"),
            picUrl = album?.optString("picUrl")?.takeIf { it.isNotBlank() })
    }
}
