/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.player

/** Numeric, typed events prevent exception messages/URLs/tokens from reaching exported reports. */
object PlaybackDiagnostics {
    enum class Event { STATE, PLAYING, PLAY_WHEN_READY, SUPPRESSION, PLAYER_VOLUME, OUTPUT_GAIN,
        AUDIO_SESSION, ERROR, RETRY, FALLBACK, UNDERRUN, SYSTEM_VOLUME, SYSTEM_MODE, OUTPUT_TYPE, TIMER }
    private val entries = ArrayDeque<String>()
    @Synchronized fun record(event: Event, value: Number) {
        if (entries.size >= 200) entries.removeFirst()
        entries.addLast("${System.currentTimeMillis()} ${event.name}=$value")
    }
    @Synchronized fun snapshot(): String = "Kinara ${com.kinara.music.BuildConfig.VERSION_NAME}\nAndroid API ${android.os.Build.VERSION.SDK_INT}\n" + entries.joinToString("\n")
}

class PlaybackRetryGate {
    private var mediaId: String? = null
    private var attempted = false
    fun select(id: String?) { mediaId = id; attempted = false }
    fun take(id: String?): Boolean {
        if (id == null || id != mediaId || attempted) return false
        attempted = true
        return true
    }
}
