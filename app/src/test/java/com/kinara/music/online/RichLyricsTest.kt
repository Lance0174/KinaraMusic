/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.online

import com.kinara.music.data.OnlineKey
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RichLyricsTest {
    @Test fun kuwoFractionalSecondsAreNotTruncatedOrReadFromLyricText() {
        val response = JSONObject("""{"data":{"lrclist":[{"time":"12.345","lineLyric":"第一行","timeLyric":"歌词"},{"time":"60.09","lineLyric":"第二行"}]}}""")
        assertEquals(listOf(12345L, 60090L), LrcParser.parse(KuwoSource.parseLyric(response)!!).map { it.timeMs })
    }
    @Test fun capturedOfficialYrcTimingKeepsAllLinesAndWords() {
        val fixture = javaClass.getResourceAsStream("/netease-yrc-timing.json")!!.bufferedReader().use { it.readText() }
        val lines = NeteaseSource.parseLyrics(JSONObject(fixture)).lines
        assertEquals(57, lines.size)
        assertEquals(558, lines.sumOf { it.words.size })
        assertTrue(lines.all { it.words.isNotEmpty() })
    }
    @Test fun neteaseLegacyTranslationAndRomanizationStayOnOriginalTimeline() {
        val response = JSONObject().put("code", 200)
            .put("lrc", JSONObject().put("lyric", "[offset:800]\n[00:01.100]A\n[00:01.900]B"))
            .put("tlyric", JSONObject().put("lyric", "[00:01.100]甲\n[00:01.900]乙"))
            .put("romalrc", JSONObject().put("lyric", "[00:01.100]a\n[00:01.900]b"))
        val doc = NeteaseSource.parseLyrics(response)
        assertEquals(listOf(300L, 1100L), doc.lines.map { it.timeMs })
        assertEquals(listOf("甲", "乙"), doc.lines.map { it.translation })
        assertEquals(listOf("a", "b"), doc.lines.map { it.romanization })
    }

    @Test fun yrcUsesItsOwnSideTracksAndDoesNotStealLrcTranslations() {
        val response = JSONObject().put("code", 200)
            .put("lrc", JSONObject().put("lyric", "[00:01.000]legacy"))
            .put("yrc", JSONObject().put("lyric", "[1000,1000](1000,500,0)real (1500,500,0)words"))
            .put("tlyric", JSONObject().put("lyric", "[00:01.000]wrong"))
            .put("ytlrc", JSONObject().put("lyric", "[00:01.000]真实歌词"))
        val line = NeteaseSource.parseLyrics(response).lines.single()
        assertEquals("real words", line.text)
        assertEquals(2, line.words.size)
        assertEquals("真实歌词", line.translation)
        response.put("yrc", JSONObject().put("lyric", "[1000,1000](0,999,0)bad"))
        assertEquals("legacy", NeteaseSource.parseLyrics(response).lines.single().text)
    }

    @Test fun offlineCacheSurvivesReopenAndIsIsolatedByPlatform() = runTest {
        val dir = File(RuntimeEnvironment.getApplication().filesDir, "lyrics-test-${System.nanoTime()}")
        val key = OnlineKey("netease", "123")
        val doc = LyricTiming.parseYrc("[1000,1000](1000,500,0)离(1500,500,0)线").let {
            it.copy(lines = it.lines.map { line -> line.copy(translation = "offline", romanization = "lixian") })
        }
        LyricsCache(dir).write(key, doc)
        assertEquals(doc, LyricsCache(dir).read(key.copy(bitrate = "flac")))
        assertNull(LyricsCache(dir).read(key.copy(platform = "qq")))
    }
}
