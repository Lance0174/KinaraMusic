/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kinara.music.player.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SleepTimerDialog(onDismiss: () -> Unit, onSet: (Int, Boolean) -> Unit) {
    val state by SleepTimer.state.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("定时关闭") }, text = {
        Column {
            Text(when { state.afterTrack -> "当前歌曲结束后暂停"; state.deadline > 0 -> "约 ${(state.remaining(android.os.SystemClock.elapsedRealtime()) + 59_999) / 60_000} 分钟后暂停"; else -> "到时暂停播放，后台同样生效" })
            listOf(15, 30, 45, 60, 90).forEach { minutes -> TextButton({ onSet(minutes, false); onDismiss() }, Modifier.fillMaxWidth()) { Text("$minutes 分钟后") } }
            TextButton({ onSet(0, true); onDismiss() }, Modifier.fillMaxWidth()) { Text("当前歌曲结束后") }
        }
    }, confirmButton = { TextButton({ onSet(0, false); onDismiss() }) { Text("关闭定时") } }, dismissButton = { TextButton(onDismiss) { Text("返回") } })
}

@Composable
fun DiagnosticsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun snapshot(): String {
        val audio = context.getSystemService(AudioManager::class.java)
        PlaybackDiagnostics.record(PlaybackDiagnostics.Event.SYSTEM_VOLUME, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        PlaybackDiagnostics.record(PlaybackDiagnostics.Event.SYSTEM_MODE, audio.mode)
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { PlaybackDiagnostics.record(PlaybackDiagnostics.Event.OUTPUT_TYPE, it.type) }
        return PlaybackDiagnostics.snapshot()
    }
    var report by remember { mutableStateOf(snapshot()) }
    var status by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            status = withContext(Dispatchers.IO) { runCatching {
                val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入")
                output.bufferedWriter().use { it.write(report) }; "已导出诊断"
            }.getOrDefault("导出失败，请重试") }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("播放诊断") }, text = {
        Column {
            Text("记录播放器状态、音频焦点、输出增益与错误码。复现问题后刷新并导出；不包含账号凭证或音频地址。", style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text(report, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(top = 12.dp)) }
            if (status.isNotEmpty()) Text(status)
            TextButton({ report = snapshot() }) { Text("刷新诊断") }
        }
    }, confirmButton = { TextButton({ export.launch("Kinara-playback-diagnostics.txt") }) { Text("导出") } }, dismissButton = { TextButton(onDismiss) { Text("关闭") } })
}
