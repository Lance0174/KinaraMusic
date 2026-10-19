/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.update

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, INSTALLING, FAILED }
data class UpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val release: UpdateRelease? = null,
    val downloaded: Long = 0, val apk: File? = null, val message: String = "")

class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val updater = AppUpdater(app)
    private val mutable = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = mutable
    private var job: Job? = null
    @Volatile private var generation = 0

    fun check() {
        if (job?.isActive == true) return
        mutable.value = UpdateState(phase = UpdatePhase.CHECKING)
        job = viewModelScope.launch {
            try {
                val release = updater.check()
                mutable.value = if (release == null) UpdateState(UpdatePhase.CURRENT, message = "当前没有可用的正式更新")
                    else UpdateState(UpdatePhase.AVAILABLE, release)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = UpdateState(UpdatePhase.FAILED, message = "检查更新失败，请稍后重试，也可查看发布页面") }
        }
    }

    fun download() {
        if (job?.isActive == true) return
        val release = mutable.value.release ?: return
        val request = ++generation
        mutable.value = UpdateState(UpdatePhase.DOWNLOADING, release)
        job = viewModelScope.launch {
            try {
                val file = updater.download(release) { downloaded ->
                    mutable.update { current ->
                        if (request == generation && current.phase == UpdatePhase.DOWNLOADING) current.copy(downloaded = downloaded) else current
                    }
                }
                mutable.value = UpdateState(UpdatePhase.READY, release, release.bytes, file, "下载完成，校验通过")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.value = UpdateState(UpdatePhase.FAILED, release, message = "下载或校验失败，请重试；正式更新须与当前应用签名一致")
            }
        }
    }

    fun cancel() {
        generation++; job?.cancel(); job = null
        mutable.value = mutable.value.release?.let { UpdateState(UpdatePhase.AVAILABLE, it) } ?: UpdateState()
    }

    fun install() {
        if (job?.isActive == true) return
        val before = mutable.value
        val file = before.apk ?: return
        val release = before.release ?: return
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            runCatching { app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onSuccess { mutable.value = before.copy(message = "允许安装后返回，再轻点「安装更新」") }
                .onFailure { mutable.value = before.copy(message = "请在系统设置中允许 Kinara 安装应用后重试") }
            return
        }
        mutable.value = before.copy(phase = UpdatePhase.INSTALLING, message = "正在核对安装包")
        job = viewModelScope.launch {
            try {
                updater.verifyForInstall(file, release)
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
                app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                mutable.value = before.copy(message = "请在系统安装界面完成更新")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.value = UpdateState(UpdatePhase.FAILED, release, message = "无法安装此更新，请重新下载或查看发布说明")
            }
        }
    }

    fun openReleases() {
        val app = getApplication<Application>()
        runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mutable.value.release?.pageUrl ?: updater.policy.pageUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { mutable.value = mutable.value.copy(message = "未找到可打开发布页面的浏览器") }
    }
}
