/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import com.kinara.music.data.KinaraPrefs
import com.kinara.music.data.Track
import com.kinara.music.data.stableDigest
import com.kinara.music.online.OnlineMediaUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.TreeSet

fun audioCacheKey(track: Track): String {
    val key = requireNotNull(track.onlineKey)
    // v1 could store a GD recording under a QQ/Kugou key. Never read those bytes as native audio.
    return "audio-v2-" + stableDigest("${key.platform}\n${key.id}\n${key.bitrate}")
}

/** Private persistent byte cache, before URL resolution, so fully cached tracks work offline. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class AudioCache internal constructor(context: Context, private val now: () -> Long = System::currentTimeMillis,
    directory: File = File(context.cacheDir, "audio-v1")) {
    private val evictor = BoundedEvictor(KinaraPrefs.get(context).settings().cacheLimitGb * 1_000_000_000L)
    private val cache = SimpleCache(directory, evictor, StandaloneDatabaseProvider(context))
    private val active = mutableMapOf<String, Int>()
    private val pendingClear = mutableSetOf<String>()
    private val _bytes = MutableStateFlow(cache.cacheSpace)
    val bytes: StateFlow<Long> = _bytes
    private var lastSweep = Long.MIN_VALUE

    fun maintain(limitGb: Int, clear: Boolean = false) = synchronized(cache) {
        evictor.limit = limitGb.coerceIn(1, 5) * 1_000_000_000L
        val time = now()
        if (clear || pendingClear.isNotEmpty() || lastSweep == Long.MIN_VALUE || time - lastSweep >= 60_000) {
        cache.keys.toList().forEach { key ->
            val created = cache.getContentMetadata(key).get(CREATED, time)
            if (clear || time - created >= AGE_MS || key in pendingClear) {
                if ((active[key] ?: 0) > 0) pendingClear += key
                else { cache.removeResource(key); pendingClear -= key }
            }
        }
        lastSweep = time
        }
        evictor.trim(cache)
        _bytes.value = cache.cacheSpace
    }

    fun source(upstream: DataSource.Factory, track: Track): DataSource {
        val key = audioCacheKey(track)
        return object : DataSource {
            private var opened = false
            private val delegate = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)
                .setCacheKeyFactory { key }.setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache).setFragmentSize(512 * 1024))
                .createDataSource()
            override fun addTransferListener(listener: TransferListener) = delegate.addTransferListener(listener)
            override fun open(spec: DataSpec): Long {
                synchronized(cache) {
                    maintain((evictor.limit / 1_000_000_000L).toInt())
                    active[key] = (active[key] ?: 0) + 1
                    opened = true
                    if (cache.getContentMetadata(key).get(CREATED, -1L) < 0)
                        cache.applyContentMetadataMutations(key, ContentMetadataMutations().set(CREATED, now()))
                }
                return try { delegate.open(spec.buildUpon().setKey(key).build()) } catch (e: Exception) { close(); throw e }
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate.read(buffer, offset, length)
            override fun getUri(): Uri? = delegate.uri
            override fun getResponseHeaders(): Map<String, List<String>> = delegate.responseHeaders
            override fun close() {
                try { delegate.close() } finally {
                    synchronized(cache) {
                        if (opened) { active[key] = ((active[key] ?: 1) - 1).coerceAtLeast(0); opened = false }
                        maintain((evictor.limit / 1_000_000_000L).toInt())
                    }
                }
            }
        }
    }
    internal fun release() { cache.release() }

    private class BoundedEvictor(@Volatile var limit: Long) : CacheEvictor {
        private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan>({ it.lastTouchTimestamp }, { it.key }, { it.position }))
        private var size = 0L
        override fun requiresCacheSpanTouches() = true
        override fun onCacheInitialized() = Unit
        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) = trim(cache, if (length < 0) 512 * 1024 else length)
        override fun onSpanAdded(cache: Cache, span: CacheSpan) { if (spans.add(span)) size += span.length; trim(cache) }
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) { if (spans.remove(span)) size -= span.length }
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            onSpanRemoved(cache, oldSpan); onSpanAdded(cache, newSpan)
        }
        fun trim(cache: Cache, incoming: Long = 0) {
            while (size + incoming > limit && spans.isNotEmpty()) cache.removeSpan(spans.first())
        }
    }

    companion object {
        const val AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val CREATED = "kinara-created"
        @Volatile private var instance: AudioCache? = null
        fun get(context: Context): AudioCache = instance ?: synchronized(this) {
            instance ?: AudioCache(context.applicationContext).also { instance = it }
        }
        /** Delays database/index creation until open(), which Media3 calls on its loading thread. */
        fun factory(context: Context, upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory {
            object : DataSource {
                private var delegate: DataSource? = null
                private val listeners = mutableListOf<TransferListener>()
                override fun addTransferListener(listener: TransferListener) { listeners += listener }
                override fun open(spec: DataSpec): Long {
                    val track = OnlineMediaUri.decode(spec.uri.toString())
                    val source = if (track == null) upstream.createDataSource() else get(context).source(upstream, track)
                    delegate = source; listeners.forEach(source::addTransferListener)
                    return source.open(spec)
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int) = delegate!!.read(buffer, offset, length)
                override fun getUri(): Uri? = delegate?.uri
                override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
                override fun close() { try { delegate?.close() } finally { delegate = null } }
            }
        }
    }
}
