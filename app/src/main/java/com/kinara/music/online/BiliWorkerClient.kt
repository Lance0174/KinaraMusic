/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * B站 Worker 客户端。Worker 地址来自构建配置，不写入公开源码；
 * 本机构建可以提供逗号分隔的 HTTPS 地址，按配置顺序回退。
 * 全部失败按 TRANSPORT 处理（上层走"直连失败即跳过"降级，不阻塞其它源）。
 */
package com.kinara.music.online

import com.kinara.music.BuildConfig
import org.json.JSONObject
import java.net.URI

object BiliWorkerClient {

    /** The current APK's configured bases. Invalid or non-HTTPS values are ignored. */
    val BASES: List<String>
        get() = BuildConfig.BILI_WORKER_BASES.split(',')
            .mapNotNull(::validBase)
            .distinct()

    private fun validBase(raw: String): String? = runCatching {
        val value = raw.trim().trimEnd('/')
        if (value.isBlank()) return@runCatching null
        val uri = URI(value)
        value.takeIf {
            uri.scheme.equals("https", true) && uri.rawUserInfo == null &&
                uri.port in listOf(-1, 443) && uri.rawQuery == null &&
                uri.rawFragment == null && !uri.host.isNullOrBlank()
        }
    }.getOrNull()

    /** HTTP 故障也会回退；返回成功域名，解析与音频请求使用同一入口。 */
    private suspend fun request(path: String): Pair<String, JSONObject> {
        val bases = BASES
        if (bases.isEmpty()) throw TransportException(ResolveStage.NOT_CONFIGURED, "未配置 B站 Worker")
        var failure: Exception? = null
        for (base in bases) {
            try {
                val r = OnlineTransport.getText(base + path, timeoutMs = 12000)
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
                album = "视频", durationMs = v.optLong("duration") * 1000)
        }
    }

    suspend fun resolve(bvid: String): ResolvedUrl {
        return try {
            val (base, j) = request("/bili/resolve?bvid=" + OnlineTransport.enc(bvid))
            val path = j.optString("streamPath")
            if (path != "/bili/stream?bvid=" + OnlineTransport.enc(bvid)) {
                throw TransportException(ResolveStage.TRANSPORT, "音频代理响应不完整")
            }
            ResolvedUrl(ResolveStage.OK, base + path, viaProxy = true)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ResolvedUrl(ResolveStage.TRANSPORT, errorMessage = e.message ?: "B站解析失败")
        }
    }
}
