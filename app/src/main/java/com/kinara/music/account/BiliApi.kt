/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0.
 * Protocol reference: https://github.com/SocialSisterYi/bilibili-API-collect
 * Anonymous official API. Account credentials are held only by the private Cloudflare Worker.
 */
package com.kinara.music.account

import com.kinara.music.online.OnlineTrack
import com.kinara.music.online.OnlineTransport
import com.kinara.music.online.objects
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

internal typealias BiliRequest = suspend (String, Map<String, String>) -> OnlineTransport.TransportResponse
class BiliApiException(val code: Int, message: String) : IOException(message)

class BiliApi internal constructor(private val request: BiliRequest) {
    constructor() : this({ url, headers -> OnlineTransport.getText(url, headers, followRedirects = false) })
    private val keyLock = Mutex()
    private var wbi: Pair<Long, String>? = null
    private var guest = ""
    private val guestLock = Mutex()

    suspend fun search(keyword: String): List<OnlineTrack> {
        val result = withApiCookie { cookie -> signedData("/x/web-interface/wbi/search/type",
            mapOf("search_type" to "video", "keyword" to keyword, "page" to "1"), cookie) }
        val rows = result.optJSONArray("result") ?: throw IOException("B站搜索响应不完整，请稍后重试")
        return rows.objects()
            .filter { it.optString("bvid").matches(Regex("BV[0-9A-Za-z]{10}")) }.map { row ->
            OnlineTrack("bilibili", row.optString("bvid") + "|", android.text.Html.fromHtml(row.optString("title"), 0).toString(),
                row.optString("author"), "哔哩哔哩", row.optString("duration").split(":")
                    .fold(0L) { total, part -> total * 60 + (part.toLongOrNull() ?: 0) } * 1000,
                row.optString("pic").let { if (it.startsWith("//")) "https:$it" else it }.takeIf { it.startsWith("https://") })
        }
    }

    data class Audio(val urls: List<String>, val id: Int)

    suspend fun resolve(bvid: String, cid: String = ""): String = resolveAudio(bvid, cid).urls.first()

    suspend fun resolveAudio(bvid: String, cid: String = "", bitrate: String = "320"): Audio {
        require(bvid.matches(Regex("BV[0-9A-Za-z]{10}"))) { "视频编号无效" }
        return withApiCookie { cookie ->
        val actualCid = cid.toLongOrNull()?.takeIf { it > 0 }?.toString() ?: data(get(
            "https://api.bilibili.com/x/web-interface/view?bvid=${encode(bvid)}", cookie)).optLong("cid").takeIf { it > 0 }?.toString()
            ?: throw IOException("未找到视频分集")
        val info = signedData("/x/player/wbi/playurl", mapOf("bvid" to bvid, "cid" to actualCid,
            "fnval" to "16", "fnver" to "0", "fourk" to "0", "platform" to "pc"), cookie)
        val tracks = info.optJSONObject("dash")?.optJSONArray("audio")?.objects().orEmpty()
            .filter { it.optString("codecs").let { codec -> codec.isBlank() || codec.startsWith("mp4a.") } }
            .sortedBy { it.optLong("bandwidth") }
        // Each candidate list contains one encoded track only; another bitrate requires a new stream.
        val ordered = if (bitrate == "128") tracks else tracks.reversed()
        ordered.firstNotNullOfOrNull { audio ->
            val backups = audio.optJSONArray("backupUrl") ?: audio.optJSONArray("backup_url")
            val urls = (listOf(audio.optString("baseUrl").ifBlank { audio.optString("base_url") }) +
                (0 until (backups?.length() ?: 0)).map { backups!!.optString(it) }).filter(::trustedAudioUrl).distinct().take(4)
            urls.takeIf { it.isNotEmpty() }?.let { Audio(it, audio.optInt("id")) }
        } ?: throw BiliApiException(-10403, "该视频暂无可用音轨或需要额外权限")
        }
    }

    private suspend fun apiCookie(): String {
        // Get actual guest device identifiers; never fabricate them.
        guestLock.withLock {
        if (guest.isBlank()) {
            try {
                val finger = data(get("https://api.bilibili.com/x/frontend/finger/spi", ""))
                guest = listOf("buvid3" to finger.optString("b_3"), "buvid4" to finger.optString("b_4"))
                    .filter { (_, v) -> v.isNotBlank() && v.none { it == ';' || it == '\r' || it == '\n' } }
                    .joinToString("; ") { "${it.first}=${it.second}" }
            } catch (e: Exception) { if (e is CancellationException) throw e }
        }
        }
        return guest
    }

    private suspend fun <T> withApiCookie(block: suspend (String) -> T): T = block(apiCookie())

    private suspend fun signedData(path: String, parameters: Map<String, String>, cookie: String): JSONObject = try {
        data(get(signed(path, parameters, cookie), cookie))
    } catch (e: BiliApiException) {
        if (e.code != -403) throw e
        keyLock.withLock { wbi = null }
        data(get(signed(path, parameters, cookie), cookie))
    }

    private suspend fun signed(path: String, parameters: Map<String, String>, cookie: String): String {
        val key = keyLock.withLock {
            wbi?.takeIf { System.currentTimeMillis() - it.first < 600_000 }?.second ?: run {
                // Anonymous nav can return root code -101 and still include WBI keys.
                val keys = json(get("https://api.bilibili.com/x/web-interface/nav", cookie))
                    .optJSONObject("data")?.optJSONObject("wbi_img") ?: throw IOException("暂时无法获取 B站请求签名")
                fun part(name: String) = keys.optString(name).substringAfterLast('/').substringBefore('.')
                mixinKey(part("img_url") + part("sub_url")).also { wbi = System.currentTimeMillis() to it }
            }
        }
        return "https://api.bilibili.com$path?" + signedQuery(parameters, key, System.currentTimeMillis() / 1000)
    }

    private suspend fun get(url: String, cookie: String): OnlineTransport.TransportResponse {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "api.bilibili.com")
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://www.bilibili.com/", "Accept" to "application/json") +
            if (cookie.isNotBlank()) mapOf("Cookie" to cookie) else emptyMap()
        return try { request(url, headers) }
        catch (e: Exception) { if (e is CancellationException) throw e; throw IOException("B站连接失败，请检查网络后重试") }
    }

    private fun json(response: OnlineTransport.TransportResponse): JSONObject {
        if (response.code !in 200..299) throw BiliApiException(response.code, if (response.code == 412) "B站暂时限制了当前网络，请稍后重试" else "B站服务暂不可用（${response.code}）")
        return try { JSONObject(response.body) } catch (_: Exception) { throw IOException("B站返回了无法识别的响应") }
    }
    private fun data(response: OnlineTransport.TransportResponse): JSONObject {
        val root = json(response)
        val code = root.optInt("code", -1)
        if (code != 0) throw BiliApiException(code, when (code) {
            -101 -> "B站接口暂时不可用"
            -352, -412 -> "B站暂时限制了当前网络，请稍后重试"
            -404, 62002 -> "视频不存在或已被删除"
            -10403, 6002003 -> "此视频需要额外播放权限"
            else -> "B站请求失败（$code）"
        })
        val result = root.optJSONObject("data") ?: throw IOException("B站响应不完整")
        if (result.has("v_voucher")) throw BiliApiException(-352, "B站要求验证当前访问，请在官方客户端完成验证后重试")
        return result
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        val shared = BiliApi()
        fun trustedAudioUrl(url: String): Boolean = runCatching { URI(url).let {
            it.scheme == "https" && it.userInfo == null && it.port in listOf(-1, 443) &&
                listOf("bilivideo.com", "bilivideo.cn").any { suffix -> it.host?.lowercase().let { host -> host == suffix || host?.endsWith(".$suffix") == true } }
        } }.getOrDefault(false)
        fun playbackHeaders(url: String): Map<String, String> = if (trustedAudioUrl(url))
            mapOf("User-Agent" to USER_AGENT, "Referer" to "https://www.bilibili.com/") else emptyMap()
        internal fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
        internal fun mixinKey(raw: String): String {
            require(raw.length == 64) { "B站签名数据无效" }
            val indices = intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13)
            return indices.map { raw[it] }.joinToString("")
        }
        internal fun signedQuery(parameters: Map<String, String>, key: String, seconds: Long): String {
            val query = (parameters + ("wts" to seconds.toString())).toSortedMap().entries.joinToString("&") { (k,v) ->
                "${encode(k)}=${encode(v.filterNot { it in "!'()*" })}"
            }
            val hash = MessageDigest.getInstance("MD5").digest((query + key).toByteArray()).joinToString("") { "%02x".format(it) }
            return "$query&w_rid=$hash"
        }
    }
}
