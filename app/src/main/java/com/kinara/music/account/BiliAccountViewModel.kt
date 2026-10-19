/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class BiliLoginState(val visible: Boolean = false, val qrUrl: String? = null,
    val message: String = "正在生成二维码…", val refreshable: Boolean = false, val success: Boolean = false)

class BiliAccountViewModel @JvmOverloads constructor(
    private val clientFactory: () -> BiliLoginClient = { BiliApi() },
    private val storage: BiliLoginStorage = BiliSession,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : ViewModel() {
    val account = storage.account
    private val _login = MutableStateFlow(BiliLoginState())
    val login: StateFlow<BiliLoginState> = _login
    private var job: Job? = null
    private var generation = 0
    init { viewModelScope.launch { storage.load() } }

    fun openLogin() {
        job?.cancel()
        val current = ++generation
        _login.value = BiliLoginState(visible = true)
        job = viewModelScope.launch {
            // Each attempt owns its cookie jar; closing/refreshing cancels the previous attempt.
            val api = clientFactory()
            try {
                val qr = api.generateQr()
                if (current != generation) return@launch
                _login.value = BiliLoginState(true, qr.url, "等待扫码")
                val deadline = clock() + 180_000
                while (isActive && current == generation && clock() < deadline) {
                    delay(2000)
                    val result = api.pollQr(qr.key)
                    if (current != generation) return@launch
                    when (result.code) {
                        86101 -> Unit
                        86090 -> _login.value = _login.value.copy(message = "已扫码，请在哔哩哔哩确认登录")
                        86038 -> break
                        0 -> {
                            val account = api.account(result.cookie)
                            if (current != generation) return@launch
                            try { storage.save(result.cookie, account) } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                throw java.io.IOException("登录信息无法在本机安全保存，请重试")
                            }
                            if (current == generation) _login.value = BiliLoginState(true, message = "已登录 ${account.name}", success = true)
                            return@launch
                        }
                        else -> throw java.io.IOException("扫码登录暂不可用，请刷新重试")
                    }
                }
                if (current == generation) _login.value = BiliLoginState(true, message = "二维码已过期", refreshable = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (current == generation) _login.value = BiliLoginState(true, message = e.message?.take(100) ?: "登录失败，请重试", refreshable = true)
            }
        }
    }

    fun closeLogin() { generation++; job?.cancel(); _login.value = BiliLoginState() }
    fun logout() { closeLogin(); viewModelScope.launch { storage.clear() } }
}
