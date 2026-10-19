/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 封面加载：
 *  - 本地曲目：在 IO 线程提取内嵌封面，缩小后缓存；失败回退占位。
 *  - 在线曲目：coverUrl（由各平台解析所得，如网易云经 /api/song/detail 取真实 picUrl——
 *    搜索接口只有数字 picId，直接拼 CDN 地址实测 404），Coil 加载并磁盘缓存。
 *  - 占位设计：冰蓝渐变 + 平台色点（有设计感的缺图，而非"无封面"图标）。
 * 纪律：仅供视觉，不阻塞列表；不伪造音频可视化等无法验证的信息。
 */
package com.kinara.music.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.runtime.remember
import coil.request.ImageRequest
import coil.request.CachePolicy
import com.kinara.music.data.NetworkState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kinara.music.data.LocalLibrary
import com.kinara.music.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 封面位：本地曲目显示真实封面，其余显示几何占位。 */
val LocalArtworkOnline = compositionLocalOf { true }

@Composable
fun KinaraArtwork(
    track: Track?,
    size: Dp,
    modifier: Modifier = Modifier,
    corner: Dp = 10.dp,
) {
    val shape = com.kinara.music.ui.theme.kinaraShape(corner)
    Box(modifier.size(size).clip(shape), contentAlignment = Alignment.Center) {
        val localBitmap = if (track != null && track.isLocal && track.localPath != null) {
            rememberLocalArtwork(track)
        } else null
        // A matched network image fills missing local artwork; embedded album art keeps priority.
        val remoteUrl = track?.coverUrl
        val context = LocalContext.current
        val request = remember(remoteUrl, context) { ImageRequest.Builder(context).data(remoteUrl).build() }

        ArtworkPlaceholder(track, Modifier.fillMaxSize())
        when {
            localBitmap != null ->
                Image(localBitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            !remoteUrl.isNullOrBlank() -> key(remoteUrl, LocalArtworkOnline.current) {
                // 在线封面（Coil：磁盘缓存 + 失败自动 error 占位）
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> Unit
        }
    }
}

/** 只缓存已缩小的封面，避免列表滚动反复解析整张音频封面。 */
private val localArtCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}
private val missingArt = LruCache<String, Boolean>(1024)
private val artworkSlots = Semaphore(2)

internal fun extractLocalArtwork(context: Context, track: Track): Bitmap? {
    val path = track.localPath ?: return null
    localArtCache.get(path)?.let { return it }
    if (missingArt.get(path) == true) return null
    val retriever = MediaMetadataRetriever()
    return try {
        if (path.startsWith("content://")) retriever.setDataSource(context, android.net.Uri.parse(path))
        else retriever.setDataSource(path)
        val bytes = retriever.embeddedPicture ?: run { missingArt.put(path, true); return null }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 768) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.also { localArtCache.put(path, it) }
    } catch (_: RuntimeException) { null }
    finally { retriever.release() }
}

@Composable
private fun rememberLocalArtwork(track: Track): ImageBitmap? {
    val ctx = LocalContext.current.applicationContext
    val state by produceState<ImageBitmap?>(initialValue = null, track.mediaId, track.localPath) {
        value = null
        value = withContext(Dispatchers.IO) { artworkSlots.withPermit { extractLocalArtwork(ctx, track)?.asImageBitmap() } }
    }
    return state
}

/** 封面中的实际颜色作为景深基底；没有封面时使用主题的蓝色背景。 */
@Composable
fun rememberArtworkColors(track: Track?): List<Color> {
    val ctx = LocalContext.current.applicationContext
    val colors by produceState<List<Color>>(emptyList(), track?.mediaId, track?.coverUrl) {
        value = emptyList()
        value = withContext(Dispatchers.IO) {
            val bitmap = if (track?.isLocal == true) extractLocalArtwork(ctx, track) else {
                track?.coverUrl?.let { url ->
                    val result = coil.Coil.imageLoader(ctx).execute(ImageRequest.Builder(ctx)
                        .data(url).size(64).allowHardware(false)
                        .networkCachePolicy(if (NetworkState.isOnline(ctx)) CachePolicy.ENABLED else CachePolicy.DISABLED)
                        .build())
                    (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                }
            }
            if (bitmap == null) emptyList() else listOf(0.2f, 0.5f, 0.8f).map { fraction ->
                var red = 0L; var green = 0L; var blue = 0L; var count = 0
                val start = (bitmap.width * (fraction - 0.15f)).toInt().coerceAtLeast(0)
                val end = (bitmap.width * (fraction + 0.15f)).toInt().coerceAtMost(bitmap.width)
                for (x in start until end step 4) for (y in 0 until bitmap.height step 4) {
                    val px = bitmap.getPixel(x, y)
                    red += android.graphics.Color.red(px); green += android.graphics.Color.green(px)
                    blue += android.graphics.Color.blue(px); count++
                }
                Color(android.graphics.Color.rgb((red / count.coerceAtLeast(1)).toInt(),
                    (green / count.coerceAtLeast(1)).toInt(), (blue / count.coerceAtLeast(1)).toInt()))
            }
        }
    }
    return colors
}

/** Original abstract sleeve art when no embedded/remote cover is available. */
@Composable
private fun ArtworkPlaceholder(track: Track?, modifier: Modifier = Modifier) {
    val palettes = listOf(
        listOf(Color(0xFF667BC1), Color(0xFFB2C3EF)),
        listOf(Color(0xFF7A6CA6), Color(0xFFDAC7E5)),
        listOf(Color(0xFF4C8A87), Color(0xFFB6D5C9)),
        listOf(Color(0xFFAA7E61), Color(0xFFE7CEAE)),
    )
    val theme = com.kinara.music.ui.theme.LocalKinaraTheme.current
    val palette = if (theme.kind == com.kinara.music.ui.theme.KinaraThemeKind.INDUSTRIAL) listOf(theme.colors.bgBottom, theme.colors.panelRaised)
        else palettes[((track?.title?.hashCode() ?: 0) and Int.MAX_VALUE) % palettes.size]
    Box(modifier.background(Brush.linearGradient(palette)), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val center = androidx.compose.ui.geometry.Offset(size.width * 0.70f, size.height * 0.42f)
            drawCircle(Color.White.copy(alpha = 0.13f), size.width * 0.62f, center)
            repeat(4) { i -> drawCircle(Color.White.copy(alpha = 0.12f), size.width * (0.24f + i * 0.10f), center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(size.width * 0.003f)) }
            drawCircle(Color.White.copy(alpha = 0.5f), size.width * 0.06f, center)
        }
        KinaraIcon(Glyph.NOTE, modifier = Modifier.fillMaxSize(0.28f).align(Alignment.BottomStart).padding(6.dp), tint = Color.White.copy(alpha = 0.8f))
    }
}
