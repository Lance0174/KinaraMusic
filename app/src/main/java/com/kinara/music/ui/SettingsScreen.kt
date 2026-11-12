/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import com.kinara.music.data.PlayerVisualMode
import com.kinara.music.ui.theme.*
import androidx.compose.ui.platform.LocalContext
import com.kinara.music.player.AudioCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.extra.SuperSpinner
import top.yukonga.miuix.kmp.extra.SpinnerEntry
import top.yukonga.miuix.kmp.extra.SuperSwitch

@Composable
fun SettingsScreen(settings: KinaraSettings, onTheme: (KinaraThemeKind) -> Unit, onDark: (String) -> Unit,
    onSource: (String, Boolean) -> Unit, onBitrate: (String) -> Unit = {},
    onCacheLimit: (Int) -> Unit = {}, onDiagnostics: () -> Unit = {},
    onPreferences: ((KinaraSettings) -> KinaraSettings) -> Unit = {}, onPreviewLyrics: (() -> Unit)? = null) {
    val c = LocalKinaraTheme.current.colors
    var licenses by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(bottom = LocalDockInset.current).testTag("settings_scroll")
        .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingsSection("外观与主题") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("浅色", "深色", "工业").forEachIndexed { index, label ->
                    val selected = if (index == 2) settings.theme == KinaraThemeKind.INDUSTRIAL else
                        settings.theme == KinaraThemeKind.DEFAULT && settings.darkMode == if (index == 0) KinaraDarkMode.LIGHT else KinaraDarkMode.DARK
                    ChoiceChip(label, selected, {
                        onPreferences { it.copy(theme = if (index == 2) KinaraThemeKind.INDUSTRIAL else KinaraThemeKind.DEFAULT,
                            darkMode = if (index == 0) KinaraDarkMode.LIGHT else KinaraDarkMode.DARK) }
                    }, Modifier.weight(1f))
                }
            }
            SettingsChoice("明暗模式", KinaraDarkMode.entries.map { it.label }, settings.darkMode.ordinal,
                onSelect = { onDark(KinaraDarkMode.entries[it].name) })
            SettingsChoice("界面质感", KinaraSurfaceStyle.entries.map { it.label }, settings.surfaceStyle.ordinal,
                summary = "简洁卡片，或带背景模糊的液态玻璃", onSelect = { index -> onPreferences { it.copy(surfaceStyle = KinaraSurfaceStyle.entries[index]) } })
        }
        SettingsSection("歌词与歌曲信息") {
            SettingsChoice("歌词视觉模式", LyricRenderer.entries.map { it.label }, settings.lyricRenderer.ordinal,
                summary = "经典逐行、逐字辉光与多样动态编排", onSelect = { index ->
                    onPreferences { it.copy(lyricRenderer = LyricRenderer.entries[index], playerVisualMode = PlayerVisualMode.LYRICS) }
                })
            Text("在播放页左右滑动，或轻点底部切换封面与歌词", color = c.textDim, fontSize = 12.sp)
            if (settings.lyricRenderer == LyricRenderer.FOLIA_LUMIERE) {
                Spacer(Modifier.height(12.dp))
                SettingsChoice("视觉编排", LyricVisualStyle.entries.map { it.label }, settings.lyricVisualStyle.ordinal,
                    summary = settings.lyricVisualStyle.description, onSelect = { index ->
                        onPreferences { it.copy(lyricVisualStyle = LyricVisualStyle.entries[index], playerVisualMode = PlayerVisualMode.LYRICS) }
                    })
                SettingsChoice("动态画质", LumiereQuality.entries.map { it.label }, settings.lumiereQuality.ordinal,
                    onSelect = { index -> onPreferences { it.copy(lumiereQuality = LumiereQuality.entries[index]) } })
                Text("在播放页轻点「横屏」进入沉浸视界，返回可退出。", color = c.textDim, fontSize = 12.sp)
            }
            if (onPreviewLyrics != null) TextButton(onPreviewLyrics, Modifier.fillMaxWidth()) { Text("查看当前歌曲效果") }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = c.textDim.copy(alpha = 0.12f))
            SourceRow("translation", "显示译文", settings.showTranslation, { _, value -> onPreferences { it.copy(showTranslation = value) } })
            SourceRow("romanization", "显示罗马音", settings.showRomanization, { _, value -> onPreferences { it.copy(showRomanization = value) } })
            SourceRow("motion", "减少歌词动效", settings.reduceLyricMotion, { _, value -> onPreferences { it.copy(reduceLyricMotion = value) } }, "保留所选视觉编排与歌词高亮，减少镜头运动和连续动画")
            SourceRow("info", "联网更新歌曲信息", settings.autoTrackInfo, { _, value -> onPreferences { it.copy(autoTrackInfo = value) } }, "自动补全专辑封面；本地歌曲仅在信息明确匹配时更新，不修改音频文件")
            Text("全局歌词时间 · ${if (settings.lyricDelayMs > 0) "+" else ""}${settings.lyricDelayMs} ms", color = c.text)
            Text("单首歌不准时，请在播放页菜单中选择「歌词校准」", color = c.textDim, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ onPreferences { it.copy(lyricDelayMs = (it.lyricDelayMs - 100).coerceAtLeast(-10_000)) } }) { Text("提前 0.1 秒") }
                TextButton({ onPreferences { it.copy(lyricDelayMs = 0) } }) { Text("重置") }
                TextButton({ onPreferences { it.copy(lyricDelayMs = (it.lyricDelayMs + 100).coerceAtMost(10_000)) } }) { Text("延后 0.1 秒") }
            }
        }
        SettingsSection("音乐来源") {
            Text("搜索会合并各来源的歌曲；播放失败时自动尝试其他来源。", color = c.textDim, fontSize = 12.sp)
            SourceRow("netease", "网易云音乐", settings.sources.netease, onSource)
            SourceRow("qq", "QQ音乐", settings.sources.qq, onSource, "播放可使用聚合匹配")
            SourceRow("kugou", "酷狗音乐", settings.sources.kugou, onSource, "播放可使用聚合匹配")
            SourceRow("kuwo", "酷我音乐", settings.sources.kuwo, onSource)
            SourceRow("bilibili", "哔哩哔哩", settings.sources.bilibili, onSource)
            SourceRow("solara", "Solara", settings.sources.solara, onSource, if (settings.solaraBaseUrl.isNotBlank()) "已配置服务" else "需由部署者配置服务地址和凭据")
            HorizontalDivider(color = c.textDim.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 6.dp))
            SourceRow("gdFallback", "聚合补充", settings.sources.gdFallback, onSource, "为部分来源匹配同名、同艺术家的音频")
        }
        SettingsSection("播放与缓存") {
            PlaybackPreferences(settings, onBitrate, onCacheLimit, onDiagnostics)
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
        // 原来在组合期间直接 assets.open().readText()：既是主线程阻塞 I/O，
        // 又会在资源缺失时直接抛异常把设置页打崩。改成 IO 读取 + 失败降级。
        var notice by remember(file) { mutableStateOf("正在读取…") }
        LaunchedEffect(file) {
            notice = withContext(Dispatchers.IO) {
                val path = if (file == "miuix-Apache-2.0.txt") "licenses/$file" else "folia/$file"
                runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }
                    .getOrElse { "无法读取该许可文件（$file）" }
            }
        }
        AlertDialog(onDismissRequest = { licenses = false }, title = { Text("开源许可") }, text = {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ file = "KINARA-LICENSE.txt" }) { Text("Kinara") }
                    TextButton({ file = "FOLIA-LICENSE.txt" }) { Text("渲染组件") }
                    TextButton({ file = "THIRD-PARTY.txt" }) { Text("第三方") }
                }
                TextButton({ file = "miuix-Apache-2.0.txt" }) { Text("界面组件许可") }
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
        // 192 一直是数据模型里合法的一档（PortablePlaylist 导入时接受 128/192/320/flac），
        // 但设置页只给了三档，导致"能导入却选不出来"。这里补齐，与数据模型对齐。
        listOf("128", "192", "320", "flac").forEach { bitrate -> ChoiceChip(bitrate.uppercase(), settings.preferredBitrate == bitrate, { onBitrate(bitrate) }, Modifier.weight(1f)) }
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
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.textDim, modifier = Modifier.padding(start = 16.dp, bottom = 9.dp))
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp) {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
private fun SourceRow(key: String, label: String, enabled: Boolean, onSource: (String, Boolean) -> Unit, description: String? = null) {
    SuperSwitch(checked = enabled, onCheckedChange = { onSource(key, it) }, title = label, summary = description,
        insideMargin = PaddingValues(vertical = 12.dp))
}

@Composable
private fun SettingsChoice(title: String, items: List<String>, selected: Int, summary: String? = null, onSelect: (Int) -> Unit) {
    // Dialog mode also supports large text and avoids the popup's duplicate measurement semantics.
    SuperSpinner(items = items.map { SpinnerEntry(title = it) }, selectedIndex = selected, title = title, dialogButtonString = "完成",
        summary = summary, insideMargin = PaddingValues(vertical = 14.dp), onSelectedIndexChange = onSelect)
}
