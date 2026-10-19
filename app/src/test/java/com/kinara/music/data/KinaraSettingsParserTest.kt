/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 */
package com.kinara.music.data

import com.kinara.music.ui.theme.KinaraDarkMode
import com.kinara.music.ui.theme.KinaraThemeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KinaraSettingsParserTest {

    private fun snapshotOf(map: Map<String, Any?>) = PrefsSnapshot { map[it] }

    @Test
    fun `empty snapshot falls back to defaults`() {
        val s = parseSettings(snapshotOf(emptyMap()))
        assertEquals(KinaraThemeKind.DEFAULT, s.theme)
        assertEquals(KinaraDarkMode.LIGHT, s.darkMode)
        assertEquals("320", s.preferredBitrate)
        assertEquals("", s.solaraBaseUrl)
        assertEquals("", s.solaraSecret)
    }

    @Test
    fun `all sources default on per D2`() {
        val s = parseSettings(snapshotOf(emptyMap())).sources
        assertTrue(s.netease); assertTrue(s.kuwo); assertTrue(s.kugou)
        assertTrue(s.qq); assertTrue(s.bilibili); assertTrue(s.gdFallback)
        assertTrue(!s.solara)
    }

    @Test
    fun `roundtrip preserves every field`() {
        val original = KinaraSettings(
            theme = KinaraThemeKind.INDUSTRIAL,
            darkMode = KinaraDarkMode.DARK,
            sources = SourceToggles(netease = false, kugou = false, solara = false),
            solaraBaseUrl = "https://example.invalid/",
            solaraSecret = "s3cret",
            preferredBitrate = "flac",
        )
        val map = original.toPrefsMap()
        val parsed = parseSettings(snapshotOf(map))
        assertEquals(original, parsed)
    }

    @Test
    fun `corrupted enum values fall back safely`() {
        val s = parseSettings(snapshotOf(mapOf("theme" to "NOPE", "dark_mode" to 42)))
        assertEquals(KinaraThemeKind.DEFAULT, s.theme)
        assertEquals(KinaraDarkMode.LIGHT, s.darkMode)
    }

    @Test
    fun `theme kind has exactly two builtin themes per D1`() {
        assertEquals(2, KinaraThemeKind.entries.size)
    }
}
