/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 文本 API 出口。媒体通过独立的受限 OkHttp 客户端加载。
 * 教训 E013：网络必须在 IO 上下文；异常归类为 ResolveStage，不静默吞掉。
 * 教训 E019/E020：调用方须自行核对"最终域名"协议，明文域名进 manifest 白名单。
 */
package com.kinara.music.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicReference

object OnlineTransport {

    /** Text APIs are expected to return small JSON/LRC documents; bound memory before decoding. */
    internal const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    private const val MAX_REDIRECTS = 3

    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 8000,
        followRedirects: Boolean = true,
    ): TransportResponse = getTextUsing(url, headers, timeoutMs, followRedirects) { it.openConnection() as HttpURLConnection }

    internal suspend fun getTextUsing(
        url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 8000,
        followRedirects: Boolean = true, openConnection: (URL) -> HttpURLConnection,
    ): TransportResponse = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<HttpURLConnection?>()
            // One handler covers the entire chain, including cancellation between redirects.
            continuation.invokeOnCancellation { active.getAndSet(null)?.disconnect() }
            val result = runCatching { request(url, headers, timeoutMs, followRedirects, openConnection) { connection ->
                active.set(connection)
                if (!continuation.isActive) {
                    connection.disconnect()
                    throw kotlinx.coroutines.CancellationException()
                }
            } }
            active.set(null)
            if (continuation.isActive) continuation.resumeWith(result)
        }
    }

    data class TransportResponse(val code: Int, val body: String, val headers: Map<String, List<String>> = emptyMap())

    private fun request(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int,
        followRedirects: Boolean,
        openConnection: (URL) -> HttpURLConnection,
        onConnection: (HttpURLConnection) -> Unit,
    ): TransportResponse {
        var current = validatedTextUrl(url)?.toURL()
            ?: throw TransportException(ResolveStage.TRANSPORT, "请求地址无效")
        var redirects = 0
        while (true) {
            var conn: HttpURLConnection? = null
            try {
                val connection = openConnection(current).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    requestMethod = "GET"
                    // Automatic redirects can carry Cookie or other caller headers to a new host.
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "Kinara/1.0 (Android; Music client)")
                    for ((k, v) in headers) setRequestProperty(k, v)
                }
                conn = connection
                onConnection(conn)
                val code = conn.responseCode
                if (followRedirects && code in setOf(301, 302, 303, 307, 308)) {
                    val location = conn.getHeaderField("Location")
                    if (location.isNullOrBlank()) {
                        val stream = conn.errorStream
                        val body = stream?.use { readResponseBody(it, connection.contentLengthLong) }.orEmpty()
                        return TransportResponse(code, body, responseHeaders(conn))
                    }
                    if (redirects++ >= MAX_REDIRECTS) throw TransportException(ResolveStage.TRANSPORT, "重定向次数过多")
                    val next = redirectedUrl(current.toString(), location)
                        ?: throw TransportException(ResolveStage.TRANSPORT, "重定向目标不受信任")
                    conn.disconnect()
                    conn = null
                    current = URL(next)
                    continue
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.use { readResponseBody(it, connection.contentLengthLong) }.orEmpty()
                return TransportResponse(code, body, responseHeaders(conn))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (e is TransportException) throw e
                // 分类：超时/IO 一律 TRANSPORT，由上层转 NO_RESULT 前先记录原因（不静默）
                throw TransportException(ResolveStage.TRANSPORT, "网络请求失败（${e.javaClass.simpleName}）")
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun responseHeaders(conn: HttpURLConnection): Map<String, List<String>> =
        conn.headerFields.entries.mapNotNull { (key, value) -> key?.let { it to value } }.toMap()

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

internal fun validatedTextUrl(value: String): URI? = runCatching {
    URI(value).takeIf { uri ->
        val scheme = uri.scheme?.lowercase()
        scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawFragment == null &&
            (uri.port == -1 || uri.port == if (scheme == "https") 443 else 80)
    }
}.getOrNull()

/** Caller headers stay on the same host; only the default-port HTTP to HTTPS upgrade is allowed. */
internal fun redirectedUrl(current: String, location: String): String? = runCatching {
    val source = validatedTextUrl(current) ?: return@runCatching null
    val target = validatedTextUrl(source.resolve(location).toString()) ?: return@runCatching null
    val sourceScheme = source.scheme?.lowercase() ?: return@runCatching null
    val targetScheme = target.scheme?.lowercase() ?: return@runCatching null
    if (sourceScheme !in setOf("http", "https") || targetScheme !in setOf("http", "https")) return@runCatching null
    if (sourceScheme == "https" && targetScheme != "https") return@runCatching null
    if (source.rawUserInfo != null || target.rawUserInfo != null || target.rawFragment != null) return@runCatching null
    if (source.host.isNullOrBlank() || !source.host.equals(target.host, true)) return@runCatching null
    target.toString()
}.getOrNull()

internal fun readResponseBody(stream: InputStream, declaredLength: Long = -1L): String {
    if (declaredLength > OnlineTransport.MAX_RESPONSE_BYTES) throw TransportException(ResolveStage.TRANSPORT, "响应体过大")
    val output = ByteArrayOutputStream(minOf(declaredLength.coerceAtLeast(0).toInt(), OnlineTransport.MAX_RESPONSE_BYTES))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        total += count
        if (total > OnlineTransport.MAX_RESPONSE_BYTES) throw TransportException(ResolveStage.TRANSPORT, "响应体过大")
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}

class TransportException(val stage: ResolveStage, message: String, cause: Throwable? = null) : RuntimeException(message, cause)
