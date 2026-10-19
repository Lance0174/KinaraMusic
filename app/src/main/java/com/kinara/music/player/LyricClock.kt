/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.player

import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kinara.music.data.lyricIdentity
import kotlinx.coroutines.isActive

/** One audio-derived clock for every lyric renderer. Reduced motion must not reduce timing accuracy. */
@Composable
fun rememberLyricClock(player: PlayerUiState): State<Long> {
    val samples = LocalPlayerProgress.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mediaIdentity = player.current?.mediaId
    val recordingIdentity = lyricIdentity(player.actualTrack ?: player.current)
    fun read(): Long = samples?.value?.let {
        if (!it.belongsTo(mediaIdentity, recordingIdentity)) 0L
        else it.estimatedPosition(SystemClock.elapsedRealtime())
    } ?: player.positionMs
    val position = remember(mediaIdentity, recordingIdentity) { mutableLongStateOf(read()) }
    LaunchedEffect(samples, lifecycle, mediaIdentity, recordingIdentity, player.playing, player.buffering, player.positionMs) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (player.playing && !player.buffering && samples != null) {
                while (isActive) withFrameNanos { position.longValue = read() }
            } else snapshotFlow { read() }.collect { position.longValue = it }
        }
    }
    return position
}
