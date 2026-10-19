/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import com.kinara.music.online.toTrack

/** Portable files contain stable online IDs only, never device paths, cookies or signed URLs. */
object PortablePlaylist {
    fun encode(name: String, tracks: List<Track>): String {
        val online = tracks.filter { it.onlineKey != null }.distinctBy { it.mediaId }
        return JSONObject().put("meta", JSONObject().put("app", "Kinara").put("version", 1).put("name", name)
            .put("omittedLocalTracks", tracks.count { it.onlineKey == null }))
            .put("items", JSONArray(online.map { t ->
                val key = t.onlineKey!!
                JSONObject().put("id", key.id).put("source", key.platform).put("name", t.title)
                    .put("artist", t.artist).put("album", t.album).put("duration", t.durationMs)
                    .put("bitrate", key.bitrate)
            })).toString(2)
    }

    fun decode(text: String): Pair<String, List<Track>> {
        require(text.length <= 5_000_000) { "歌单文件过大" }
        val root = JSONTokener(text).nextValue()
        val items = (root as? JSONObject)?.optJSONArray("items") ?: root as? JSONArray ?: error("文件缺少 items 歌曲列表")
        require(items.length() <= 10_000) { "歌单最多支持 10000 首" }
        val tracks = (0 until items.length()).mapNotNull { index ->
            val obj = items.optJSONObject(index) ?: return@mapNotNull null
            val platform = obj.optString("source", obj.optString("platform"))
            val id = obj.optString("id")
            if (platform !in setOf("netease", "kuwo", "qq", "kugou", "bilibili", "solara", "gd") || id.isBlank() || id.length > 200) return@mapNotNull null
            val key = OnlineKey(platform, id, obj.optString("bitrate", "320").takeIf { it in setOf("128", "192", "320", "flac") } ?: "320")
            val artists = obj.optJSONArray("artist")?.let { array -> (0 until array.length()).map { array.optString(it) }.joinToString("/") }
                ?: obj.optString("artist", "未知艺术家")
            com.kinara.music.online.OnlineTrack(platform, id, obj.optString("name", obj.optString("title", "未知标题")),
                artists, obj.optString("album"), obj.optLong("duration").coerceAtLeast(0))
                .toTrack(key.bitrate)
        }.distinctBy { it.mediaId }
        require(tracks.isNotEmpty()) { "没有可导入的在线曲目；本机音频需通过文件选择器导入" }
        return ((root as? JSONObject)?.optJSONObject("meta")?.optString("name")?.ifBlank { "导入歌单" } ?: "导入歌单") to tracks
    }
}
