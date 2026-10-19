/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SleepTimerState(val deadline: Long = 0, val afterTrack: Boolean = false) {
    val active get() = deadline > 0 || afterTrack
    fun remaining(now: Long) = (deadline - now).coerceAtLeast(0)
}

/** Owned by the playback service, independent of the Activity and wall-clock changes. */
class SleepTimer(private val pause: () -> Unit, private val now: () -> Long = SystemClock::elapsedRealtime) {
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { cancel(); pause() }
    fun set(minutes: Int, afterTrack: Boolean) {
        cancel()
        if (afterTrack) current.value = SleepTimerState(afterTrack = true)
        else if (minutes in 1..240) {
            val delay = minutes * 60_000L
            current.value = SleepTimerState(now() + delay)
            handler.postDelayed(expire, delay)
        }
        PlaybackDiagnostics.record(PlaybackDiagnostics.Event.TIMER, if (afterTrack) -1 else minutes)
    }
    fun trackEnded() { if (current.value.afterTrack) { cancel(); pause() } }
    fun cancel() { handler.removeCallbacks(expire); current.value = SleepTimerState() }
    companion object {
        private val current = MutableStateFlow(SleepTimerState())
        val state: StateFlow<SleepTimerState> = current
        const val COMMAND = "kinara.sleep_timer"
    }
}
