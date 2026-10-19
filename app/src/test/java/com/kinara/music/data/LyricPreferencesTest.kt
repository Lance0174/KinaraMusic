package com.kinara.music.data

import org.junit.Assert.*
import org.junit.Test

class LyricPreferencesTest {
    @Test fun oldPagesMigrateWithoutMakingTheRendererAPage() {
        val dynamic = parseSettings { if (it == "player_visual_mode") "DYNAMIC" else null }
        assertEquals(PlayerVisualMode.LYRICS, dynamic.playerVisualMode)
        assertEquals(LyricRenderer.GLOW, dynamic.lyricRenderer)
        val classic = parseSettings { if (it == "player_visual_mode") "LYRICS" else null }
        assertEquals(LyricRenderer.CLASSIC, classic.lyricRenderer)
        val selected = dynamic.copy(lyricRenderer = LyricRenderer.FOLIA_LUMIERE, lumiereQuality = LumiereQuality.LOW)
        assertEquals(selected, parseSettings { selected.toPrefsMap()[it] })
        assertEquals(2, PlayerVisualMode.entries.size)
    }
    @Test fun calibrationIsPerRecordingAndRoundTripsWithPunctuation() {
        val track = Track("a", "song", "artist", "album", 1000, "netease", onlineKey = OnlineKey("netease", "id|x&y="))
        val settings = KinaraSettings(lyricDelayMs = 100).withLyricOffset(lyricIdentity(track)!!, -2500)
        assertEquals(-2400L, settings.lyricDelayFor(track))
        assertEquals(-2400L, settings.lyricDelayFor(track.copy(onlineKey = track.onlineKey!!.copy(bitrate = "flac"))))
        assertEquals(100L, settings.lyricDelayFor(track.copy(onlineKey = OnlineKey("qq", "id|x&y="))))
        assertEquals(settings, parseSettings { settings.toPrefsMap()[it] })
        assertEquals(100L, settings.withLyricOffset(lyricIdentity(track)!!, 0).lyricDelayFor(track))
        var many = settings
        repeat(220) { many = many.withLyricOffset("id$it", 100) }
        assertEquals(200, many.lyricOffsets.size)
        assertFalse(many.lyricOffsets.containsKey("id0"))
    }
}
