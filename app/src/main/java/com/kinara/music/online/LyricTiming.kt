/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0.
 * Native lyric timing; independently implemented, no synthesized word timestamps.
 */
package com.kinara.music.online

import kotlin.math.abs

object LyricTiming {
    private val lineTag = Regex("""^\[(\d+),(\d+)]""")
    private val wordTag = Regex("""\((\d+),(\d+),\d+\)""")

    /** YRC word starts are absolute milliseconds, not offsets within the line. */
    fun parseYrc(yrc: String): LyricDocument {
        val metadata = LrcParser.parseDocument(yrc)
        val offset = metadata.fileOffsetMs
        val lines = yrc.lineSequence().mapNotNull { raw ->
            val line = lineTag.find(raw) ?: return@mapNotNull null
            val start = line.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val duration = line.groupValues[2].toLongOrNull()?.takeIf { it in 1..600_000 } ?: return@mapNotNull null
            if (start !in 0..86_400_000) return@mapNotNull null
            val tags = wordTag.findAll(raw).toList()
            val words = tags.mapIndexedNotNull { index, tag ->
                val time = tag.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
                val length = tag.groupValues[2].toLongOrNull() ?: return@mapIndexedNotNull null
                val text = raw.substring(tag.range.last + 1, tags.getOrNull(index + 1)?.range?.first ?: raw.length)
                if (time !in start..(start + duration) || length < 0 || length > duration || time + length > start + duration)
                    return@mapIndexedNotNull null
                LyricWord(time - offset, time + length - offset, text)
            }
            val text = raw.substring(line.range.last + 1).replace(wordTag, "")
            if (text.isBlank()) return@mapNotNull null
            val timed = words.takeIf { it.size == tags.size && it.isNotEmpty() &&
                it.joinToString("") { w -> w.text } == text && it.zipWithNext().all { (a, b) -> a.startMs <= b.startMs } }.orEmpty()
            LyricLine(start - offset, text, start + duration - offset, timed)
        }.sortedBy { it.timeMs }.toList()
        return LyricDocument(lines, metadata.metadata, offset)
    }

    /** Exact millisecond matches first, then bounded nearest matches; each side row is used once. */
    fun attach(base: List<LyricLine>, side: List<LyricLine>, romanization: Boolean = false): List<LyricLine> {
        val remaining = side.indices.toMutableSet()
        val matches = mutableMapOf<Int, Int>()
        for (i in base.indices) {
            val exact = remaining.firstOrNull { side[it].timeMs == base[i].timeMs } ?: continue
            matches[i] = exact; remaining.remove(exact)
        }
        for (i in base.indices.filterNot(matches::containsKey)) {
            val nearest = remaining.minByOrNull { abs(side[it].timeMs - base[i].timeMs) } ?: continue
            // Never cross the midpoint into another original line.
            val distance = abs(side[nearest].timeMs - base[i].timeMs)
            if (distance <= 350 && base.indices.none { it != i && abs(side[nearest].timeMs - base[it].timeMs) <= distance }) {
                matches[i] = nearest; remaining.remove(nearest)
            }
        }
        return base.mapIndexed { i, line ->
            val text = matches[i]?.let { side[it].text.trim() }?.takeIf { it.isNotBlank() && it != line.text.trim() }
            if (romanization) line.copy(romanization = text) else line.copy(translation = text)
        }
    }

    // User delay is intentionally named differently from LRC offset: + delays display.
    fun position(audioMs: Long, delayMs: Long): Long = audioMs - delayMs
    fun seek(line: LyricLine, delayMs: Long): Long = (line.timeMs + delayMs).coerceAtLeast(0)
    fun wordProgress(word: LyricWord, positionMs: Long): Float = if (word.endMs <= word.startMs)
        if (positionMs >= word.startMs) 1f else 0f
    else ((positionMs - word.startMs).toFloat() / (word.endMs - word.startMs)).coerceIn(0f, 1f)
}
