/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 设置解析纯函数：从键值快照组装 KinaraSettings。
 * 抽离目的（MAS 纪律：逻辑与平台解耦以便 JVM 单测）：
 * SharedPreferences 是 Android 类型，纯 JVM 测试无法实例化；
 * 解析规则（含默认值兜底）集中于此，测试用 Map 快照覆盖。
 */
package com.kinara.music.data

import com.kinara.music.BuildConfig
import com.kinara.music.ui.theme.KinaraDarkMode
import com.kinara.music.ui.theme.KinaraThemeKind

/** 只读键值快照：屏蔽 SharedPreferences/Map 等具体来源。 */
fun interface PrefsSnapshot {
    fun get(key: String): Any?
}

private fun themeKindByName(v: String?): KinaraThemeKind =
    KinaraThemeKind.entries.firstOrNull { it.name == v } ?: KinaraThemeKind.DEFAULT

private fun darkModeByName(v: String?): KinaraDarkMode =
    KinaraDarkMode.entries.firstOrNull { it.name == v } ?: KinaraDarkMode.LIGHT

fun parseSettings(snapshot: PrefsSnapshot): KinaraSettings {
    fun str(key: String, def: String) = (snapshot.get(key) as? String) ?: def
    fun bool(key: String, def: Boolean) = (snapshot.get(key) as? Boolean) ?: def
    return KinaraSettings(
        theme = themeKindByName(snapshot.get("theme") as? String),
        darkMode = darkModeByName(snapshot.get("dark_mode") as? String),
        sources = SourceToggles(
            netease = bool("src_netease", true),
            kuwo = bool("src_kuwo", true),
            kugou = bool("src_kugou", true),
            qq = bool("src_qq", true),
            bilibili = bool("src_bili", true),
            solara = bool("src_solara", BuildConfig.SOLARA_SECRET.isNotBlank()),
            gdFallback = bool("src_gd", true),
        ),
        solaraBaseUrl = str("solara_url", BuildConfig.SOLARA_BASE_URL),
        solaraSecret = str("solara_secret", BuildConfig.SOLARA_SECRET),
        preferredBitrate = str("preferred_bitrate", "320"),
        cacheLimitGb = str("cache_limit_gb", "1").toIntOrNull()?.coerceIn(1, 5) ?: 1,
        // 1.2.x DYNAMIC was a page. Migrate it to the lyric page plus the glow renderer.
        playerVisualMode = if (str("player_visual_mode", "COVER") == "DYNAMIC") PlayerVisualMode.LYRICS
            else PlayerVisualMode.entries.firstOrNull { it.name == str("player_visual_mode", "COVER") } ?: PlayerVisualMode.COVER,
        lyricRenderer = LyricRenderer.entries.firstOrNull { it.name == str("lyric_renderer", "") }
            ?: if (str("player_visual_mode", "COVER") == "LYRICS") LyricRenderer.CLASSIC else LyricRenderer.GLOW,
        lumiereQuality = LumiereQuality.entries.firstOrNull { it.name == str("lumiere_quality", "BALANCED") } ?: LumiereQuality.BALANCED,
        showTranslation = bool("show_translation", true),
        showRomanization = bool("show_romanization", false),
        reduceLyricMotion = bool("reduce_lyric_motion", false),
        lyricDelayMs = str("lyric_delay_ms", "0").toLongOrNull()?.coerceIn(-10_000, 10_000) ?: 0,
        lyricOffsets = parseLyricOffsets(str("lyric_offsets", "")),
        autoTrackInfo = bool("auto_track_info", true),
    )
}

/** 键值对 → 存储写法：与 parseSettings 互为镜像，保证读写对称（测试覆盖往返）。 */
fun KinaraSettings.toPrefsMap(): Map<String, Any> = buildMap {
    put("theme", theme.name)
    put("dark_mode", darkMode.name)
    put("src_netease", sources.netease)
    put("src_kuwo", sources.kuwo)
    put("src_kugou", sources.kugou)
    put("src_qq", sources.qq)
    put("src_bili", sources.bilibili)
    put("src_solara", sources.solara)
    put("src_gd", sources.gdFallback)
    put("solara_url", solaraBaseUrl)
    put("solara_secret", solaraSecret)
    put("preferred_bitrate", preferredBitrate)
    put("cache_limit_gb", cacheLimitGb.toString())
    put("player_visual_mode", playerVisualMode.name)
    put("lyric_renderer", lyricRenderer.name)
    put("lumiere_quality", lumiereQuality.name)
    put("show_translation", showTranslation)
    put("show_romanization", showRomanization)
    put("reduce_lyric_motion", reduceLyricMotion)
    put("lyric_delay_ms", lyricDelayMs.coerceIn(-10_000, 10_000).toString())
    put("lyric_offsets", encodeLyricOffsets(lyricOffsets))
    put("auto_track_info", autoTrackInfo)
}
