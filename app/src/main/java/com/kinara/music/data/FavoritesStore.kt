/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 收藏持久层：收藏曲目存本机（SharedPreferences），不发往任何服务。
 * 纪律：持久层即唯一事实来源；编解码复用 TrackCodec（零依赖纯 Kotlin，可 JVM 单测）。
 * 说明：在线曲目以 onlineKey（平台+id+码率）持久化，重放时按需重新解析地址——
 *  地址是一次播放尝试的结果，不作为曲目属性存取。
 */
package com.kinara.music.data

/** 序列化：曲目列表 → 文本（纯函数）。 */
fun encodeFavorites(tracks: List<Track>): String = tracks.joinToString("\n") { encodeTrackLine(it) }

/** 反序列化：文本 → 曲目列表；损坏行跳过而非整体失败（宽容解析纪律）。 */
fun decodeFavorites(text: String?): List<Track> {
    if (text.isNullOrBlank()) return emptyList()
    return text.lineSequence().mapNotNull { line -> decodeTrackLine(line) }.toList()
}

/** 切换收藏（纯函数）：存在则移除，不存在则加到队首（最近收藏靠前）。 */
fun toggleFavorite(current: List<Track>, track: Track): List<Track> =
    if (current.any { it.mediaId == track.mediaId }) {
        current.filterNot { it.mediaId == track.mediaId }
    } else {
        listOf(track) + current
    }

class FavoritesStore(private val sp: android.content.SharedPreferences) {

    fun load(): List<Track> = decodeFavorites(sp.getString(KEY, null))

    fun save(tracks: List<Track>) {
        sp.edit().putString(KEY, encodeFavorites(tracks)).apply()
    }

    companion object {
        private const val KEY = "favorites_txt"

        @Volatile private var instance: FavoritesStore? = null

        fun get(context: android.content.Context): FavoritesStore =
            instance ?: synchronized(this) {
                instance ?: FavoritesStore(
                    context.applicationContext.getSharedPreferences("kinara_favorites", android.content.Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
