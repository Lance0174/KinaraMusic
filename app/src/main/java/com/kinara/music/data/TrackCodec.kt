/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 曲目编解码（收藏与歌单共用）：零依赖纯 Kotlin，可 JVM 单测。
 * 为什么不用 org.json：Android 的 org.json 在纯 JVM 单测中是未实现桩，调用即抛
 * "not mocked"（教训 E004/E007，本会话复发后定为硬规则）。
 * 格式：字段用单元分隔符 FIELD 连接，字段内的 FIELD/ESC/换行 先转义。
 */
package com.kinara.music.data

internal const val FIELD_CHAR = '\u001F'
internal const val ESC_CHAR = '\u001E'

internal fun kEsc(s: String): String = s
    .replace(ESC_CHAR.toString(), ESC_CHAR + "e")
    .replace("\n", ESC_CHAR + "n")
    .replace(FIELD_CHAR.toString(), ESC_CHAR + "f")

internal fun kUnesc(s: String): String = s
    .replace(ESC_CHAR + "f", FIELD_CHAR.toString())
    .replace(ESC_CHAR + "n", "\n")
    .replace(ESC_CHAR + "e", ESC_CHAR.toString())

/** 曲目 → 一行文本（不含换行，可直接与其他行拼接）。 */
internal fun encodeTrackLine(t: Track): String = listOf(
    t.mediaId, t.title, t.artist, t.album, t.durationMs.toString(), t.source,
    t.localPath ?: "", t.onlineKey?.platform ?: "", t.onlineKey?.id ?: "", t.onlineKey?.bitrate ?: "",
    t.coverUrl ?: "",
).joinToString(FIELD_CHAR.toString()) { kEsc(it) }

/** 一行文本 → 曲目；损坏行返回 null（宽容解析）。 */
internal fun decodeTrackLine(line: String): Track? {
    if (line.isBlank()) return null
    val f = line.split(FIELD_CHAR.toString()).map { kUnesc(it) }
    if (f.size < 6) return null
    val mediaId = f[0]
    if (mediaId.isBlank()) return null
    val platform = f.getOrElse(7) { "" }
    return Track(
        mediaId = mediaId,
        title = f[1],
        artist = f[2],
        album = f[3],
        durationMs = f[4].toLongOrNull() ?: 0L,
        source = f[5].ifBlank { "local" },
        localPath = f.getOrElse(6) { "" }.ifBlank { null },
        onlineKey = if (platform.isBlank()) null else OnlineKey(
            platform = platform,
            id = f.getOrElse(8) { "" },
            bitrate = f.getOrElse(9) { "320" }.ifBlank { "320" },
        ),
        coverUrl = f.getOrElse(10) { "" }.ifBlank { null },
    )
}
