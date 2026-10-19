/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.online

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

object QQSource {
    private const val SEARCH = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=%s&format=json&n=30"
    private const val LYRIC_API = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=%s&g_tk=5381&format=json&nobase64=1"
    private val headers = mapOf("Referer" to "https://y.qq.com/", "User-Agent" to com.kinara.music.account.BiliApi.USER_AGENT)

    suspend fun search(keyword: String): OnlineResult = try {
        parseSearch(OnlineTransport.getText(SEARCH.format(OnlineTransport.enc(keyword)), headers))
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        OnlineResult("qq", errorMessage = "QQ音乐连接失败，请检查网络后重试")
    }

    internal fun parseSearch(response: OnlineTransport.TransportResponse): OnlineResult {
        if (response.code != 200) return OnlineResult("qq", errorMessage = "QQ音乐服务暂不可用（HTTP ${response.code}）")
        val root = runCatching { JSONObject(response.body) }.getOrNull()
            ?: return OnlineResult("qq", errorMessage = "QQ音乐响应格式无效")
        val code = root.optInt("code", -1)
        if (code != 0) return OnlineResult("qq", errorMessage = "QQ音乐搜索受限（$code）")
        val list = root.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
            ?: return OnlineResult("qq", errorMessage = "QQ音乐搜索响应不完整")
        return OnlineResult("qq", list.objects().mapNotNull { song ->
            val mid = song.optString("songmid")
            val title = song.optString("songname")
            if (!mid.matches(Regex("[A-Za-z0-9]+")) || title.isBlank()) return@mapNotNull null
            OnlineTrack("qq", mid, title, song.optJSONArray("singer").objects().joinToString("/") { it.optString("name") }.ifBlank { "未知艺术家" },
                song.optString("albumname").ifBlank { "未知专辑" }, song.optLongSafe("interval") * 1000,
                song.optString("albummid").takeIf { it.matches(Regex("[A-Za-z0-9]+")) }
                    ?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg" })
        })
    }

    /** Ask the official service for a public full-track URL; an empty purl is a real failure. */
    suspend fun resolveUrl(mid: String, bitrate: String): ResolvedUrl {
        if (!mid.matches(Regex("[A-Za-z0-9]+"))) return ResolvedUrl(ResolveStage.NO_RESULT, errorMessage = "QQ歌曲编号无效")
        return try {
            val prefix = when (bitrate) { "flac" -> "F000"; "128" -> "M500"; else -> "M800" }
            val extension = if (bitrate == "flac") ".flac" else ".mp3"
            val param = JSONObject().put("guid", System.currentTimeMillis().toString()).put("songmid", JSONArray().put(mid))
                .put("songtype", JSONArray().put(0)).put("uin", "0").put("loginflag", 1).put("platform", "20")
                .put("filename", JSONArray().put(prefix + mid + mid + extension))
            val payload = JSONObject().put("comm", JSONObject().put("ct", 24).put("cv", 0).put("uin", "0").put("format", "json"))
                .put("req", JSONObject().put("module", "vkey.GetVkeyServer").put("method", "CgiGetVkey").put("param", param))
            parseUrl(OnlineTransport.getText("https://u.y.qq.com/cgi-bin/musicu.fcg?data=" + OnlineTransport.enc(payload.toString()), headers), mid)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ResolvedUrl(ResolveStage.TRANSPORT, errorMessage = "QQ音乐取流连接失败")
        }
    }

    internal fun parseUrl(response: OnlineTransport.TransportResponse, mid: String): ResolvedUrl {
        if (response.code != 200) return ResolvedUrl(ResolveStage.TRANSPORT, errorMessage = "QQ音乐取流失败（HTTP ${response.code}）")
        val root = runCatching { JSONObject(response.body) }.getOrNull()
            ?: return ResolvedUrl(ResolveStage.TRANSPORT, errorMessage = "QQ音乐取流响应格式无效")
        val req = root.optJSONObject("req")
        if (root.optInt("code", -1) != 0 || req?.optInt("code", -1) != 0)
            return ResolvedUrl(ResolveStage.NO_RESULT, errorMessage = "QQ音乐未授权此播放请求")
        val data = req.optJSONObject("data")
        val row = data?.optJSONArray("midurlinfo").objects().firstOrNull { it.optString("songmid") == mid }
        val path = row?.optString("purl").orEmpty()
        if (path.isBlank()) return ResolvedUrl(ResolveStage.NO_RESULT, errorMessage = "QQ音乐未提供可播放地址，可能需要登录或会员权限")
        // Reject preview files; never silently substitute a paid track with a short trial.
        if (path.contains("trial", true) || path.contains("preview", true))
            return ResolvedUrl(ResolveStage.NO_RESULT, errorMessage = "QQ音乐仅提供试听片段")
        val sip = data?.optJSONArray("sip") ?: JSONArray()
        val urls = (0 until sip.length()).mapNotNull { index ->
            runCatching { URI(sip.getString(index)).resolve(path).toString() }.getOrNull()
        }.filter { url -> runCatching { URI(url).let { u ->
            u.scheme in listOf("http", "https") && u.rawUserInfo == null && u.port in listOf(-1, 80, 443) &&
                listOf("qq.com", "qqmusic.qq.com", "gtimg.com").any { u.host == it || u.host?.endsWith(".$it") == true }
        } }.getOrDefault(false) }.distinct()
        return if (urls.isEmpty()) ResolvedUrl(ResolveStage.NO_RESULT, errorMessage = "QQ音乐音频地址无效")
        else ResolvedUrl(ResolveStage.OK, urls.first(), backupUrls = urls.drop(1))
    }

    suspend fun lyric(songmid: String): String? = try {
        val response = OnlineTransport.getText(LYRIC_API.format(OnlineTransport.enc(songmid)), headers)
        val root = JSONObject(response.body)
        root.optString("lyric").takeIf { response.code == 200 && root.optInt("code", -1) == 0 && it.isNotBlank() }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    }
}
