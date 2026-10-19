/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.media3.datasource.DataSpec
import com.kinara.music.BuildConfig
import com.kinara.music.account.BiliApi
import com.kinara.music.data.*
import com.kinara.music.online.OnlineMediaUri
import com.kinara.music.player.AudioResolver
import com.kinara.music.player.ResolvedAudioSource
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import android.os.ParcelFileDescriptor

enum class DownloadPhase { RESOLVING, RUNNING, COMPLETE, FAILED, CANCELLED }
data class DownloadEntry(val key: String, val track: Track, val id: Long = -1, val phase: DownloadPhase = DownloadPhase.RESOLVING,
    val bytes: Long = 0, val total: Long = 0, val uri: String? = null, val message: String = "", val addedAt: Long = System.currentTimeMillis(),
    val requestedTrack: Track = track) {
    fun localTrack(): Track? = uri?.takeIf { phase == DownloadPhase.COMPLETE }?.let { track.copy(localPath = it, coverUrl = null) }
}

/** Header checks prevent a successful HTTP error page from becoming a fake music download. */
fun audioExtension(bytes: ByteArray): String? {
    fun at(offset: Int, text: String) = bytes.size >= offset + text.length && text.indices.all { bytes[offset + it].toInt() == text[it].code }
    return when {
        at(0, "fLaC") -> "flac"
        at(0, "OggS") -> "ogg"
        at(0, "RIFF") && at(8, "WAVE") -> "wav"
        at(4, "ftyp") -> "m4a"
        at(0, "ID3") -> "mp3"
        bytes.size > 1 && bytes[0].toInt() and 255 == 255 && bytes[1].toInt() and 224 == 224 ->
            if (bytes[1].toInt() and 6 == 0) "aac" else "mp3"
        else -> null
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
private suspend fun probeDownload(context: Context, track: Track): PreparedDownload {
    val resolver = AudioResolver(context)
    var candidate = track
    val attempts = com.kinara.music.online.PlaybackAttempts()
    while (true) {
    val source = ResolvedAudioSource(context, resolver)
    try {
        source.open(DataSpec.Builder().setUri(OnlineMediaUri.encode(candidate)).build())
        val header = ByteArray(64)
        var count = 0
        while (count < header.size) { val n = source.read(header, count, header.size - count); if (n <= 0) break; count += n }
        val extension = audioExtension(header.copyOf(count)) ?: error("音源返回的不是支持的音频文件")
        return PreparedDownload(source.uri?.toString() ?: error("音频地址无效"), extension, candidate)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        source.close()
        candidate = resolver.next(track, candidate, attempts) ?: throw e
    } finally { source.close() }
    }
}

internal data class PreparedDownload(val url: String, val extension: String, val track: Track)

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class Downloads internal constructor(private val context: Context,
    private val prepare: suspend (Track) -> PreparedDownload = { probeDownload(context, it) },
    taskScope: CoroutineScope? = null) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("kinara_downloads", Context.MODE_PRIVATE)
    private val scope = taskScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _entries = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val entries: StateFlow<List<DownloadEntry>> = _entries
    private val writes = Channel<List<DownloadEntry>>(Channel.CONFLATED)
    private val jobs = mutableMapOf<String, Job>()
    private var polling: Job? = null
    private val ready = scope.launch {
        _entries.value = withContext(Dispatchers.IO) { decode(prefs.getString("items", "[]").orEmpty()) }
        refresh()
    }
    init { scope.launch(Dispatchers.IO) { for (snapshot in writes) prefs.edit().putString("items", encode(snapshot)).apply() } }

    private fun change(key: String, transform: (DownloadEntry) -> DownloadEntry) {
        _entries.value = _entries.value.map { if (it.key == key) transform(it) else it }
        writes.trySend(_entries.value)
    }
    fun enqueue(track: Track, bitrate: String) = scope.launch {
        ready.join()
        val online = track.onlineKey ?: return@launch
        val adjusted = track.copy(localPath = null, onlineKey = online.copy(bitrate = bitrate))
        val key = stableDigest("${online.platform}|${online.id}|$bitrate")
        val previous = _entries.value.firstOrNull { it.key == key }
        if (previous?.phase in setOf(DownloadPhase.RUNNING, DownloadPhase.RESOLVING, DownloadPhase.COMPLETE)) return@launch
        if (previous != null && previous.id >= 0) withContext(Dispatchers.IO) { manager.remove(previous.id) }
        _entries.value = listOf(DownloadEntry(key, adjusted)) + _entries.value.filterNot { it.key == key }
        writes.trySend(_entries.value)
        jobs[key] = scope.launch {
            var systemId = -1L
            var recorded = false
            try {
                check(Build.VERSION.SDK_INT >= 29) { "下载功能需要 Android 10 或更新版本" }
                val prepared = withContext(Dispatchers.IO) { prepare(adjusted) }
                systemId = withContext(Dispatchers.IO) {
                    val (url, extension) = prepared
                    coroutineContext.ensureActive()
                    val name = adjusted.title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").take(60).ifBlank { "音频" }
                    val request = DownloadManager.Request(Uri.parse(url)).setTitle(adjusted.title)
                        .setDescription("Kinara · ${bitrate.uppercase()} 音质请求")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "Kinara/$name-${key.take(10)}-${System.currentTimeMillis()}.$extension")
                    (mapOf("User-Agent" to com.kinara.music.player.AUDIO_USER_AGENT) + BiliApi.playbackHeaders(url))
                        .forEach { (name, value) -> request.addRequestHeader(name, value) }
                    manager.enqueue(request).also { systemId = it }
                }
                change(key) { it.copy(id = systemId, track = prepared.track.copy(mediaId = adjusted.mediaId), phase = DownloadPhase.RUNNING) }
                recorded = true; refresh()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                change(key) { it.copy(phase = DownloadPhase.FAILED, message = if (Build.VERSION.SDK_INT < 29) "下载需要 Android 10 或更新版本" else "获取音频失败，请重试或更换音源") }
            } finally {
                if (!recorded && systemId >= 0) withContext(NonCancellable + Dispatchers.IO) { manager.remove(systemId) }
                if (jobs[key] === coroutineContext[Job]) jobs.remove(key)
            }
        }
    }
    fun cancel(key: String) = scope.launch {
        ready.join(); jobs.remove(key)?.cancel()
        val entry = _entries.value.firstOrNull { it.key == key } ?: return@launch
        if (entry.phase == DownloadPhase.COMPLETE) return@launch
        change(key) { it.copy(phase = DownloadPhase.CANCELLED, message = "已取消") }
        if (entry.id >= 0) withContext(Dispatchers.IO) { manager.remove(entry.id) }
    }
    fun retry(entry: DownloadEntry) { enqueue(entry.requestedTrack, entry.requestedTrack.onlineKey?.bitrate ?: "320") }

    fun refresh() {
        if (polling?.isActive == true) return
        polling = scope.launch {
            var verifyCompleted = true
            do {
                val snapshot = _entries.value
                val updates = withContext(Dispatchers.IO) { snapshot.filter { it.id >= 0 && (it.phase == DownloadPhase.RUNNING || (verifyCompleted && it.phase == DownloadPhase.COMPLETE)) }.map(::query) }
                verifyCompleted = false
                updates.forEach { update -> change(update.key) { current -> if (current.id == update.id && current.phase != DownloadPhase.CANCELLED) update else current } }
                if (_entries.value.none { it.phase == DownloadPhase.RUNNING }) break
                delay(2000)
            } while (isActive)
        }
    }
    private fun query(entry: DownloadEntry): DownloadEntry = try {
        manager.query(DownloadManager.Query().setFilterById(entry.id)).use { cursor ->
            if (!cursor.moveToFirst()) entry.copy(phase = DownloadPhase.FAILED, uri = null, message = "文件或任务已移除，可重新下载") else {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).coerceAtLeast(0)
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val valid = ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(entry.id)).use { input ->
                                val head = ByteArray(64); var count = 0
                                while (count < head.size) { val n = input.read(head, count, head.size - count); if (n < 0) break; count += n }
                                audioExtension(head.copyOf(count)) != null
                        }
                        if (!valid) {
                            manager.remove(entry.id)
                            error("无效音频文件")
                        }
                        entry.copy(phase = DownloadPhase.COMPLETE, bytes = bytes, total = total, uri = manager.getUriForDownloadedFile(entry.id)?.toString(), message = "已保存到 Music/Kinara")
                    }
                    DownloadManager.STATUS_FAILED -> entry.copy(phase = DownloadPhase.FAILED, uri = null, message = "下载失败，可重新获取地址后重试")
                    else -> entry.copy(phase = DownloadPhase.RUNNING, bytes = bytes, total = total, message = if (status == DownloadManager.STATUS_PAUSED) "等待网络或系统调度" else "下载中")
                }
            }
        }
    } catch (_: Exception) { entry.copy(phase = DownloadPhase.FAILED, uri = null, message = "音频文件无效或已移走，请重试") }

    private fun encode(items: List<DownloadEntry>) = JSONArray(items.map { e -> JSONObject().put("key", e.key).put("track", encodeTrackLine(e.track))
        .put("requestedTrack", encodeTrackLine(e.requestedTrack))
        .put("id", e.id).put("phase", e.phase.name).put("at", e.addedAt).put("uri", e.uri.orEmpty()).put("bytes", e.bytes).put("total", e.total).put("message", e.message) }).toString()
    private fun decode(value: String): List<DownloadEntry> = runCatching {
        val array = JSONArray(value)
        (0 until array.length()).mapNotNull { i -> val e = array.getJSONObject(i); val track = decodeTrackLine(e.optString("track")) ?: return@mapNotNull null
            val phase = runCatching { DownloadPhase.valueOf(e.optString("phase")) }.getOrDefault(DownloadPhase.FAILED)
            DownloadEntry(e.getString("key"), track, e.optLong("id", -1), if (phase == DownloadPhase.RESOLVING) DownloadPhase.FAILED else phase,
                e.optLong("bytes"), e.optLong("total"), e.optString("uri").ifBlank { null }, e.optString("message"), e.optLong("at"),
                decodeTrackLine(e.optString("requestedTrack")) ?: track) }
    }.getOrDefault(emptyList())
    companion object {
        @Volatile private var instance: Downloads? = null
        fun get(context: Context): Downloads = instance ?: synchronized(this) { instance ?: Downloads(context.applicationContext).also { instance = it } }
    }
}
