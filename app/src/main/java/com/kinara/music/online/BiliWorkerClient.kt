/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * B站 Worker 客户端。Worker 地址来自构建配置，不写入公开源码；
 * 本机构建可以提供逗号分隔的 HTTPS 地址，按配置顺序回退。
 * Cookie 仅由服务端保管；失败后由上层尝试公开请求及其他歌曲来源。
 */
package com.kinara.music.online

import com.kinara.music.BuildConfig
import org.json.JSONObject
import java.net.URI

object BiliWorkerClient {

    /** The current APK's configured bases. Invalid or non-HTTPS values are ignored. */
    val BASES: List<String>
        get() = BuildConfig.BILI_WORKER_BASES.split(',')
            .mapNotNull(::normalizeBase)
            .distinct()

    fun headers(url: String): Map<String, String> = workerHeaders(url, BASES, BuildConfig.BILI_WORKER_TOKEN)

    internal fun normalizeBase(raw: String): String? = runCatching {
        val value = raw.trim()
        if (value.isBlank()) return@runCatching null
        val uri = URI(value)
        value.takeIf {
            uri.scheme.equals("https", true) && uri.rawUserInfo == null &&
                uri.port in listOf(-1, 443) && uri.rawQuery == null &&
                uri.rawFragment == null && uri.rawPath in listOf("", "/") && !uri.host.isNullOrBlank()
        }?.removeSuffix("/")
    }.getOrNull()

    /** HTTP 故障也会回退；返回成功域名，解析与音频请求使用同一入口。 */
    private suspend fun request(path: String): Pair<String, JSONObject> {
        val bases = BASES
        if (bases.isEmpty()) throw TransportException(ResolveStage.NOT_CONFIGURED, "未配置 B站 Worker")
        var failure: Exception? = null
        for (base in bases) {
            try {
                val r = OnlineTransport.getText(base + path, headers(base + path), timeoutMs = 12000, followRedirects = false)
                if (r.code != 200) {
                    val reason = runCatching { JSONObject(r.body).optString("error") }.getOrNull()
                    throw TransportException(ResolveStage.TRANSPORT, reason?.takeIf { it.isNotBlank() }
                        ?: "B站代理 HTTP ${r.code}")
                }
                return base to JSONObject(r.body)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (failure == null) failure = e
            }
        }
        throw failure ?: TransportException(ResolveStage.TRANSPORT, "B站代理不可达")
    }

    suspend fun search(keyword: String): List<OnlineTrack> {
        val (_, j) = request("/bili/search?kw=" + OnlineTransport.enc(keyword))
        val arr = j.optJSONArray("result") ?: throw TransportException(ResolveStage.TRANSPORT, "搜索响应不完整")
        return arr.objects().map { v ->
            OnlineTrack(platform = "bilibili", id = v.optString("bvid") + "|",
                title = v.optString("title"), artist = v.optString("author").ifBlank { "B站UP主" },
                album = "视频", durationMs = v.optLong("duration") * 1000,
                picUrl = v.optString("pic").let { if (it.startsWith("//")) "https:$it" else it })
        }
    }

    suspend fun resolve(bvid: String, cid: String = "", bitrate: String = "320"): ResolvedUrl {
        return try {
            val query = "bvid=" + OnlineTransport.enc(bvid) +
                (if (cid.isNotBlank()) "&cid=" + OnlineTransport.enc(cid) else "") + "&br=" + OnlineTransport.enc(bitrate)
            val (base, j) = request("/bili/resolve?$query")
            val path = j.optString("streamPath")
            val expected = "/bili/stream?$query"
            if (path != expected && !path.matches(Regex(Regex.escape(expected) + "&expires=[0-9]{10}&ticket=[a-f0-9]{64}"))) {
                throw TransportException(ResolveStage.TRANSPORT, "音频代理响应不完整")
            }
            ResolvedUrl(ResolveStage.OK, base + path, viaProxy = true)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ResolvedUrl(ResolveStage.TRANSPORT, errorMessage = e.message ?: "B站解析失败")
        }
    }
}

/** Never send the private service key to a redirect, CDN, lookalike host or unrelated path. */
internal fun workerHeaders(url: String, bases: List<String>, token: String): Map<String, String> = runCatching {
    val uri = URI(url)
    if (token.isBlank() || token.any { it == '\r' || it == '\n' } || uri.rawUserInfo != null || uri.rawFragment != null ||
        uri.scheme != "https" || uri.port !in listOf(-1, 443) || !uri.path.startsWith("/bili/") ||
        bases.none { URI(it).host.equals(uri.host, ignoreCase = true) } ||
        (uri.path == "/bili/stream" && uri.rawQuery.orEmpty().contains("&ticket="))) emptyMap()
    else mapOf("X-Kinara-Key" to token)
}.getOrDefault(emptyMap())
