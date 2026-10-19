/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kinara.music.BuildConfig
import com.kinara.music.update.UpdatePhase
import com.kinara.music.update.UpdateViewModel

@Composable
internal fun AppUpdatePanel(vm: UpdateViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var notes by remember { mutableStateOf(false) }
    val busy = state.phase in listOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.INSTALLING)
    Text("Kinara ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
    Text("${state.release?.let { "发现新版本 ${it.version}" } ?: "获取正式版本与更新说明"}",
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
    if (state.phase == UpdatePhase.DOWNLOADING) {
        val total = state.release?.bytes ?: 1
        val fraction = (state.downloaded.toFloat() / total).coerceIn(0f, 1f)
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text("${(fraction * 100).toInt()}% · ${formatBytes(state.downloaded)} / ${formatBytes(total)}",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
    if (state.message.isNotEmpty()) Text(state.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.phase) {
            UpdatePhase.AVAILABLE -> Button(vm::download) { Text("下载更新") }
            UpdatePhase.DOWNLOADING -> OutlinedButton(vm::cancel) { Text("取消下载") }
            UpdatePhase.READY -> Button(vm::install) { Text("安装更新") }
            UpdatePhase.FAILED -> if (state.release != null) Button(vm::download) { Text("重新下载") }
            else -> Unit
        }
        if (state.phase != UpdatePhase.DOWNLOADING) OutlinedButton(vm::check, enabled = !busy) {
            Text(if (state.phase == UpdatePhase.CHECKING) "正在检查…" else "检查更新")
        }
    }
    Row {
        if (state.release != null) TextButton({ notes = true }) { Text("更新说明") }
        TextButton(vm::openReleases) { Text("发布页面") }
    }
    if (notes) AlertDialog(onDismissRequest = { notes = false }, title = { Text("Kinara ${state.release?.version}") },
        text = { Text(state.release?.notes?.ifBlank { "此版本未提供更新说明" }.orEmpty(),
            Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ notes = false }) { Text("关闭") } })
}
