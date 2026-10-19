/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 歌单：模型 + 纯函数操作 + 本机持久层（不外发任何数据）。
 * 设计说明：歌单内保存**曲目完整信息**而非仅 id——在线曲目的 id 跨平台不通用，
 * 存完整信息可保证歌单在离线打开、源变更时仍可显示与重试解析（地址仍不持久化）。
 */
package com.kinara.music.data

data class Playlist(
    val id: String,
    val name: String,
    val createdAt: Long,
    val tracks: List<Track> = emptyList(),
)

// ---------- 纯函数操作（可 JVM 单测）----------

fun createPlaylist(current: List<Playlist>, name: String, now: Long, id: String): List<Playlist> {
    val clean = name.trim().ifBlank { "未命名歌单" }
    return current + Playlist(id = id, name = clean, createdAt = now)
}

fun renamePlaylist(current: List<Playlist>, id: String, name: String): List<Playlist> =
    current.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }

fun deletePlaylist(current: List<Playlist>, id: String): List<Playlist> =
    current.filterNot { it.id == id }

/** 追加曲目：跳过歌单内已存在的 mediaId（按序去重），返回新列表。 */
fun addTracksToPlaylist(current: List<Playlist>, id: String, tracks: List<Track>): List<Playlist> =
    current.map { pl ->
        if (pl.id != id) pl else {
            val existing = pl.tracks.map { it.mediaId }.toMutableSet()
            val added = tracks.filter { existing.add(it.mediaId) }
            pl.copy(tracks = pl.tracks + added)
        }
    }

fun removeTrackFromPlaylist(current: List<Playlist>, id: String, mediaId: String): List<Playlist> =
    current.map { pl -> if (pl.id == id) pl.copy(tracks = pl.tracks.filterNot { it.mediaId == mediaId }) else pl }

// ---------- 编解码（零依赖纯 Kotlin）----------

/** 歌单 → 文本：每行一个歌单；字段：id FIELD name FIELD createdAt FIELD 曲目行（用 ESC+t 连接）。 */
fun encodePlaylists(playlists: List<Playlist>): String = playlists.joinToString("\n") { pl ->
    val tracksPart = pl.tracks.joinToString(ESC_CHAR + "t") { kEsc(encodeTrackLine(it)) }
    listOf(kEsc(pl.id), kEsc(pl.name), pl.createdAt.toString(), tracksPart)
        .joinToString(FIELD_CHAR.toString())
}

fun decodePlaylists(text: String?): List<Playlist> {
    if (text.isNullOrBlank()) return emptyList()
    return text.lineSequence().mapNotNull { line ->
        if (line.isBlank()) return@mapNotNull null
        val f = line.split(FIELD_CHAR.toString())
        if (f.size < 3) return@mapNotNull null
        val id = kUnesc(f[0])
        if (id.isBlank()) return@mapNotNull null
        val tracks = f.getOrElse(3) { "" }
            .split(ESC_CHAR + "t")
            .mapNotNull { part -> if (part.isBlank()) null else decodeTrackLine(kUnesc(part)) }
        Playlist(
            id = id,
            name = kUnesc(f[1]),
            createdAt = f[2].toLongOrNull() ?: 0L,
            tracks = tracks,
        )
    }.toList()
}

class PlaylistStore(private val sp: android.content.SharedPreferences) {

    fun load(): List<Playlist> = decodePlaylists(sp.getString(KEY, null))

    fun save(playlists: List<Playlist>) {
        sp.edit().putString(KEY, encodePlaylists(playlists)).apply()
    }

    companion object {
        private const val KEY = "playlists_txt"

        @Volatile private var instance: PlaylistStore? = null

        fun get(context: android.content.Context): PlaylistStore =
            instance ?: synchronized(this) {
                instance ?: PlaylistStore(
                    context.applicationContext.getSharedPreferences("kinara_playlists", android.content.Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
