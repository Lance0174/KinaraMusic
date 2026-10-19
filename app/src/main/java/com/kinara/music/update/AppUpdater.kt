/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.kinara.music.BuildConfig
import com.kinara.music.online.OnlineTransport
import com.kinara.music.online.mediaHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal fun digestHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

/** Checks actual bytes while streaming; a short response, oversized response or mismatch never becomes an APK. */
internal fun copyUpdate(input: InputStream, output: File, expected: UpdateRelease,
    cancelled: () -> Boolean, progress: (Long) -> Unit) {
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    try {
        output.outputStream().use { sink ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                if (cancelled()) throw CancellationException()
                val size = input.read(buffer)
                if (size < 0) break
                total += size
                check(total <= expected.bytes && total <= MAX_APK_BYTES) { "安装包大小与发布信息不一致" }
                digest.update(buffer, 0, size); sink.write(buffer, 0, size)
                progress(total)
            }
        }
        if (cancelled()) throw CancellationException()
        check(total == expected.bytes && digestHex(digest.digest()) == expected.sha256) { "安装包校验失败，请重新下载" }
    } catch (e: Exception) { output.delete(); throw e }
}

internal class AppUpdater(private val context: Context,
    val policy: ReleasePolicy = ReleasePolicy(BuildConfig.UPDATE_REPOSITORY),
    private val client: OkHttpClient = mediaHttpClient(policy::trustedDownloadUrl)) {
    suspend fun check(): UpdateRelease? {
        val response = OnlineTransport.getText(policy.apiUrl, mapOf("Accept" to "application/vnd.github+json"))
        if (response.code == 404) return null
        check(response.code == 200) { if (response.code == 403 || response.code == 429) "更新服务请求过于频繁，请稍后重试" else "更新服务暂时不可用" }
        return policy.parse(response.body, requireNotNull(ReleaseVersion.parse(BuildConfig.VERSION_NAME)))
    }

    suspend fun download(release: UpdateRelease, progress: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        check(policy.trustedDownloadUrl(release.apkUrl)) { "安装包地址不受信任" }
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File.createTempFile("kinara-", ".part", folder)
        val call = client.newCall(Request.Builder().url(release.apkUrl).header("Accept", "application/octet-stream").build())
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            val result = runCatching {
                call.execute().use { response ->
                    check(response.code == 200) { "安装包下载失败，请稍后重试" }
                    val body = checkNotNull(response.body) { "安装包响应为空" }
                    check(body.contentLength() == -1L || body.contentLength() == release.bytes) { "安装包长度异常" }
                    body.byteStream().use { copyUpdate(it, partial, release, { !continuation.isActive }, progress) }
                }
                verifyPackage(partial, release)
                if (!continuation.isActive) throw CancellationException()
                val ready = File(folder, "Kinara-${release.version}.apk")
                check((!ready.exists() || ready.delete()) && partial.renameTo(ready)) { "无法保存安装包" }
                ready
            }
            if (result.isFailure) partial.delete()
            if (continuation.isActive) continuation.resumeWith(result)
            else result.getOrNull()?.delete()
        }
    }

    suspend fun verifyForInstall(file: File, release: UpdateRelease) = withContext(Dispatchers.IO) {
        check(file.isFile && file.length() == release.bytes) { "安装包已失效，请重新下载" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
        }
        check(digestHex(digest.digest()) == release.sha256) { "安装包校验失败，请重新下载" }
        verifyPackage(file, release)
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File, release: UpdateRelease) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        val archive = checkNotNull(pm.getPackageArchiveInfo(file.absolutePath, flags)) { "安装包无效或未签名" }
        val installed = pm.getPackageInfo(context.packageName, flags)
        fun signatures(info: PackageInfo) = (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            .orEmpty().map { digestHex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
        fun code(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        validateApkIdentity(release, archive.packageName, context.packageName, archive.versionName, code(archive), code(installed), signatures(archive), signatures(installed))
    }
}
