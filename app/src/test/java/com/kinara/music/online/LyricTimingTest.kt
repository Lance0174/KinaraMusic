/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.online

import com.kinara.music.player.PlayerProgress
import org.junit.Assert.*
import org.junit.Test

class LyricTimingTest {
    @Test fun fileOffsetAndUserDelayHaveExplicitInverseSeek() {
        val doc = LrcParser.parseDocument("[ti:Title]\n[offset:800]\n[00:02.000]Hello")
        assertEquals("Title", doc.metadata["ti"])
        val line = doc.lines.single()
        assertEquals(1200, line.timeMs)
        assertEquals(1500, LyricTiming.seek(line, 300))
        assertEquals(0, LrcParser.currentIndex(doc.lines, LyricTiming.position(1500, 300)))
        assertEquals(-1, LrcParser.currentIndex(doc.lines, LyricTiming.position(1499, 300)))
        assertEquals(0, LyricTiming.seek(line, -5000))
    }

    @Test fun translationsWithinOneSecondRemainAttachedToDifferentOriginals() {
        val base = LrcParser.parse("[00:01.100]A\n[00:01.900]B")
        val result = LyricTiming.attach(base, LrcParser.parse("[00:01.100]甲\n[00:01.900]乙"))
        assertEquals(listOf("A", "B"), result.map { it.text })
        assertEquals(listOf("甲", "乙"), result.map { it.translation })
        assertEquals(1, LrcParser.currentIndex(result, 1900))
    }

    @Test fun exactMatchesWinAndSideRowsAreNeverReusedOrCrossed() {
        val base = listOf(LyricLine(1000, "A"), LyricLine(1250, "B"), LyricLine(1500, "C"))
        val result = LyricTiming.attach(base, listOf(LyricLine(1250, "译文"), LyricLine(1125, "歧义"), LyricLine(8000, "错位")))
        assertEquals(listOf(null, "译文", null), result.map { it.translation })
    }

    @Test fun yrcUsesAbsoluteMillisecondsAndPreservesWhitespace() {
        val line = LyricTiming.parseYrc("[10000,2000](10000,500,0)Hello (10750,1250,0)world").lines.single()
        assertEquals("Hello world", line.text)
        assertEquals(10000, line.words.first().startMs)
        assertEquals(12000L, line.endMs)
        assertEquals(0.5f, LyricTiming.wordProgress(line.words.last(), 11375), 0.001f)
        assertEquals(0f, LyricTiming.wordProgress(line.words.last(), 10000), 0f)
        assertEquals(1f, LyricTiming.wordProgress(line.words.last(), 15000), 0f)
    }

    @Test fun invalidOrBackwardWordTimesFallBackToTextWithoutFakeTiming() {
        val lines = LyricTiming.parseYrc("[1000,1000](0,400,0)bad\n[2000,1000](2600,400,0)one(2200,500,0)two").lines
        assertEquals(listOf("bad", "onetwo"), lines.map { it.text })
        assertTrue(lines.all { it.words.isEmpty() })
        assertTrue(LrcParser.parse("[00:01.000]Plain words").single().words.isEmpty())
    }

    @Test fun observedOneMillisecondYrcOverlapDoesNotDiscardRealWordTiming() {
        // Official /lyric/v1 sample 1901371647 begins at 1ms, then 270ms; text is replaced.
        val line = LyricTiming.parseYrc("[1,810](1,270,0)甲(270,270,0)乙(540,270,0)丙").lines.single()
        assertEquals(3, line.words.size)
        assertEquals(270L, line.words[1].startMs)
    }

    @Test fun clockStopsForPauseAndStaleSamplesAndCorrectsOnSeek() {
        val sample = PlayerProgress(4000, 10_000, 1000, true)
        assertEquals(4250, sample.estimatedPosition(1250))
        assertEquals(5000, sample.estimatedPosition(10000))
        assertEquals(4000, sample.copy(advancing = false).estimatedPosition(2000))
        assertEquals(500, sample.copy(positionMs = 500, sampledAtMs = 2000).estimatedPosition(2000))
        assertEquals(10000, sample.copy(positionMs = 9900).estimatedPosition(1500))
        assertEquals(4500, sample.copy(speed = 2f).estimatedPosition(1250))
    }

    @Test fun responseBodyLimitRejectsOversizedTextBeforeDecoding() {
        val bytes = ByteArray(OnlineTransport.MAX_RESPONSE_BYTES + 1) { 'x'.code.toByte() }
        assertThrows(TransportException::class.java) {
            readResponseBody(java.io.ByteArrayInputStream(bytes))
        }
        assertEquals("ok", readResponseBody(java.io.ByteArrayInputStream("ok".toByteArray())))
    }

    @Test fun recordingIdentityRejectsSamplesFromAResolvedFallback() {
        val sample = PlayerProgress(4_000, 10_000, 1_000, true, mediaId = "stable-queue",
            recordingIdentity = "netease:42")
        assertTrue(sample.belongsTo("stable-queue", "netease:42"))
        assertFalse(sample.belongsTo("stable-queue", "qq:mid"))
        assertFalse(sample.belongsTo("other-queue", "netease:42"))
    }
}
