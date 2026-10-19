/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 设置持久层 = 唯一事实来源。
 * 纪律（前会话死命令）：页面不持有状态副本、不设保存按钮；任何改动即写即生效。
 * 教训 E008/E010：读取必须容忍未初始化（默认值兜底），不得在属性初始化器时序上赌博。
 * 教训 E021：SharedPreferences 有进程内快照，外部改文件不作为调试手段，一切走应用内设置。
 * 解析规则在 KinaraSettingsParser（纯函数，JVM 可测），本类只做存储读写。
 */
package com.kinara.music.data

import android.content.Context
import android.content.SharedPreferences
import com.kinara.music.BuildConfig
import com.kinara.music.ui.theme.KinaraDarkMode
import com.kinara.music.ui.theme.KinaraThemeKind

/** 音源开关集合。Solara 需要用户自行配置凭据，默认关闭。 */
data class SourceToggles(
    val netease: Boolean = true,
    val kuwo: Boolean = true,
    val kugou: Boolean = true,
    val qq: Boolean = true,
    val bilibili: Boolean = true,
    val solara: Boolean = BuildConfig.SOLARA_SECRET.isNotBlank(),
    val gdFallback: Boolean = true,
)

data class KinaraSettings(
    val theme: KinaraThemeKind = KinaraThemeKind.DEFAULT,
    val darkMode: KinaraDarkMode = KinaraDarkMode.SYSTEM,
    val sources: SourceToggles = SourceToggles(),
    // Solara 凭据只能由运行时外部配置，源码不携带服务地址或密钥。
    val solaraBaseUrl: String = BuildConfig.SOLARA_BASE_URL,
    val solaraSecret: String = BuildConfig.SOLARA_SECRET,
    val preferredBitrate: String = "320", // 128 / 320 / flac
    val cacheLimitGb: Int = 1,
    val playerVisualMode: PlayerVisualMode = PlayerVisualMode.COVER,
    val lyricRenderer: LyricRenderer = LyricRenderer.GLOW,
    val lumiereQuality: LumiereQuality = LumiereQuality.BALANCED,
    val lyricVisualStyle: LyricVisualStyle = LyricVisualStyle.AUTO,
    val showTranslation: Boolean = true,
    val showRomanization: Boolean = false,
    val reduceLyricMotion: Boolean = false,
    val lyricDelayMs: Long = 0,
    val lyricOffsets: Map<String, Long> = emptyMap(),
    val autoTrackInfo: Boolean = true,
)

enum class PlayerVisualMode(val label: String) { COVER("封面"), LYRICS("歌词") }
enum class LyricRenderer(val label: String) { CLASSIC("经典"), GLOW("逐字辉光"), FOLIA_LUMIERE("动态视界") }
enum class LyricVisualStyle(val label: String, val wireName: String, val description: String) {
    AUTO("随曲变化", "auto", "每首歌选择一种视觉编排，同一首歌保持一致"),
    LUMIERE("流光", "lumiere", "光束、薄雾与粒子随音乐起伏，歌词在光场中浮现"),
    SONNET("动态排字", "sonnet", "文字冲击、镜头追踪与海报排字，随歌词变换构图"),
    TEMPERA("凝彩", "tempera", "色块拼贴、动态反色与分镜转场，逐句展开歌词影像"),
}
enum class LumiereQuality(val label: String, val wireName: String) {
    LOW("省电", "low"), BALANCED("均衡", "balanced"), FULL("精细", "full")
}

class KinaraPrefs private constructor(private val sp: SharedPreferences) {

    /** 同步读取：解析规则见 KinaraSettingsParser；缺键回落默认值，不抛异常。 */
    fun settings(): KinaraSettings {
        val snapshot = sp.all
        return parseSettings { key -> snapshot[key] }
    }

    /** 即改即存。UI 每次点选直接调用，无"保存"动作。写入键集与 toPrefsMap 保持镜像。 */
    fun save(s: KinaraSettings) {
        // 一个 Editor 原子更新内存快照，apply 异步落盘；调用方按主线程操作顺序调用。
        val editor = sp.edit().clear()
        for ((k, v) in s.toPrefsMap()) {
            when (v) {
                is Boolean -> editor.putBoolean(k, v)
                is String -> editor.putString(k, v)
            }
        }
        editor.apply()
    }

    companion object {
        @Volatile private var instance: KinaraPrefs? = null

        /** 双检单例：进程内共享；任何时机调用安全（含 ViewModel 属性初始化器，教训 E008）。 */
        fun get(context: Context): KinaraPrefs =
            instance ?: synchronized(this) {
                instance ?: KinaraPrefs(
                    context.applicationContext.getSharedPreferences("kinara_prefs", Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
