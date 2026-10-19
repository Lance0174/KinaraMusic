/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0.
 * Folia-inspired lyric focus, implemented with native Compose drawing and real lyric timing.
 */
package com.kinara.music.ui

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kinara.music.data.KinaraSettings
import com.kinara.music.online.*
import com.kinara.music.player.*
import com.kinara.music.ui.theme.LocalKinaraTheme
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

@Composable
internal fun DynamicLyrics(lyrics: List<LyricLine>, player: PlayerUiState, settings: KinaraSettings, onSeek: (Long) -> Unit) {
    val c = LocalKinaraTheme.current.colors
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var systemMotion by remember { mutableStateOf(true) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            systemMotion = android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled()
        }
        lifecycle.addObserver(observer)
        systemMotion = android.os.Build.VERSION.SDK_INT < 26 || android.animation.ValueAnimator.areAnimatorsEnabled()
        onDispose { lifecycle.removeObserver(observer) }
    }
    val motion = !settings.reduceLyricMotion && systemMotion
    val audio = rememberLyricClock(player)
    val position = remember(audio, settings.lyricDelayMs) { derivedStateOf { LyricTiming.position(audio.value, settings.lyricDelayMs) } }
    val active by remember(lyrics, position) { derivedStateOf { LrcParser.currentIndex(lyrics, position.value) } }
    if (lyrics.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("暂时没有歌词\n专心听一会儿吧", color = c.textDim, fontSize = 17.sp, lineHeight = 30.sp, textAlign = TextAlign.Center)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("dynamic_lyrics")) {
        val inset = maxHeight * 0.30f
        val list = key(player.current?.mediaId) {
            rememberLazyListState(initialFirstVisibleItemIndex = active.coerceAtLeast(0))
        }
        var browsing by remember(player.current?.mediaId) { mutableStateOf(false) }
        val browseGesture = remember(player.current?.mediaId) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available.y != 0f) browsing = true
                    return Offset.Zero
                }
            }
        }
        LaunchedEffect(browsing, list.isScrollInProgress) {
            if (browsing && !list.isScrollInProgress) { delay(5_000); browsing = false }
        }
        LaunchedEffect(active, lyrics, motion, browsing) {
            if (!browsing) {
                val target = active.coerceAtLeast(0)
                val lineDuration = (lyrics[target].endMs ?: lyrics.getOrNull(target + 1)?.timeMs ?: Long.MAX_VALUE) - lyrics[target].timeMs
                val visible = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
                // A seek across many lines jumps directly; normal progression eases into the reading anchor.
                if (!motion || active < 0 || lineDuration < 180 || visible == null) list.scrollToItem(target)
                else list.animateScrollBy(visible.offset.toFloat(), tween(
                    (lineDuration / 3).coerceIn(180, 480).toInt(), easing = FastOutSlowInEasing))
            }
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("dynamic_lyric_list").nestedScroll(browseGesture)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.08f to Color.Black,
                    0.88f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
            }, contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = inset, bottom = maxHeight * 0.70f),
            verticalArrangement = Arrangement.spacedBy(30.dp)) {
            itemsIndexed(lyrics, key = { index, line -> "$index:${line.timeMs}" }) { index, line ->
                DynamicLine(line, index == active, index < active, position, settings, motion, {
                    browsing = false
                    onSeek(LyricTiming.seek(line, settings.lyricDelayMs))
                })
            }
        }
        if (browsing) TextButton({ browsing = false }, Modifier.align(Alignment.BottomCenter)
            .background(c.bgBottom.copy(alpha = 0.94f), androidx.compose.foundation.shape.CircleShape)) {
            Text("回到当前歌词", color = c.text, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DynamicLine(line: LyricLine, active: Boolean, past: Boolean, position: State<Long>, settings: KinaraSettings, motion: Boolean, onClick: () -> Unit) {
    val c = LocalKinaraTheme.current.colors
    val short = line.endMs?.let { it - line.timeMs < 180 } == true
    val scale = animateFloatAsState(if (active || !motion) 1f else 0.95f, tween(if (motion && !short) 360 else 0), label = "lyric-focus")
    val opacity = animateFloatAsState(if (active) 1f else if (past) 0.32f else 0.48f, tween(if (motion && !short) 300 else 0), label = "lyric-opacity")
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }
    Column(Modifier.fillMaxWidth().graphicsLayer {
        scaleX = scale.value; scaleY = scale.value; alpha = opacity.value; transformOrigin = TransformOrigin(0f, 0.5f)
    }
        .clickable(onClickLabel = "播放此句", onClick = onClick)) {
        Text(line.text, style = TextStyle(color = c.text.copy(alpha = if (active && line.words.isNotEmpty() && motion) 0.36f else 1f),
            fontSize = 28.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold),
            onTextLayout = { layout = it }, modifier = Modifier.fillMaxWidth().drawWithCache {
                val measured = layout
                var offset = 0
                // Glyph geometry is cached until text/layout changes, never measured on the frame clock.
                val glyphs = line.words.map { word ->
                    val start = offset; offset += word.text.length
                    (start until offset).filter { it < line.text.length && !line.text[it].isLowSurrogate() }
                        .mapNotNull { measured?.getBoundingBox(it) }.filter { it.width > 0f }.distinct()
                }
                val widths = glyphs.map { boxes -> boxes.sumOf { it.width.toDouble() }.toFloat() }
                val mask = Path()
                val glowMask = Path()
                val feather = 10.dp.toPx()
                val glowPadding = 7.dp.toPx()
                val glowRadius = 14.dp.toPx()
                onDrawWithContent {
                    drawContent()
                    if (active && motion && measured != null && line.words.isNotEmpty()) {
                        val now = position.value
                        // Local light follows only real word times, with a short release after the sung word.
                        // The glyph/layout cache is reused; no full-page blur or text measuring on frame ticks.
                        var glowing = -1
                        var strength = 0f
                        line.words.forEachIndexed { index, word ->
                            val rise = ((now - word.startMs).toFloat() / 110f).coerceIn(0f, 1f)
                            val release = ((word.endMs + 220 - now).toFloat() / 220f).coerceIn(0f, 1f)
                            val light = rise * release
                            if (light > strength) { strength = light; glowing = index }
                        }
                        if (glowing >= 0) {
                            glowMask.reset()
                            var width = widths[glowing] * LyricTiming.wordProgress(line.words[glowing], now)
                            glyphs[glowing].forEach { box ->
                                val shown = width.coerceIn(0f, box.width)
                                if (shown > 0f) glowMask.addRect(Rect(box.left - glowPadding, box.top - glowPadding,
                                    box.left + shown + glowPadding, box.bottom + glowPadding))
                                width -= box.width
                            }
                            clipPath(glowMask) {
                                drawText(measured, color = c.accent.copy(alpha = strength * 0.18f),
                                    shadow = Shadow(c.accent.copy(alpha = strength * 0.72f), blurRadius = glowRadius))
                            }
                        }
                        mask.reset()
                        line.words.forEachIndexed { index, word ->
                            val boxes = glyphs[index]
                            var width = widths[index] * LyricTiming.wordProgress(word, now)
                            boxes.forEach { box ->
                                val shown = width.coerceIn(0f, box.width)
                                if (shown >= box.width) mask.addRect(box)
                                else if (shown > 0f) {
                                    val solid = (shown - feather).coerceAtLeast(0f)
                                    if (solid > 0f) mask.addRect(box.copy(right = box.left + solid))
                                    clipRect(box.left + solid, box.top, box.left + shown, box.bottom) {
                                        drawText(measured, brush = Brush.horizontalGradient(listOf(c.text, c.text.copy(alpha = 0f)),
                                            startX = box.left + solid, endX = box.left + shown))
                                    }
                                }
                                width -= box.width
                            }
                        }
                        clipPath(mask) { drawText(measured, color = c.text) }
                    }
                }
            })
        if (settings.showTranslation) line.translation?.let {
            Text(it, color = c.textDim, fontSize = 15.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 9.dp))
        }
        if (settings.showRomanization) line.romanization?.let {
            Text(it, color = c.textDim, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 5.dp))
        }
    }
}
