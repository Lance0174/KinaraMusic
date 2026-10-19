/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.kinara.music.account.BiliLoginState
import com.kinara.music.ui.theme.LocalKinaraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BiliLoginDialog(state: BiliLoginState, onRefresh: () -> Unit, onClose: () -> Unit) {
    if (!state.visible) return
    val c = LocalKinaraTheme.current.colors
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, state.qrUrl) {
        value = null
        state.qrUrl?.let { url -> value = withContext(Dispatchers.Default) {
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 600, 600, mapOf(EncodeHintType.MARGIN to 4))
            val pixels = IntArray(600 * 600) { i -> if (matrix[i % 600, i / 600]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            android.graphics.Bitmap.createBitmap(pixels, 600, 600, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
        } }
    }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(28.dp), color = c.panelRaised) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("哔哩哔哩扫码登录", color = c.text, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Spacer(Modifier.height(12.dp))
                if (!state.success) Text("用哔哩哔哩扫一扫。也可截图后，\n在扫一扫中从相册识别。", color = c.textDim, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                if (state.success) KinaraIcon(Glyph.CHECK, modifier = Modifier.size(64.dp), tint = c.accent)
                else image?.let { Image(it, "哔哩哔哩官方登录二维码", Modifier.fillMaxWidth().aspectRatio(1f)) }
                    ?: Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        if (!state.refreshable) CircularProgressIndicator() else KinaraIcon(Glyph.REFRESH, modifier = Modifier.size(40.dp), tint = c.textDim)
                    }
                Text(state.message, color = c.text, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 18.dp))
                if (state.refreshable) Button(onRefresh, Modifier.fillMaxWidth()) { Text("刷新二维码") }
                TextButton(onClose, Modifier.fillMaxWidth()) { Text(if (state.success) "完成" else "取消") }
                Text("登录信息仅保存在当前设备", color = c.textDim, fontSize = 11.sp)
            }
        }
    }
}
