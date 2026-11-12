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
            // 只有"行首连续出现"的时间标签才代表同一句的多个时间点（[t1][t2]歌词）。
            // 原实现按"最后一个标签之后"取文本，有两个毛病：
            //   1) 少数 LRC 把标签写到行尾（"[00:01.00]歌词[00:02.00]"），取到空串就把整行丢掉，
            //      歌词凭空少一句；
            //   2) 行尾那个标签还会被当成时间点展开，同一句被重复计入。
            // 现在只认行首连续的标签，文本取其后剩余内容并剔除残留标签。
            var lead = matches.first().range.first
            for (m in matches) {
                if (m.range.first == lead) lead = m.range.last + 1 else break
            }
            val leading = matches.filter { it.range.last < lead }
            if (leading.isEmpty()) continue
            val text = raw.substring(lead).replace(TAG, "").trim()
            if (text.isEmpty()) continue
            for (m in leading) {
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
