/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 统一曲目标识（上轮设计保留）：
 *  - 本地曲目 mediaId 为正数段（hash & Long.MAX_VALUE）；
 *  - 在线曲目 mediaId 为负数段（-hash），与本地同一 Long 空间内不互相污染。
 * 来源平台与解析服务分开：故障切换时换服务而不换曲目。
 */
package com.kinara.music.data

data class Track(
    val mediaId: String,      // ExoPlayer 用；本地 "L<id>"，在线 "N<neId>" / "K<kuwoId>" ...
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val source: String,       // local / netease / kuwo / kugou / qq / bilibili / solara / gd
    val localPath: String? = null,   // 本地曲目非空
    val onlineKey: OnlineKey? = null, // 在线曲目非空
    val coverUrl: String? = null,     // 在线封面地址（解析所得，非曲目永久属性）
) {
    val isLocal: Boolean get() = localPath != null
}

/** 在线曲目的平台定位键：platform + 平台侧 id。播放地址按需解析，不是曲目的永久属性。 */
data class OnlineKey(val platform: String, val id: String, val bitrate: String = "320")

/** 展示排序纯函数（JVM 可测）：按艺术家分组、曲名升序。 */
fun sortForDisplay(tracks: List<Track>): List<Track> =
    tracks.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))

/** 本地搜索过滤纯函数（JVM 可测）：大小写不敏感，标题/艺术家/专辑三字段命中。 */
fun filterTracks(tracks: List<Track>, query: String): List<Track> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return tracks
    return tracks.filter {
        it.title.lowercase().contains(q) ||
            it.artist.lowercase().contains(q) ||
            it.album.lowercase().contains(q)
    }
}
