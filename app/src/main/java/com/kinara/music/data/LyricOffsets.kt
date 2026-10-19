/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.data

import java.net.URLDecoder
import java.net.URLEncoder

/** Calibrations belong to the recording actually playing, independent of selected bitrate. */
fun lyricIdentity(track: Track?): String? = track?.onlineKey?.let { "${it.platform}:${it.id}" }
    ?: track?.mediaId?.let { "local:$it" }

fun KinaraSettings.lyricDelayFor(track: Track?): Long = lyricDelayMs + (lyricOffsets[lyricIdentity(track)] ?: 0L)

fun KinaraSettings.withLyricOffset(identity: String, offset: Long): KinaraSettings {
    val updated = LinkedHashMap(lyricOffsets)
    updated.remove(identity)
    if (offset != 0L) updated[identity] = offset.coerceIn(-10_000, 10_000)
    while (updated.size > 200) updated.remove(updated.keys.first())
    return copy(lyricOffsets = updated)
}

internal fun encodeLyricOffsets(offsets: Map<String, Long>): String = offsets.entries.toList().takeLast(200)
    .joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${it.value.coerceIn(-10_000, 10_000)}" }
internal fun parseLyricOffsets(raw: String): Map<String, Long> = buildMap {
    raw.take(100_000).split('&').takeLast(200).forEach { part ->
        val pair = part.split('=', limit = 2)
        if (pair.size == 2) runCatching {
            val key = URLDecoder.decode(pair[0], "UTF-8")
            val value = pair[1].toLongOrNull()?.coerceIn(-10_000, 10_000) ?: return@runCatching
            if (key.isNotBlank() && key.length <= 1024 && value != 0L) put(key, value)
        }
    }
}
