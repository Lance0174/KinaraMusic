/*
 * Kinara — Android music client.
 * Copyright (C) 2026 Kinara contributors. GPL-3.0.
 *
 * 网络可用性：脱机时在线音源暂停、封面只走缓存、本地功能不受影响。
 * 说明：用系统 ConnectivityManager 的 validated 网络判断（不自己 ping，避免额外流量与误判）。
 */
package com.kinara.music.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkState {

    /** 是否存在已验证可上网的网络（API 23+）。 */
    fun isOnline(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            true // 判定失败时按在线处理（不因权限/系统差异误禁在线功能）
        }
    }
}
