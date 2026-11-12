/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.*
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.kinara.music.account.BiliApi
import com.kinara.music.data.*
import com.kinara.music.online.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URI

/** 本音源超过这个时长仍没给出可用地址，就并行去问其他音源（对冲请求）。 */
private const val HEDGE_DELAY_MS = 700L
/** 已有可用结果后，为等优先级更高的音源再多等一会儿的上限。 */
private const val HEDGE_PREFERENCE_MS = 600L

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
    if (url.rawUserInfo != null || url.rawFragment != null || url.scheme !in listOf("http", "https") ||
        (url.port != -1 && url.port != if (url.scheme == "https") 443 else 80) || !allowedAudioHost(host)) throw IOException("音频地址不受信任")
    if (url.scheme == "https") return listOf(value)
    val upgraded = "https://$host" + url.rawPath.orEmpty() + (url.rawQuery?.let { "?$it" } ?: "")
    val httpAllowed = listOf("music.126.net", "kuwo.cn", "kugou.com", "qq.com", "gtimg.com")
        .any { host == it || host.endsWith(".$it") }
    return if (httpAllowed) listOf(upgraded, value) else listOf(upgraded)
}

internal fun resolvedAudioCandidates(result: ResolvedUrl): List<String> {
    val primary = result.url?.takeIf { result.stage == ResolveStage.OK && it.isNotBlank() }
        ?: throw AudioResolutionException(sourceErrorText(result.errorMessage))
    return (audioCandidates(primary) + result.backupUrls.flatMap {
        runCatching { audioCandidates(it) }.getOrDefault(emptyList())
    }).distinct()
}

internal val audioHttpClient by lazy {
    mediaHttpClient({ runCatching { it in audioCandidates(it) }.getOrDefault(false) }, ::audioRequestHeaders)
        .newBuilder().connectTimeout(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
        .readTimeout(4, java.util.concurrent.TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
}

internal fun audioRequestHeaders(url: String): Map<String, String> =
    BiliApi.playbackHeaders(url) + BiliWorkerClient.headers(url)

fun interface AudioUrlAccess { suspend fun resolve(track: Track): List<String> }

class AudioResolver(private val context: Context) : AudioUrlAccess {
    private data class Entry(val at: Long, val urls: List<String>)
    /** actual 为 null 表示"就是本曲目本身"，非 null 表示竞速赢家来自另一个音源。 */
    private data class Resolution(val candidates: List<String>, val actual: Track?)
    private val urls = object : LinkedHashMap<String, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > 64
    }
    private val prefs = KinaraPrefs.get(context)
    private val playback = OnlinePlayback(OnlineRepository { prefs.settings().sources }, ::verifiedAudio)
    /** 对冲阶段已经找到、但必须由换源路径消费的跨源结果（见 resolve 的说明）。 */
    @Volatile private var pendingCross: Track? = null
    fun invalidate() = synchronized(urls) { urls.clear(); pendingCross = null }

    private fun cacheId(track: Track, settings: KinaraSettings): String {
        val key = requireNotNull(track.onlineKey)
        return "${key.platform}:${key.id}:${key.bitrate}:${settings.sources.hashCode()}:${settings.solaraBaseUrl}:${settings.solaraSecret.hashCode()}"
    }

    fun prime(track: Track, result: ResolvedUrl): Boolean {
        val values = runCatching { resolvedAudioCandidates(result) }.getOrNull() ?: return false
        primeUrls(track, values)
        return true
    }

    suspend fun next(original: Track, failed: Track, attempts: PlaybackAttempts): Track? {
        if (!NetworkState.isOnline(context)) return null
        // 首播对冲期间可能已经替我们找到更好的来源，直接交出去，别把同样的搜索再做一遍。
        pendingCross?.let { cross ->
            pendingCross = null
            if (cross.mediaId != failed.mediaId) return cross
        }
        var cursor = failed
        while (true) {
            val (result, track) = playback.resolveNextForPlay(original, cursor, prefs.settings(), attempts) ?: return null
            if (prime(track, result)) return track
            cursor = track
        }
    }

    /** Warm only the next item's own source; never change recording identity or fetch the queue. */
    suspend fun warm(track: Track) {
        val key = track.onlineKey ?: return
        val settings = prefs.settings().copy(preferredBitrate = key.bitrate)
        if (!settings.sources.isEnabled(key.platform) || !NetworkState.isOnline(context)) return
        val id = cacheId(track, settings)
        val now = android.os.SystemClock.elapsedRealtime()
        if (synchronized(urls) { urls[id] }?.let { now - it.at in 0 until 90_000 } == true) return
        val values = withTimeoutOrNull(5000) { candidatesOf { playback.resolveForPlay(track, settings).first } } ?: return
        synchronized(urls) { urls[id] = Entry(android.os.SystemClock.elapsedRealtime(), values) }
        PlaybackDiagnostics.record(PlaybackDiagnostics.Event.NEXT_SOURCE_READY, 1)
    }

    override suspend fun resolve(track: Track): List<String> {
        val started = android.os.SystemClock.elapsedRealtime()
        return try { resolveTrack(track) } finally {
            PlaybackDiagnostics.record(PlaybackDiagnostics.Event.RESOLVE_MS, android.os.SystemClock.elapsedRealtime() - started)
        }
    }

    private suspend fun resolveTrack(track: Track): List<String> {
        val key = track.onlineKey ?: throw IOException("缺少在线曲目标识")
        val settings = prefs.settings().copy(preferredBitrate = key.bitrate)
        if (!settings.sources.isEnabled(key.platform)) throw AudioResolutionException("该音源已关闭")
        if (!NetworkState.isOnline(context)) throw AudioResolutionException("未缓存部分需要联网")
        val id = cacheId(track, settings)
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(urls) { urls[id] }?.takeIf { now - it.at in 0 until 120_000 }?.let { return it.urls }

        // 旧实现首播只解析本曲所属音源：该源抽风时要先耗完 4s 解析预算 + 3s 校验超时，
        // 才由 Media3 抛错、再异步换源，最坏十几秒不出声——这就是"加载缓慢 / 没有音源可播"。
        // 现在改成对冲（hedged）：本音源先行，HEDGE_DELAY_MS 内没给出可播地址就并行去问其他音源。
        val resolution = preferredRace(
            listOf(
                { candidatesOf { playback.resolveForPlay(track, settings).first }?.let { Resolution(it, null) } },
                { delay(HEDGE_DELAY_MS); crossSourceCandidates(track, settings) }
            ),
            preferenceMs = HEDGE_PREFERENCE_MS,
        ) ?: throw AudioResolutionException("该来源暂不可用")

        val actual = resolution.actual
        if (actual == null) {
            pendingCross = null
            synchronized(urls) { urls[id] = Entry(now, resolution.candidates) }
            return resolution.candidates
        }
        // 跨源胜出时不能在这里直接播：字节缓存键 audioCacheKey(track) 来自 MediaItem 里的原曲目
        // （AudioCache.kt:19-23 用 OnlineMediaUri.decode(spec.uri)），把别的平台的录音写进这首歌的
        // 键下，之后 7 天内重播都会串味。所以这里只"提前把答案备好"，随即快速失败，
        // 让 Media3 立刻进入既有换源路径：fallbackMediaItem 会把 URI/extras/缓存键都换成实际曲目，
        // 而 next() 直接用我们备好的答案，不再重复搜索。最坏首播由十几秒降到 2~3 秒，且不串味。
        primeUrls(actual, resolution.candidates)
        pendingCross = actual
        throw AudioResolutionException("正在尝试其他来源")
    }

    /** 解析 + 可用性校验 + 候选列表；失败一律归为 null，交由竞速决定。 */
    private suspend fun candidatesOf(block: suspend () -> ResolvedUrl): List<String>? = try {
        val result = block()
        val checked = if (result.stage == ResolveStage.OK) verifiedAudio(result) else null
        if (checked == null) null else resolvedAudioCandidates(checked)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }

    /**
     * 其他音源上的同一首歌。复用 resolveNextForPlay 现有的并行竞速、录音一致性过滤与去重；
     * 它返回的地址已经过 verifiedAudio 校验，因此这里不再重复探测（重复探测会白花 1-3 秒）。
     */
    private suspend fun crossSourceCandidates(track: Track, settings: KinaraSettings): Resolution? {
        val found = playback.resolveNextForPlay(track, track, settings, PlaybackAttempts()) ?: return null
        val values = try {
            resolvedAudioCandidates(found.first)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
        return Resolution(values, found.second)
    }

    private fun primeUrls(track: Track, values: List<String>) {
        synchronized(urls) { urls[cacheId(track, prefs.settings())] = Entry(android.os.SystemClock.elapsedRealtime(), values) }
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
            val http = OkHttpDataSource.Factory(audioHttpClient).setUserAgent(AUDIO_USER_AGENT)
                .setContentTypePredicate { it == null || (!it.contains("text/html", true) && !it.contains("json", true)) }
            val source = sourceFactory?.invoke() ?: DefaultDataSource.Factory(context, http).createDataSource()
            listeners.forEach(source::addTransferListener)
            delegate = source
            try {
                return source.open(spec.withUri(Uri.parse(candidate)).withRequestHeaders(audioRequestHeaders(candidate)))
            } catch (e: IOException) { failure = e; runCatching { source.close() }; delegate = null }
        }
        throw IOException(if (track?.onlineKey?.platform == "bilibili") "B站音频连接失败，当前 CDN 地址不可用" else "音频连接失败", failure)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate!!.read(buffer, offset, length)
    override fun getUri(): Uri? = delegate?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
    override fun close() { try { delegate?.close() } finally { delegate = null } }
}
