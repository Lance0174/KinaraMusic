/* Kinara Android bridge — Copyright (C) 2026 Kinara contributors. AGPL-3.0.
 * The bundled Folia Lumiere renderer is © 2026 chthollyphile, AGPL-3.0.
 */
package com.kinara.music.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kinara.music.data.KinaraSettings
import com.kinara.music.online.*
import com.kinara.music.player.*
import com.kinara.music.ui.theme.KinaraThemeKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** The WebView can read only packaged assets. It has no native JS interface or network access. */
internal class FoliaAssetClient(private val context: android.content.Context,
    private val loaded: () -> Unit, private val failed: () -> Unit) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
        val uri = request?.url
        val name = uri?.path?.removePrefix("/folia/")
        if (uri?.scheme != "https" || uri.host != "kinara.invalid" || uri.path?.startsWith("/folia/") != true ||
            name !in setOf("index.html", "lumiere.js", "NOTICE.txt", "FOLIA-LICENSE.txt", "THIRD-PARTY.txt", "KINARA-LICENSE.txt")) {
            return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        }
        return try {
            WebResourceResponse(if (name == "lumiere.js") "application/javascript" else if (name == "index.html") "text/html" else "text/plain",
                "UTF-8", 200, "OK", emptyMap(), context.assets.open("folia/$name"))
        } catch (_: Exception) {
            WebResourceResponse("text/plain", "UTF-8", 404, "Missing", emptyMap(), ByteArrayInputStream(byteArrayOf()))
        }
    }
    override fun onPageFinished(view: WebView?, url: String?) { if (url == FOLIA_URL) loaded() }
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean { failed(); return true }
    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (request?.isForMainFrame == true) failed()
    }
}
private const val FOLIA_URL = "https://kinara.invalid/folia/index.html"

internal fun foliaSongJson(lyrics: List<LyricLine>, player: PlayerUiState, settings: KinaraSettings,
    coverColors: List<Color> = emptyList()): String {
    val track = player.actualTrack ?: player.current
    val duration = player.durationMs.takeIf { it > 0 } ?: track?.durationMs ?: 0
    return JSONObject().put("identity", com.kinara.music.data.lyricIdentity(track).orEmpty())
        .put("title", track?.title.orEmpty()).put("artist", track?.artist.orEmpty()).put("album", track?.album.orEmpty())
        .put("durationMs", duration).put("quality", settings.lumiereQuality.wireName)
        .put("visualStyle", settings.lyricVisualStyle.wireName)
        .put("coverColors", JSONArray(coverColors.take(3).map { "#%06x".format(it.toArgb() and 0xffffff) }))
        .put("industrial", settings.theme == KinaraThemeKind.INDUSTRIAL).put("reduceMotion", settings.reduceLyricMotion)
        .put("lines", JSONArray().apply { lyrics.forEachIndexed { index, line ->
            val end = line.endMs ?: lyrics.getOrNull(index + 1)?.timeMs ?: duration.takeIf { it > line.timeMs } ?: (line.timeMs + 5000)
            put(JSONObject().put("startTime", line.timeMs / 1000.0).put("endTime", end.coerceAtLeast(line.timeMs + 1) / 1000.0)
                .put("fullText", line.text).put("words", JSONArray().apply { line.words.forEach { word ->
                    put(JSONObject().put("text", word.text).put("startTime", word.startMs / 1000.0).put("endTime", word.endMs / 1000.0))
                } }))
        } }).toString()
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun FoliaLyrics(lyrics: List<LyricLine>, player: PlayerUiState, settings: KinaraSettings, onSeek: (Long) -> Unit) {
    var reload by remember { mutableIntStateOf(0) }
    // A failed WebView must not permanently mask later choices with the native fallback.
    key(settings.lyricVisualStyle, settings.lumiereQuality, reload) {
        FoliaLyricsContent(lyrics, player, settings, onSeek, onReload = { reload++ })
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun FoliaLyricsContent(lyrics: List<LyricLine>, player: PlayerUiState, settings: KinaraSettings,
    onSeek: (Long) -> Unit, onReload: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var systemMotion by remember { mutableStateOf(true) }
    DisposableEffect(lifecycle) {
        fun update() { systemMotion = android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled() }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer) }
    }
    val progress = LocalPlayerProgress.current
    val recording = com.kinara.music.data.lyricIdentity(player.actualTrack ?: player.current).orEmpty()
    val audio = rememberLyricClock(player)
    val position = remember(audio, settings.lyricDelayMs) { derivedStateOf { LyricTiming.position(audio.value, settings.lyricDelayMs) } }
    val active by remember(lyrics, position) { derivedStateOf { LrcParser.currentIndex(lyrics, position.value) } }
    var loaded by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val config = remember(settings, systemMotion) { settings.copy(reduceLyricMotion = settings.reduceLyricMotion || !systemMotion) }
    val coverColors = rememberArtworkColors(player.actualTrack ?: player.current)
    val song = remember(lyrics, player.actualTrack, player.current, player.durationMs, config.lyricRenderer, config.lumiereQuality, config.lyricVisualStyle, config.theme, config.reduceLyricMotion, coverColors) {
        foliaSongJson(lyrics, player, config, coverColors)
    }
    LaunchedEffect(web, loaded, song) {
        if (!loaded || failed) return@LaunchedEffect
        ready = false
        web?.evaluateJavascript("window.KinaraFolia.setSong($song)", null)
        repeat(80) {
            if (ready || failed) return@LaunchedEffect
            // A loaded WebGL runtime is not enough: Android can give a percentage-height
            // document a zero-height stage. Confirm the canvas actually occupies the viewport.
            web?.evaluateJavascript("window.KinaraFolia?.displayStatus()") { status ->
                if (status == "\"ready\"") ready = true
                if (status == "\"failed\"") failed = true
            }
            delay(250)
        }
        failed = true
    }
    LaunchedEffect(web, loaded, failed, recording, player.playing, player.buffering, settings.lyricDelayMs, config.reduceLyricMotion) {
        if (!loaded || failed) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val sample = progress?.value
                val valid = sample?.belongsTo(player.current?.mediaId, recording) ?: true
                val lease = if (sample != null && sample.sampledAtMs > 0) (1000 - (now - sample.sampledAtMs)).coerceIn(0, 1000) else 0L
                val audioPosition = if (valid) sample?.estimatedPosition(now) ?: player.positionMs else 0
                val bands = if (!config.reduceLyricMotion && player.playing && !player.buffering) ReactiveAudio.at(audioPosition) else AudioBands()
                val clock = JSONObject().put("positionMs", audioPosition)
                    .put("identity", recording)
                    .put("durationMs", sample?.durationMs ?: player.durationMs).put("speed", sample?.speed ?: 1f)
                    .put("advancing", valid && player.playing && !player.buffering && sample?.advancing == true)
                    .put("validForMs", lease).put("sentAtMs", System.currentTimeMillis()).put("delayMs", settings.lyricDelayMs)
                    .put("bands", JSONObject().put("power", bands.power).put("bass", bands.bass).put("treble", bands.treble))
                web?.evaluateJavascript("window.KinaraFolia.clock($clock);window.KinaraFolia.status") { status ->
                    if (status == "\"failed\"") failed = true
                }
                delay(100)
            }
        }
    }
    DisposableEffect(web, lifecycle) {
        val view = web
        val observer = LifecycleEventObserver { _, _ ->
            val visible = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (visible) view?.onResume()
            view?.evaluateJavascript("window.KinaraFolia?.setForeground($visible)", null)
            if (!visible) view?.onPause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(lifecycle, failed, config.reduceLyricMotion) {
        var subscribed = false
        fun update() {
            val visible = !failed && !config.reduceLyricMotion && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (visible != subscribed) {
                subscribed = visible
                if (visible) ReactiveAudio.consumers.incrementAndGet() else ReactiveAudio.consumers.decrementAndGet()
            }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); if (subscribed) ReactiveAudio.consumers.decrementAndGet() }
    }
    Box(Modifier.fillMaxSize().testTag("folia_lyrics").then(if (!failed) Modifier.background(androidx.compose.ui.graphics.Color(0xff070b12)) else Modifier)) {
        if (failed) {
            DynamicLyrics(lyrics, player, settings, onSeek)
            Column(Modifier.align(Alignment.TopCenter).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("动态视界加载失败，暂用逐字辉光", color = com.kinara.music.ui.theme.LocalKinaraTheme.current.colors.textDim, fontSize = 12.sp)
                TextButton(onReload) { Text("重新加载动态视界") }
            }
        } else {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = {
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    this.settings.javaScriptEnabled = true
                    this.settings.allowFileAccess = false; this.settings.allowContentAccess = false
                    this.settings.domStorageEnabled = false; this.settings.blockNetworkLoads = true
                    this.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    this.settings.setSupportZoom(false)
                    isFocusable = false
                    webViewClient = FoliaAssetClient(context, { loaded = true }, { failed = true })
                    web = this
                    loadUrl(FOLIA_URL)
                }
            }, onRelease = { view ->
                view.evaluateJavascript("window.KinaraFolia?.destroy()", null)
                view.stopLoading(); view.onPause(); view.destroy()
                web = null
            })
            val line = lyrics.getOrNull(active)
            // Compose handles taps/swipes above the canvas, so WebView cannot swallow pager gestures.
            Box(Modifier.fillMaxSize().semantics { contentDescription = line?.text ?: "Kinara 动态视界" }
                .pointerInput(line, settings.lyricDelayMs) { detectTapGestures { line?.let { onSeek(LyricTiming.seek(it, settings.lyricDelayMs)) } } })
            Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (!ready) Text("动态视界正在展开…", color = androidx.compose.ui.graphics.Color(0xffded7ca), fontSize = 13.sp)
                else if (lyrics.isEmpty()) Text("暂时没有歌词 · 继续聆听", color = androidx.compose.ui.graphics.Color(0xffded7ca), fontSize = 13.sp)
                if (config.reduceLyricMotion) Text("已减少动效 · 保留所选编排", color = androidx.compose.ui.graphics.Color(0xffb0b5bf), fontSize = 12.sp)
                if (settings.showTranslation) line?.translation?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xffded7ca), fontSize = 13.sp, textAlign = TextAlign.Center) }
                if (settings.showRomanization) line?.romanization?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xffb0b5bf), fontSize = 12.sp, textAlign = TextAlign.Center) }
            }
        }
    }
}
