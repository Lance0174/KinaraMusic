/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class LocalImportResult(val tracks: List<Track>, val failed: Int)

fun stableDigest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

object LocalAudioImport {
    private val copyLock = Mutex()
    suspend fun read(context: Context, uris: List<Uri>): LocalImportResult = withContext(Dispatchers.IO) {
        val tracks = ArrayList<Track>()
        var failed = 0
        for (uri in uris.distinct()) {
            coroutineContext.ensureActive()
            try { tracks += readOne(context, uri) }
            catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; failed++ }
        }
        LocalImportResult(tracks, failed)
    }

    private suspend fun readOne(context: Context, uri: Uri): Track {
        require(uri.scheme == "content") { "请选择本机音频文件" }
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }.orEmpty().ifBlank { "本地音频" }
        val persistent = runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        }.getOrDefault(false)
        // Providers without persistable grants are copied once into private storage before saving.
        val path = if (persistent) uri.toString() else copyLock.withLock {
            val dir = File(context.noBackupFilesDir, "imported-audio").apply { mkdirs() }
            val file = File(dir, stableDigest(uri.toString()))
            if (!file.exists()) {
                val temp = File.createTempFile(file.name, ".part", dir)
                try {
                    resolver.openInputStream(uri)?.use { input -> temp.outputStream().use { copyImportedAudio(input, it) } }
                        ?: error("无法读取文件")
                    check(temp.length() > 0 && temp.renameTo(file))
                } finally { temp.delete() }
            }
            file.absolutePath
        }
        // Opening validates the grant now; unreadable entries never enter a playlist.
        resolver.openAssetFileDescriptor(uri, "r")?.use { } ?: error("无法读取文件")
        val metadata = MediaMetadataRetriever()
        var title: String? = null; var artist: String? = null; var album: String? = null; var duration = 0L
        try {
            metadata.setDataSource(context, uri)
            title = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            album = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            duration = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (_: RuntimeException) {
            // Some valid audio containers have no readable tags. Keep a useful filename fallback.
        } finally { metadata.release() }
        return Track("F${stableDigest(uri.toString())}", title?.takeIf { it.isNotBlank() }
            ?: displayName.substringBeforeLast('.').ifBlank { displayName },
            artist?.takeIf { it.isNotBlank() } ?: "未知艺术家", album.orEmpty(), duration,
            "local", localPath = path)
    }
}

/** A provider may report no size or stream forever. Bound private copies and observe cancellation per chunk. */
internal suspend fun copyImportedAudio(input: InputStream, output: OutputStream, maxBytes: Long = 512L * 1024 * 1024) {
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        coroutineContext.ensureActive()
        val count = input.read(buffer)
        if (count < 0) return
        total += count
        check(total <= maxBytes) { "导入文件超过 512 MiB，请使用支持持久授权的文件提供方" }
        output.write(buffer, 0, count)
    }
}
