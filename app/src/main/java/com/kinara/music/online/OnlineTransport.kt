/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 唯一 HTTP 出口（HttpURLConnection，零第三方依赖）。
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
import java.net.URL
import java.net.URLEncoder

object OnlineTransport {

    /** Text APIs are expected to return small JSON/LRC documents; bound memory before decoding. */
    internal const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    suspend fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 8000,
        followRedirects: Boolean = true,
    ): TransportResponse = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val result = runCatching { request(url, headers, timeoutMs, followRedirects) { connection ->
                continuation.invokeOnCancellation { connection.disconnect() }
                if (!continuation.isActive) throw kotlinx.coroutines.CancellationException()
            } }
            if (continuation.isActive) continuation.resumeWith(result)
        }
    }

    data class TransportResponse(val code: Int, val body: String, val headers: Map<String, List<String>> = emptyMap())

    private fun request(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int,
        followRedirects: Boolean,
        onConnection: (HttpURLConnection) -> Unit,
    ): TransportResponse {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = followRedirects
                setRequestProperty("User-Agent", "Kinara/1.0 (Android; Music client)")
                for ((k, v) in headers) setRequestProperty(k, v)
            }
            onConnection(conn)
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { readResponseBody(it, conn.contentLengthLong) }.orEmpty()
            TransportResponse(code, body, conn.headerFields.filterKeys { it != null })
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (e is TransportException) throw e
            // 分类：超时/IO 一律 TRANSPORT，由上层转 NO_RESULT 前先记录原因（不静默）
            throw TransportException(ResolveStage.TRANSPORT, "网络请求失败（${e.javaClass.simpleName}）")
        } finally {
            conn?.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

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
