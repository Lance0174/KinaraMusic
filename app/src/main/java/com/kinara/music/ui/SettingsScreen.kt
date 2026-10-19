/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinara.music.BuildConfig
import com.kinara.music.data.KinaraSettings
import com.kinara.music.data.LyricRenderer
import com.kinara.music.data.LumiereQuality
import com.kinara.music.data.LyricVisualStyle
import com.kinara.music.ui.theme.*
import androidx.compose.ui.platform.LocalContext
import com.kinara.music.player.AudioCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(settings: KinaraSettings, onTheme: (KinaraThemeKind) -> Unit, onDark: (String) -> Unit,
    onSource: (String, Boolean) -> Unit, onBitrate: (String) -> Unit = {},
    onCacheLimit: (Int) -> Unit = {}, onDiagnostics: () -> Unit = {},
    onPreferences: ((KinaraSettings) -> KinaraSettings) -> Unit = {}) {
    val c = LocalKinaraTheme.current.colors
    var licenses by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().testTag("settings_scroll").verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
        .padding(bottom = LocalDockInset.current), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingsSection("外观") {
            Text("主题", fontSize = 14.sp, color = c.textDim)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KinaraThemeKind.entries.forEach { kind -> ChoiceChip(kind.label, settings.theme == kind, { onTheme(kind) }, Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(12.dp))
            Text("明暗模式", fontSize = 14.sp, color = c.textDim)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("浅色" to "LIGHT", "深色" to "DARK", "自动" to "SYSTEM").forEach { (label, value) ->
                    ChoiceChip(label, settings.darkMode.name == value, { onDark(value) }, Modifier.weight(1f))
                }
            }
        }
        SettingsSection("音乐来源") {
            Text("搜索会合并各来源的歌曲；播放失败时自动尝试其他来源。", color = c.textDim, fontSize = 12.sp)
            SourceRow("netease", "网易云音乐", settings.sources.netease, onSource)
            SourceRow("kuwo", "酷我音乐", settings.sources.kuwo, onSource)
            SourceRow("kugou", "酷狗音乐", settings.sources.kugou, onSource, "播放可使用聚合匹配")
            SourceRow("qq", "QQ音乐", settings.sources.qq, onSource, "播放可使用聚合匹配")
            SourceRow("bilibili", "哔哩哔哩", settings.sources.bilibili, onSource)
            SourceRow("solara", "Solara", settings.sources.solara, onSource, if (settings.solaraBaseUrl.isNotBlank()) "已配置服务" else "需由部署者配置服务地址和凭据")
            HorizontalDivider(color = c.textDim.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 6.dp))
            SourceRow("gdFallback", "聚合补充", settings.sources.gdFallback, onSource, "为部分来源匹配同名、同艺术家的音频")
        }
        SettingsSection("播放与缓存") {
            PlaybackPreferences(settings, onBitrate, onCacheLimit, onDiagnostics)
        }
        SettingsSection("歌词与歌曲信息") {
            Text("歌词视觉模式", fontWeight = FontWeight.SemiBold, color = c.text)
            LyricRenderer.entries.forEach { renderer ->
                Row(Modifier.fillMaxWidth().selectable(settings.lyricRenderer == renderer, role = Role.RadioButton,
                    onClick = { onPreferences { it.copy(lyricRenderer = renderer) } }).heightIn(min = 64.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(settings.lyricRenderer == renderer, onClick = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(renderer.label, color = c.text)
                        Text(when (renderer) {
                            LyricRenderer.CLASSIC -> "清晰简洁，逐行跟随"
                            LyricRenderer.GLOW -> "逐字点亮，柔和平滑滚动"
                            LyricRenderer.FOLIA_LUMIERE -> "让歌词成为影像，体验多种动态编排"
                        }, fontSize = 12.sp, color = c.textDim)
                    }
                }
            }
            Text("在播放页左右滑动，或轻点底部切换封面与歌词", color = c.textDim, fontSize = 12.sp)
            if (settings.lyricRenderer == LyricRenderer.FOLIA_LUMIERE) {
                Spacer(Modifier.height(12.dp))
                Text("视觉编排", color = c.text, fontWeight = FontWeight.SemiBold)
                LyricVisualStyle.entries.forEach { style ->
                    Row(Modifier.fillMaxWidth().selectable(settings.lyricVisualStyle == style, role = Role.RadioButton,
                        onClick = { onPreferences { it.copy(lyricVisualStyle = style) } }).heightIn(min = 64.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(settings.lyricVisualStyle == style, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(style.label, color = c.text)
                            Text(style.description, color = c.textDim, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("动态画质", color = c.text)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LumiereQuality.entries.forEach { quality ->
                        ChoiceChip(quality.label, settings.lumiereQuality == quality,
                            { onPreferences { it.copy(lumiereQuality = quality) } }, Modifier.weight(1f))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = c.textDim.copy(alpha = 0.12f))
            SourceRow("translation", "显示译文", settings.showTranslation, { _, value -> onPreferences { it.copy(showTranslation = value) } })
            SourceRow("romanization", "显示罗马音", settings.showRomanization, { _, value -> onPreferences { it.copy(showRomanization = value) } })
            SourceRow("motion", "减少歌词动效", settings.reduceLyricMotion, { _, value -> onPreferences { it.copy(reduceLyricMotion = value) } }, "保留歌词高亮；动态视界改用静态歌词，关闭扫光与缩放")
            SourceRow("info", "联网更新歌曲信息", settings.autoTrackInfo, { _, value -> onPreferences { it.copy(autoTrackInfo = value) } }, "自动补全专辑封面；本地歌曲仅在信息明确匹配时更新，不修改音频文件")
            Text("全局歌词时间 · ${if (settings.lyricDelayMs > 0) "+" else ""}${settings.lyricDelayMs} ms", color = c.text)
            Text("单首歌不准时，请在播放页菜单中选择「歌词校准」", color = c.textDim, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ onPreferences { it.copy(lyricDelayMs = (it.lyricDelayMs - 100).coerceAtLeast(-10_000)) } }) { Text("提前 0.1 秒") }
                TextButton({ onPreferences { it.copy(lyricDelayMs = 0) } }) { Text("重置") }
                TextButton({ onPreferences { it.copy(lyricDelayMs = (it.lyricDelayMs + 100).coerceAtMost(10_000)) } }) { Text("延后 0.1 秒") }
            }
        }
        SettingsSection("应用更新") { AppUpdatePanel() }
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Kinara", color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("版本 ${BuildConfig.VERSION_NAME}", color = c.textDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            TextButton({ licenses = true }) { Text("开源许可") }
        }
    }
    if (licenses) {
        val context = LocalContext.current
        var file by remember { mutableStateOf("NOTICE.txt") }
        val notice = remember(file) { context.assets.open("folia/$file").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { licenses = false }, title = { Text("开源许可") }, text = {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ file = "KINARA-LICENSE.txt" }) { Text("Kinara") }
                    TextButton({ file = "FOLIA-LICENSE.txt" }) { Text("渲染组件") }
                    TextButton({ file = "THIRD-PARTY.txt" }) { Text("第三方") }
                }
                Text(notice, fontSize = 12.sp, modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()))
            }
        }, confirmButton = { TextButton({ licenses = false }) { Text("关闭") } })
    }

}

@Composable
private fun PlaybackPreferences(settings: KinaraSettings, onBitrate: (String) -> Unit, onLimit: (Int) -> Unit, onDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var usage by remember { mutableLongStateOf(0) }
    var message by remember { mutableStateOf("") }
    var clear by remember { mutableStateOf(false) }
    LaunchedEffect(settings.cacheLimitGb) {
        withContext(Dispatchers.IO) { AudioCache.get(context).maintain(settings.cacheLimitGb) }
        AudioCache.get(context).bytes.collect { usage = it }
    }
    Text("默认在线音质", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("128", "320", "flac").forEach { bitrate -> ChoiceChip(bitrate.uppercase(), settings.preferredBitrate == bitrate, { onBitrate(bitrate) }, Modifier.weight(1f)) }
    }
    Spacer(Modifier.height(12.dp))
    Text("播放缓存 · ${formatBytes(usage)}", style = MaterialTheme.typography.labelLarge)
    Text("完整缓存可离线播放，缓存保留 7 天。下载文件单独保存。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(1, 2, 5).forEach { gb -> ChoiceChip("$gb GB", settings.cacheLimitGb == gb, { onLimit(gb) }, Modifier.weight(1f)) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton({ clear = true }) { Text("清理播放缓存") }
        TextButton(onDiagnostics) { Text("播放诊断") }
    }
    if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("清理播放缓存？") },
        text = { Text("已下载文件和歌单会保留。正在读取的缓存将在本次读取结束后清理。") },
        confirmButton = { TextButton({ clear = false; scope.launch {
            withContext(Dispatchers.IO) { AudioCache.get(context).maintain(settings.cacheLimitGb, clear = true) }
            message = "缓存已清理；正在读取的部分将稍后释放"
        } }) { Text("清理") } }, dismissButton = { TextButton({ clear = false }) { Text("取消") } })
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalKinaraTheme.current.colors
    Column {
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = c.text, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
        Surface(shape = kinaraShape(24.dp), color = c.panelRaised.copy(alpha = if (c.isDark) 0.5f else 0.64f)) {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
private fun SourceRow(key: String, label: String, enabled: Boolean, onSource: (String, Boolean) -> Unit, description: String? = null) {
    val c = LocalKinaraTheme.current.colors
    Row(Modifier.fillMaxWidth().toggleable(enabled, role = Role.Switch, onValueChange = { onSource(key, it) })
        .heightIn(min = 60.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = c.text, fontSize = 15.sp)
            if (description != null) Text(description, color = c.textDim, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Switch(enabled, onCheckedChange = null)
    }
}
