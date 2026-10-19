/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * LRC 歌词解析（纯函数）：多时间标签行展开、排序、当前行定位（二分）。
 * King-LRC-Waveform-Editor 的核心纪律：歌词按真实时间定位，不伪造。
 */
package com.kinara.music.online

data class LyricWord(val startMs: Long, val endMs: Long, val text: String)
data class LyricLine(
    val timeMs: Long, val text: String,
    val endMs: Long? = null,
    val words: List<LyricWord> = emptyList(),
    val translation: String? = null,
    val romanization: String? = null,
)
data class LyricDocument(
    val lines: List<LyricLine> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    // LRC convention: positive file offset advances lyrics. Already applied to lines/words.
    val fileOffsetMs: Long = 0,
)

object LrcParser {

    private val TAG = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    fun parse(lrc: String): List<LyricLine> = parseDocument(lrc).lines

    fun parseDocument(lrc: String): LyricDocument {
        if (lrc.isBlank()) return LyricDocument()
        val metadata = Regex("""(?im)^\[([a-z]+):([^\]\r\n]*)]""").findAll(lrc)
            .associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
        val offset = metadata["offset"]?.toLongOrNull()?.coerceIn(-600_000, 600_000) ?: 0L
        val out = ArrayList<LyricLine>(128)
        for (raw in lrc.lineSequence()) {
            val matches = TAG.findAll(raw).toList()
            if (matches.isEmpty()) continue
            val text = raw.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val mm = m.groupValues[1].toLong()
                val ss = m.groupValues[2].toLong()
                if (ss >= 60) continue
                val fracStr = m.groupValues[3]
                val frac = when (fracStr.length) {
                    0 -> 0L; 1 -> fracStr.toLong() * 100; 2 -> fracStr.toLong() * 10; else -> fracStr.take(3).toLong()
                }
                out.add(LyricLine(mm * 60_000 + ss * 1_000 + frac - offset, text))
            }
        }
        return LyricDocument(out.distinct().sortedBy { it.timeMs }, metadata, offset)
    }

    /** 当前行索引（二分）：position 所在行；无歌词返回 -1。 */
    fun currentIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}
