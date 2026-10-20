/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.player

import com.kinara.music.download.audioExtension
import com.kinara.music.online.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private val probeClient by lazy {
    audioHttpClient.newBuilder().connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS).build()
}

/** A small GET tests actual audio bytes. HEAD and a resolve-API success cannot prove playability. */
internal suspend fun probeAudioUrl(url: String, client: OkHttpClient = probeClient): Boolean =
    suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", AUDIO_USER_AGENT)
            .header("Range", "bytes=0-63").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(false)
            }
            override fun onResponse(call: Call, response: Response) {
                val valid = try { response.use {
                    if (it.code !in listOf(200, 206)) return@use false
                    if (it.code == 206 && !it.header("Content-Range").orEmpty().startsWith("bytes 0-")) return@use false
                    val body = it.body ?: return@use false
                    val type = body.contentType()?.toString().orEmpty()
                    if (type.contains("html", true) || type.contains("json", true)) return@use false
                    val header = ByteArray(64)
                    val input = body.byteStream()
                    var count = 0
                    while (count < header.size) {
                        val read = input.read(header, count, header.size - count)
                        if (read < 0) break
                        count += read
                    }
                    audioExtension(header.copyOf(count)) != null
                } } catch (_: IOException) { false }
                if (continuation.isActive) continuation.resume(valid)
            }
        })
    }

/** Probe at most three equivalent CDN URLs together, close every response, keep only the winner. */
internal suspend fun verifiedAudio(result: ResolvedUrl): ResolvedUrl? {
    val candidates = runCatching { resolvedAudioCandidates(result) }.getOrNull() ?: return null
    val winner = preferredRace(candidates.take(3).map { url -> suspend {
        url.takeIf { probeAudioUrl(url) }
    } }, preferenceMs = 80) ?: return null
    return result.copy(url = winner, backupUrls = emptyList())
}
