/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.data

import com.kinara.music.online.*
import kotlinx.coroutines.*
import java.io.File
import java.net.URI
import java.security.MessageDigest

/** Display metadata has its own cache. It never changes audio IDs, URLs, duration or file tags. */
class TrackInfoRepository(
    private val directory: File,
    private val fetch: suspend (Track, KinaraSettings) -> OnlineTrack? = ::fetchTrackInfo,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun sync(track: Track, settings: KinaraSettings, online: Boolean, publish: (Track) -> Unit) {
        val file = File(directory, metadataCacheKey(track))
        val cached = withContext(Dispatchers.IO) {
            runCatching { file.takeIf { it.isFile && it.length() <= 32_768 }?.readText()?.let(::decodeTrackLine) }.getOrNull()
        }
        cached?.let { publish(applyDisplayInfo(track, it)) }
        val fresh = withContext(Dispatchers.IO) { cached != null && safeCoverUrl(cached.coverUrl) != null && now() - file.lastModified() in 0 until 7 * 86_400_000L }
        if (!settings.autoTrackInfo || !online || fresh) return
        val result = try { withTimeoutOrNull(10_000) { fetch(track, settings) } }
            catch (e: Exception) { if (e is CancellationException) throw e; null } ?: return
        // Downloaded files retain their original online ID; unidentified local files require strict matching.
        val sameId = track.onlineKey?.let { it.platform == result.platform && it.id == result.id } == true
        if (!sameId && !metadataMatch(track, result)) return
        if (result.title.isBlank() || result.artist.isBlank()) return
        val enriched = track.copy(title = result.title, artist = result.artist,
            album = result.album.takeIf(::knownAlbum) ?: track.album,
            coverUrl = safeCoverUrl(result.picUrl) ?: track.coverUrl)
        // A partial API response must not erase a previously useful image.
        val merged = if (enriched.coverUrl == null) enriched.copy(coverUrl = cached?.coverUrl) else enriched
        currentCoroutineContext().ensureActive()
        publish(merged)
        withContext(Dispatchers.IO) {
            runCatching {
                directory.mkdirs()
                val temp = File(directory, file.name + ".tmp")
                temp.writeText(encodeTrackLine(merged)); if (!temp.renameTo(file)) { file.writeText(temp.readText()); temp.delete() }
                file.setLastModified(now())
                directory.listFiles()?.filter { it.name.endsWith(".info") }?.sortedByDescending { it.lastModified() }
                    ?.drop(300)?.forEach { it.delete() }
            }
        }
    }
}

internal fun metadataCacheKey(track: Track): String {
    val identity = track.onlineKey?.let { "${it.platform}:${it.id}" }
        ?: "${track.mediaId}:${track.localPath}:${track.title}:${track.artist}:${track.durationMs}"
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) } + ".info"
}

internal fun knownAlbum(value: String) = value.isNotBlank() && value !in setOf("未知专辑", "<unknown>", "Unknown")
internal fun metadataMatch(track: Track, candidate: OnlineTrack): Boolean =
    track.durationMs > 0 && candidate.durationMs > 0 && sameRecording(track, candidate) &&
        (!knownAlbum(track.album) || track.album.trim().equals(candidate.album.trim(), true))

private val COVER_HOSTS = listOf(
    "music.126.net", "gtimg.cn", "gtimg.com", "qq.com", "kugou.com", "kuwo.cn",
    "hdslb.com", "bilibili.com", "bilivideo.com", "bilivideo.cn",
)

/** Only platform image CDNs are loaded; playlist data must not turn into an arbitrary URL fetch. */
internal fun safeCoverUrl(raw: String?): String? = raw?.trim()?.takeIf { it.isNotBlank() }?.let {
    val normalized = when {
        it.startsWith("//") -> "https:$it"
        it.startsWith("http://", true) -> "https://" + it.substringAfter("://")
        else -> it
    }
    normalized.takeIf { value -> runCatching { URI(value).let { uri ->
        val host = uri.host?.lowercase()
        uri.scheme.equals("https", true) && uri.rawUserInfo == null && uri.rawFragment == null &&
            uri.port in listOf(-1, 443) && !host.isNullOrBlank() &&
            COVER_HOSTS.any { domain -> host == domain || host.endsWith(".$domain") }
    } }.getOrDefault(false) }
}

internal fun applyDisplayInfo(original: Track, info: Track): Track = original.copy(
    title = info.title.ifBlank { original.title }, artist = info.artist.ifBlank { original.artist },
    album = info.album.takeIf(::knownAlbum) ?: original.album,
    coverUrl = safeCoverUrl(info.coverUrl) ?: original.coverUrl,
)

private suspend fun fetchTrackInfo(track: Track, settings: KinaraSettings): OnlineTrack? {
    val key = track.onlineKey
    if (key != null) {
        if (!settings.sources.isEnabled(key.platform)) return null
        if (key.platform == "netease") return NeteaseSource.detail(key.id)
        // Search is only a discovery step: accepting metadata still requires the exact platform ID.
        return OnlineRepository { settings.sources }.searchSource(key.platform, "${track.title} ${track.artist}", settings)
            .tracks.firstOrNull { it.platform == key.platform && it.id == key.id }
    }
    if (!settings.sources.netease || track.durationMs <= 0 || track.artist.isBlank() ||
        !sameRecording(track.title, track.artist, track.title, track.artist)) return null
    val candidates = NeteaseSource.search("${track.title} ${track.artist}").tracks.filter { metadataMatch(track, it) }.distinctBy { it.id }
    // Multiple matching releases are ambiguous; do not silently choose a different album image.
    val match = candidates.singleOrNull() ?: return null
    return NeteaseSource.detail(match.id)?.takeIf { metadataMatch(track, it) }
}
