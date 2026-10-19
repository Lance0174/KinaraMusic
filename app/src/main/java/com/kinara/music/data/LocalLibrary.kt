/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 本地曲库：MediaStore 扫描（READ_MEDIA_AUDIO / 旧版 READ_EXTERNAL_STORAGE）。
 * 扫描在 IO 协程执行；结果不含任何网络请求。
 */
package com.kinara.music.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LocalLibrary {

    suspend fun scan(context: Context): List<Track> = withContext(Dispatchers.IO) {
        val out = ArrayList<Track>(256)
        val collection = if (android.os.Build.VERSION.SDK_INT >= 29)
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        else
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

        val proj = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
        // 只收 IS_MUSIC='1'（排除铃声/闹钟/通知音）；IS_* 为字符串列，须用 '1'/'0' 比较
        context.contentResolver.query(
            collection, proj,
            "${MediaStore.Audio.Media.DURATION} > 0 AND ${MediaStore.Audio.Media.IS_MUSIC} = '1'",
            null,
            "${MediaStore.Audio.Media.DEFAULT_SORT_ORDER}",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iT = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val iA = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val iAl = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val iD = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                out.add(
                    Track(
                        mediaId = "L$id",
                        title = c.getString(iT) ?: "未知标题",
                        artist = (c.getString(iA) ?: "未知艺术家").ifBlank { "未知艺术家" },
                        album = (c.getString(iAl) ?: "").ifBlank { "未知专辑" },
                        durationMs = c.getLong(iD),
                        source = "local",
                        localPath = ContentUris.withAppendedId(collection, id).toString(),
                    )
                )
            }
        }
        out
    }

    fun albumArtUri(mediaId: String): android.net.Uri =
        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaId.removePrefix("L").toLong())
}
