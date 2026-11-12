/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.player

import android.content.Context
import com.kinara.music.data.Track
import com.kinara.music.data.decodeTrackLine
import com.kinara.music.data.encodeTrackLine

/** 冷启动要还原的最小状态。 */
internal data class SavedPlayback(val tracks: List<Track>, val index: Int, val positionMs: Long)

/**
 * "重进 App 继续上次播放"的落盘状态。
 *
 * 为什么不用 Media3 自带的会话状态：MediaSessionService 在进程被杀后没有持久化，
 * 冷启动连上 MediaController 时 mediaItemCount 恒为 0，连"上次放了什么"都无从得知。
 * 这里只存最小集合（队列曲目行、当前下标、播放位置）。
 *
 * 注意：**在线曲目的直链不入库**。CDN 地址会过期，恢复时必须重新解析，
 * 存了也只会在下次启动时立刻失效并弹一个播放错误。
 */
internal object PlaybackPersistence {
    private const val FILE = "kinara_playback_state"
    private const val KEY_QUEUE = "queue"
    private const val KEY_INDEX = "index"
    private const val KEY_POSITION = "position"
    /** 队列过长时只保留前若干首，避免一次写入几百行拖慢主线程。 */
    private const val MAX_TRACKS = 500

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(context: Context, tracks: List<Track>, index: Int, positionMs: Long) {
        if (tracks.isEmpty() || index !in tracks.indices) return
        val kept = tracks.take(MAX_TRACKS)
        // encodeTrackLine 已经把换行转义成 ESC_CHAR+'n'，所以直接用换行分隔是安全的。
        val encoded = kept.joinToString("\n") { encodeTrackLine(it) }
        prefs(context).edit()
            .putString(KEY_QUEUE, encoded)
            .putInt(KEY_INDEX, index.coerceIn(0, kept.lastIndex))
            .putLong(KEY_POSITION, positionMs.coerceAtLeast(0))
            .apply()
    }

    fun load(context: Context): SavedPlayback? {
        val p = prefs(context)
        val raw = p.getString(KEY_QUEUE, null)?.takeIf { it.isNotBlank() } ?: return null
        // 单行损坏只丢这一首，不能让整条队列失效。
        val tracks = raw.split("\n").mapNotNull(::safeDecode)
        if (tracks.isEmpty()) return null
        return SavedPlayback(tracks, p.getInt(KEY_INDEX, 0).coerceIn(tracks.indices),
            p.getLong(KEY_POSITION, 0L).coerceAtLeast(0))
    }

    fun clear(context: Context) { prefs(context).edit().clear().apply() }

    private fun safeDecode(line: String): Track? = runCatching { decodeTrackLine(line) }.getOrNull()
}
