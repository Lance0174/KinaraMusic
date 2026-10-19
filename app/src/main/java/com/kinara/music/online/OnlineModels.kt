/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 在线音源统一契约（上轮验证过的设计保留）：
 *  - 搜索失败返回 errorMessage，展示层"失败优先于空结果"；
 *  - 播放地址是"一次播放尝试的结果"，不是曲目永久属性；
 *  - 歌词失败返回 null 不抛异常（增强信息不阻塞播放）。
 * 纪律：UI 不直接发 HTTP；策略在 OnlineRepository，协议在各 Source。
 */
package com.kinara.music.online

import com.kinara.music.data.safeCoverUrl

data class OnlineTrack(
    val platform: String,   // netease / kuwo / kugou / qq / bilibili / solara
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long = 0,
    val picUrl: String? = null,
    val matched: Boolean = false, // 聚合按标题匹配时 true（界面明示"聚合"标签）
)

data class OnlineResult(
    val platform: String,
    val tracks: List<OnlineTrack> = emptyList(),
    val errorMessage: String? = null,
    val elapsedMs: Long = 0,
)

enum class ResolveStage { NOT_CONFIGURED, TRANSPORT, NO_RESULT, OK }

data class ResolvedUrl(
    val stage: ResolveStage,
    val url: String? = null,
    val bitrate: String? = null,
    val viaProxy: Boolean = false, // 经自建 Worker 或 Solara target 代理
    val errorMessage: String? = null,
    val backupUrls: List<String> = emptyList(), // Same encoded audio, suitable for byte-range retries.
)

/** 转成统一 Track（mediaId 负数段隔离）。 */
fun OnlineTrack.toTrack(bitrate: String): com.kinara.music.data.Track =
    com.kinara.music.data.Track(
        mediaId = "O$platform$id",
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        source = if (matched) "gd" else platform,
        onlineKey = com.kinara.music.data.OnlineKey(platform, id, bitrate),
        coverUrl = safeCoverUrl(picUrl),
    )
