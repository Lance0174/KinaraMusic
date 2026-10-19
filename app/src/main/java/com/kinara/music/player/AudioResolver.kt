/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.*
import com.kinara.music.BuildConfig
import com.kinara.music.account.BiliApi
import com.kinara.music.account.BiliSession
import com.kinara.music.data.*
import com.kinara.music.online.*
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.URI

internal const val AUDIO_USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

private val AUDIO_HOSTS = listOf(
    "music.126.net", "kuwo.cn", "kugou.com", "qq.com", "gtimg.com",
    "bilivideo.com", "bilivideo.cn",
)

private fun allowedAudioHost(host: String): Boolean = host in BiliWorkerClient.BASES.mapNotNull {
    runCatching { URI(it).host?.lowercase() }.getOrNull()
}.toSet() ||
    AUDIO_HOSTS.any { host == it || host.endsWith(".$it") }

/** Only audio URLs receive a transport fallback; account/API calls retain their HTTPS policy. */
fun audioCandidates(value: String): List<String> {
    val url = runCatching { URI(value) }.getOrNull() ?: throw IOException("音频地址无效")
    val host = url.host?.lowercase() ?: throw IOException("音频地址无效")
    if (url.rawUserInfo != null || url.scheme !in listOf("http", "https") ||
        url.port !in listOf(-1, 80, 443) || !allowedAudioHost(host)) throw IOException("音频地址不受信任")
    if (url.scheme == "https") return listOf(value)
    val upgraded = "https" + value.substring(value.indexOf(':'))
    val httpAllowed = listOf("music.126.net", "kuwo.cn", "kugou.com", "qq.com", "gtimg.com")
        .any { host == it || host.endsWith(".$it") }
    return if (httpAllowed) listOf(upgraded, value) else listOf(upgraded)
}

fun interface AudioUrlAccess { suspend fun resolve(track: Track): List<String> }

class AudioResolver(private val context: Context) : AudioUrlAccess {
    private data class Entry(val at: Long, val urls: List<String>)
    private val urls = object : LinkedHashMap<String, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 64
    }
    private val prefs = KinaraPrefs.get(context)
    private val playback = OnlinePlayback(OnlineRepository { prefs.settings().sources })
    fun invalidate() = synchronized(urls) { urls.clear() }

    private fun cacheId(track: Track, settings: KinaraSettings): String {
        val key = requireNotNull(track.onlineKey)
        return "${key.platform}:${key.id}:${key.bitrate}:${settings.sources.hashCode()}:${settings.solaraBaseUrl}:${settings.solaraSecret.hashCode()}:${BiliSession.revision}"
    }

    fun prime(track: Track, result: ResolvedUrl) {
        if (result.stage != ResolveStage.OK || result.url.isNullOrBlank()) return
        val values = (listOf(result.url) + result.backupUrls).flatMap(::audioCandidates).distinct()
        synchronized(urls) { urls[cacheId(track, prefs.settings())] = Entry(android.os.SystemClock.elapsedRealtime(), values) }
    }

    suspend fun next(original: Track, failed: Track): Track? {
        if (!NetworkState.isOnline(context)) return null
        val (result, track) = playback.resolveNextForPlay(original, failed, prefs.settings()) ?: return null
        prime(track, result)
        return track
    }

    override suspend fun resolve(track: Track): List<String> {
        val key = track.onlineKey ?: throw IOException("缺少在线曲目标识")
        val settings = prefs.settings().copy(preferredBitrate = key.bitrate)
        if (!settings.sources.isEnabled(key.platform)) throw IOException("该音源已关闭")
        if (!NetworkState.isOnline(context)) throw IOException("未缓存部分需要联网")
        val id = cacheId(track, settings)
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(urls) { urls[id] }?.takeIf { now - it.at in 0 until 120_000 }?.let { return it.urls }
        val result = playback.resolveForPlay(track, settings).first
        val raw = result.url?.takeIf { result.stage == ResolveStage.OK && it.isNotBlank() }
            ?: throw AudioResolutionException(sourceErrorText(result.errorMessage))
        val choices = (listOf(raw) + result.backupUrls).flatMap(::audioCandidates).distinct()
        synchronized(urls) { urls[id] = Entry(now, choices) }
        return choices
    }
}

class AudioResolutionException(message: String) : IOException(message)

/** Runs entirely on Media3's loader thread. Never retains credentials in DataSpec or cache keys. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ResolvedAudioSource(private val context: Context, private val resolver: AudioUrlAccess,
    private val sourceFactory: (() -> DataSource)? = null) : DataSource {
    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()
    override fun addTransferListener(listener: TransferListener) { listeners += listener; delegate?.addTransferListener(listener) }
    override fun open(spec: DataSpec): Long {
        val track = OnlineMediaUri.decode(spec.uri.toString())
        val candidates = if (track == null) listOf(spec.uri.toString()) else runBlocking { resolver.resolve(track) }
        var failure: IOException? = null
        for (candidate in candidates) {
            val http = DefaultHttpDataSource.Factory().setUserAgent(AUDIO_USER_AGENT)
                .setConnectTimeoutMs(12_000).setReadTimeoutMs(15_000)
                .setContentTypePredicate { it == null || (!it.contains("text/html", true) && !it.contains("json", true)) }
            val source = sourceFactory?.invoke() ?: DefaultDataSource.Factory(context, http).createDataSource()
            listeners.forEach(source::addTransferListener)
            delegate = source
            try {
                return source.open(spec.withUri(Uri.parse(candidate)).withRequestHeaders(BiliApi.playbackHeaders(candidate)))
            } catch (e: IOException) { failure = e; runCatching { source.close() }; delegate = null }
        }
        throw IOException(if (track?.onlineKey?.platform == "bilibili") "B站音频连接失败，当前 CDN 地址不可用" else "音频连接失败", failure)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate!!.read(buffer, offset, length)
    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
    override fun close() { try { delegate?.close() } finally { delegate = null } }
}
