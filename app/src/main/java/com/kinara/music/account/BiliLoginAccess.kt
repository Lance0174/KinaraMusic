/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.account

import kotlinx.coroutines.flow.StateFlow

/** Login protocol and local persistence are injectable, so cancellation can be tested without a real account. */
interface BiliLoginClient {
    suspend fun generateQr(): BiliApi.Qr
    suspend fun pollQr(key: String): BiliApi.Poll
    suspend fun account(cookie: String): BiliSession.Account
}

interface BiliLoginStorage {
    val account: StateFlow<BiliSession.Account?>
    suspend fun load()
    suspend fun save(cookie: String, account: BiliSession.Account)
    suspend fun clear()
}
