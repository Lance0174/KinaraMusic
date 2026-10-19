/* Kinara — Android music client. Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.online

import com.kinara.music.data.OnlineKey
import com.kinara.music.data.SourceToggles
import java.net.URI

/** Search, filters and playback fallback share one order. */
val SOURCE_PRIORITY = listOf("netease", "qq", "kugou", "kuwo", "bilibili", "solara", "gd")
fun sourceRank(platform: String): Int = SOURCE_PRIORITY.indexOf(platform).takeIf { it >= 0 } ?: SOURCE_PRIORITY.size
fun SourceToggles.enabledInOrder(): List<String> = SOURCE_PRIORITY.filter(::isEnabled)
fun fallbackPlatforms(platform: String, toggles: SourceToggles): List<String> =
    toggles.enabledInOrder().filter { sourceRank(it) > sourceRank(platform) }

/** Error text shown in the UI must not contain response bodies, credentials or signed URLs. */
fun sourceErrorText(message: String?): String = when {
    message.isNullOrBlank() -> "该来源暂不可用，请稍后重试"
    message.contains("://") || message.contains("cookie", true) || message.contains("token", true) ||
        message.contains("SESSDATA", true) -> "连接失败，请检查网络后重试"
    else -> message.take(160)
}

fun SourceToggles.isEnabled(platform: String): Boolean = when (platform) {
    "netease" -> netease
    "kuwo" -> kuwo
    "kugou" -> kugou
    "qq" -> qq
    "bilibili" -> bilibili
    "solara" -> solara
    "gd" -> gdFallback
    else -> false
}

/** 只有网易云和 Solara 的网易云通道共享数字 ID；不可拿酷我 ID 播网易同号歌曲。 */
fun solaraFallbackId(key: OnlineKey): String? =
    key.id.takeIf { key.platform == "netease" && it.isNotBlank() && it.all(Char::isDigit) }

/** 网易 CDN 支持 HTTPS，统一在交给播放器前升级，避免放宽全局明文策略。 */
fun secureAudioUrl(url: String): String {
    val parsed = runCatching { URI(url) }.getOrNull() ?: return url
    val host = parsed.host?.lowercase() ?: return url
    return if (parsed.scheme.equals("http", true) &&
        (host == "music.126.net" || host.endsWith(".music.126.net"))) {
        "https" + url.substring(url.indexOf(':'))
    } else url
}

/** Keep version words and require the same credited artists; an uploader is not a singer. */
internal fun sameRecording(title: String, artist: String, otherTitle: String, otherArtist: String): Boolean {
    fun normalized(value: String) = value.lowercase().filter { it.isLetterOrDigit() }
    fun artists(value: String) = value.split('/', '&', '、', ',').map(::normalized).filter { it.isNotBlank() }.toSet()
    val credited = artists(artist)
    return normalized(title).isNotBlank() && normalized(title) == normalized(otherTitle) &&
        credited.isNotEmpty() && credited.none { it in setOf("未知艺术家", "未知歌手", "b站up主") } &&
        credited == artists(otherArtist)
}

internal fun sameRecording(track: com.kinara.music.data.Track, candidate: OnlineTrack): Boolean =
    sameRecording(track.title, track.artist, candidate.title, candidate.artist) &&
        (track.durationMs <= 0 || candidate.durationMs <= 0 || kotlin.math.abs(track.durationMs - candidate.durationMs) <= 5000)
