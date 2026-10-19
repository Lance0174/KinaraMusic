/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.player

import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf

/** High-frequency clock is consumed only by the seek bar, lyrics and mini progress drawing. */
data class PlayerProgress(
    val positionMs: Long = 0, val durationMs: Long = 0,
    val sampledAtMs: Long = 0, val advancing: Boolean = false, val speed: Float = 1f,
    val mediaId: String? = null,
    /** Platform + platform-side id of the recording currently producing the samples. */
    val recordingIdentity: String? = null,
) {
    fun belongsTo(expectedMediaId: String?, expectedRecordingIdentity: String?): Boolean =
        (mediaId == null || mediaId == expectedMediaId) &&
            (recordingIdentity == null || expectedRecordingIdentity == null || recordingIdentity == expectedRecordingIdentity)

    /** Interpolate only within a bounded polling window; freeze on pause, buffering or stale samples. */
    fun estimatedPosition(nowMs: Long): Long {
        val delta = if (advancing && sampledAtMs > 0) ((nowMs - sampledAtMs).coerceIn(0, 1000) * speed).toLong() else 0L
        return (positionMs + delta).coerceIn(0, durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
    }
}
val LocalPlayerProgress = staticCompositionLocalOf<State<PlayerProgress>?> { null }

fun localPlaybackUri(path: String): android.net.Uri =
    if (path.startsWith("content://") || path.startsWith("file://")) android.net.Uri.parse(path)
    else android.net.Uri.fromFile(java.io.File(path))
