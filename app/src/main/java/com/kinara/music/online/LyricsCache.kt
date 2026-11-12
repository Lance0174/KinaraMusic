/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.online

import com.kinara.music.data.OnlineKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Separate lyric cache preserves real word timing and side tracks for offline playback. */
class LyricsCache(private val directory: File) {
    private fun file(key: OnlineKey): File = File(directory,
        MessageDigest.getInstance("SHA-256").digest("${key.platform}:${key.id}".toByteArray())
            .joinToString("") { "%02x".format(it) } + ".json")

    suspend fun read(key: OnlineKey): LyricDocument? = withContext(Dispatchers.IO) {
        runCatching {
            val file = file(key)
            if (!file.isFile || file.length() > 2_000_000) return@runCatching null
            val json = JSONObject(file.readText())
            val metadata = json.optJSONObject("metadata")
            LyricDocument(json.getJSONArray("lines").objects().map { row ->
                LyricLine(row.getLong("time"), row.getString("text"), row.optLong("end").takeIf { row.has("end") },
                    row.optJSONArray("words").objects().map { LyricWord(it.getLong("start"), it.getLong("end"), it.getString("text")) },
                    row.optString("translation").takeIf { it.isNotBlank() }, row.optString("romanization").takeIf { it.isNotBlank() })
            }, metadata = metadata?.keys()?.asSequence()?.associateWith { metadata.getString(it) }.orEmpty(), fileOffsetMs = json.optLong("offset"))
        }.getOrNull()
    }

    suspend fun write(key: OnlineKey, document: LyricDocument) = withContext(Dispatchers.IO) {
        if (document.lines.isEmpty()) return@withContext
        runCatching {
            val rows = JSONArray()
            for (line in document.lines) rows.put(JSONObject().put("time", line.timeMs).put("text", line.text)
                .put("end", line.endMs).put("translation", line.translation).put("romanization", line.romanization)
                .put("words", JSONArray().apply { line.words.forEach { put(JSONObject().put("start", it.startMs).put("end", it.endMs).put("text", it.text)) } }))
            val content = JSONObject().put("lines", rows).put("metadata", JSONObject(document.metadata)).put("offset", document.fileOffsetMs).toString()
            if (content.toByteArray().size > 2_000_000) return@runCatching
            directory.mkdirs()
            val destination = file(key)
            val temp = File(directory, destination.name + ".tmp")
            try {
                temp.writeText(content)
                if (!temp.renameTo(destination)) { destination.writeText(content); temp.delete() }
            } finally {
                // 写入或改名中途抛错时不能把 *.json.tmp 留在磁盘上：
                // 下面按 extension == "json" 做淘汰，".json.tmp" 的扩展名是 "tmp"，永远匹配不到，
                // 残留文件只会一直堆积。
                if (temp.exists()) temp.delete()
            }
            directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }?.drop(150)?.forEach { it.delete() }
        }
        Unit
    }
}
