/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import com.kinara.music.data.Track
import com.kinara.music.online.OnlineMediaUri
import kotlinx.coroutines.*

/** Replacement keeps the library identity but gives Media3 a new URI, extractor and byte cache. */
fun fallbackMediaItem(original: MediaItem, actual: Track): MediaItem = original.buildUpon()
    .setUri(OnlineMediaUri.encode(actual))
    .setMediaMetadata(original.mediaMetadata.buildUpon().setExtras(Bundle(original.mediaMetadata.extras ?: Bundle()).apply {
        putString("kinara.playbackSource", actual.onlineKey?.platform)
        putString("kinara.playbackBitrate", actual.onlineKey?.bitrate)
        putString("kinara.actualTrack", com.kinara.music.data.encodeTrackLine(actual))
    }).build()).build()

/** Main-thread owner. Changing selection cancels work and rejects even a late, uncancellable result. */
class PlaybackFallback(private val scope: CoroutineScope,
    private val next: suspend (Track, Track) -> Track?) {
    private var generation = 0L
    private var pending: Job? = null
    fun reset() { generation++; pending?.cancel(); pending = null }
    fun start(original: Track, failed: Track, stillSelected: () -> Boolean, apply: (Track) -> Unit) {
        if (pending?.isActive == true) return
        val token = generation
        pending = scope.launch {
            val candidate = try { next(original, failed) } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }
            if (candidate != null && token == generation && stillSelected()) apply(candidate)
        }
    }
}
