/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 歌单导入（上轮实战验证过的方案重写）：
 *  - 网易云：旧 detail 端点有隐性截断（19 首只回 10 首）→ 用 v3 trackIds 全量 +
 *    /api/song/detail 分批换详情（每批 200，防 URL 过长）；
 *  - QQ：song_num 参数不生效（固定返回剩余全部），按 song_begin 偏移分页 +
 *    total_song_num 提前结束 + 40 页死循环保护 + songmid 去重。
 * 纪律：每曲区分"有 id 无详情/无地址"状态，不静默丢歌（用户死命令）。
 */
package com.kinara.music.online

import org.json.JSONArray
import org.json.JSONObject

object PlaylistImporter {

    data class ImportReport(
        val title: String,
        val total: Int,
        val imported: Int,
        val failed: Int,
        val tracks: List<OnlineTrack>,
        val message: String,
    )

    /** 支持的输入：网易云歌单 id（纯数字）/ QQ 歌单 id（disst 形式）。 */
    suspend fun importNetease(playlistId: String,
        fetch: suspend (String) -> OnlineTransport.TransportResponse = { OnlineTransport.getText(it) }): ImportReport {
        var title = "网易云歌单"
        var total = 0
        val details = linkedMapOf<String, OnlineTrack>()
        var ids = emptyList<String>()
        return try {
            // 1) v3 全量 trackIds
            val v3 = fetch(
                "https://music.163.com/api/v3/playlist/detail?id=${OnlineTransport.enc(playlistId)}&n=1000",
            )
            if (v3.code != 200) return ImportReport("网易云歌单 $playlistId", 0, 0, 0, emptyList(), "HTTP ${v3.code}")
            val v3j = JSONObject(v3.body)
            if (v3j.optInt("code") != 200) return ImportReport("歌单 $playlistId", 0, 0, 0, emptyList(), "接口 code=${v3j.optInt("code")}")
            val playlist = v3j.optJSONObject("playlist") ?: error("歌单不可访问")
            title = playlist.optString("name").ifBlank { title }
            val trackIds = playlist.optJSONArray("trackIds") ?: JSONArray()
            ids = trackIds.objects().map { it.optLongSafe("id").toString() }.filter { it != "0" }.distinct()
            total = maxOf(playlist.optInt("trackCount"), ids.size)
            if (ids.isEmpty()) return ImportReport(title, total, 0, total, emptyList(), if (total == 0) "空歌单" else "${total} 首歌曲暂不可读取")

            // 2) 分批详情（每批 200）
            for (chunk in ids.chunked(200)) {
                val idsParam = chunk.joinToString(",", "[", "]")
                val d = fetch("https://music.163.com/api/song/detail?ids=${OnlineTransport.enc(idsParam)}")
                if (d.code == 200) {
                    val songs = JSONObject(d.body).optJSONArray("songs") ?: JSONArray()
                    val got = songs.objects()
                    for (s in got) {
                        val id = s.optLongSafe("id").toString()
                        if (id !in chunk) continue
                        val artists = s.optJSONArray("artists").objects()
                        details[id] = OnlineTrack(
                            platform = "netease",
                            id = s.optLongSafe("id").toString(),
                            title = s.optString("name"),
                            artist = artists.joinToString("/") { it.optString("name") }.ifBlank { "未知艺术家" },
                            album = s.optJSONObject("album")?.optString("name").orEmpty().ifBlank { "未知专辑" },
                            durationMs = s.optLongSafe("duration"),
                            picUrl = com.kinara.music.data.safeCoverUrl(s.optJSONObject("album")?.optString("picUrl")),
                        )
                    }
                }
            }
            val ordered = ids.mapNotNull(details::get)
            val failed = total - ordered.size
            ImportReport(title, total, ordered.size, failed, ordered,
                "读取 ${ordered.size}/$total 首" + if (failed > 0) "，${failed} 首不可读取；保存将只包含已读取曲目" else "")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val ordered = ids.mapNotNull(details::get)
            ImportReport(title, total, ordered.size, (total - ordered.size).coerceAtLeast(0), ordered,
                "读取中断，已读取 ${ordered.size}/$total 首，${(total - ordered.size).coerceAtLeast(0)} 首未读取；可重试或保存当前部分")
        }
    }

    suspend fun importQQ(
        playlistId: String,
        fetch: suspend (String, Map<String, String>) -> OnlineTransport.TransportResponse = { url, headers ->
            OnlineTransport.getText(url, headers)
        },
    ): ImportReport {
        val id = playlistId.trim()
        if (!id.matches(Regex("[0-9]+"))) return ImportReport("QQ歌单", 0, 0, 0, emptyList(), "请输入数字歌单 ID")
        val seen = HashSet<String>()
        val out = ArrayList<OnlineTrack>()
        var total = -1
        var offset = 0
        var title = "QQ歌单 $id"
        var failure: String? = null
        try {
            for (page in 0 until 40) {
                val url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg" +
                    "?type=1&json=1&utf8=1&onlysong=0&format=json&g_tk=5381&disstid=$id" +
                    "&song_begin=$offset&song_num=50"
                val r = fetch(url, mapOf("Referer" to "https://y.qq.com/n/ryqq/playlist/"))
                check(r.code == 200) { "HTTP ${r.code}" }
                val j = JSONObject(r.body)
                check(j.optInt("code", -1) == 0) { "接口 code=${j.optInt("code", -1)}" }
                val cd = j.optJSONArray("cdlist")?.optJSONObject(0) ?: error("歌单不可访问或不存在")
                title = cd.optString("dissname").ifBlank { title }
                total = cd.optInt("total_song_num", cd.optInt("songnum", total))
                val list = cd.optJSONArray("songlist") ?: error("缺少歌曲详情")
                if (list.length() == 0) {
                    check(total <= out.size) { "歌单详情不完整" }
                    break
                }
                val before = out.size
                for (s in list.objects()) {
                    val mid = s.optString("songmid")
                    if (mid.isBlank() || !seen.add(mid)) continue
                    val albumMid = s.optString("albummid")
                    out.add(OnlineTrack(
                        platform = "qq", id = mid, title = s.optString("songname"),
                        artist = s.optJSONArray("singer").objects().joinToString("/") { it.optString("name") }.ifBlank { "未知艺术家" },
                        album = s.optString("albumname").ifBlank { "未知专辑" },
                        durationMs = s.optLongSafe("interval") * 1000,
                        picUrl = albumMid.takeIf { it.isNotBlank() }?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000$it.jpg" },
                    ))
                }
                if (total >= 0 && out.size >= total) break
                check(out.size > before) { "分页未返回新歌曲，已保留读取到的曲目" }
                offset += list.length() // song_num 可能被上游忽略，按实际返回数量前进
                if (page == 39) failure = "已达到分页上限"
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            failure = e.message ?: "读取失败"
        }
        val expected = total.coerceAtLeast(out.size)
        return ImportReport(title, expected, out.size, expected - out.size, out,
            if (failure == null) "读取 ${out.size}/$expected 首" else "读取 ${out.size} 首；$failure")
    }
}
