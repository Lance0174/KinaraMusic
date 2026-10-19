/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.account

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BiliLoginLifecycleTest {
    private class Storage : BiliLoginStorage {
        override val account = MutableStateFlow<BiliSession.Account?>(null)
        var saved = 0
        var cleared = 0
        override suspend fun load() {}
        override suspend fun save(cookie: String, account: BiliSession.Account) { saved++; this.account.value = account }
        override suspend fun clear() { cleared++; account.value = null }
    }
    private open class Client : BiliLoginClient {
        override suspend fun generateQr() = BiliApi.Qr("example", "https://account.bilibili.com/test")
        override suspend fun pollQr(key: String) = BiliApi.Poll(86101)
        override suspend fun account(cookie: String) = BiliSession.Account("Example", "123")
    }

    @Test fun closingWhilePollIsInFlightCannotSaveLateCredentials() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val returned = CompletableDeferred<Unit>()
            val storage = Storage()
            val client = object : Client() {
                override suspend fun pollQr(key: String): BiliApi.Poll = withContext(NonCancellable) {
                    returned.await(); BiliApi.Poll(0, "SESSDATA=fake-test-session")
                }
            }
            val vm = BiliAccountViewModel({ client }, storage) { currentTime }
            vm.openLogin(); runCurrent(); advanceTimeBy(2001); runCurrent()
            vm.closeLogin(); returned.complete(Unit); runCurrent()
            assertFalse(vm.login.value.visible); assertEquals(0, storage.saved)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun refreshingKeepsNewQrWhenOlderGenerationReturnsLate() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val returned = CompletableDeferred<Unit>()
            val old = object : Client() {
                override suspend fun generateQr(): BiliApi.Qr = withContext(NonCancellable) {
                    returned.await(); BiliApi.Qr("old", "https://account.bilibili.com/old")
                }
            }
            var attempt = 0
            val vm = BiliAccountViewModel({ if (attempt++ == 0) old else Client() }, Storage()) { currentTime }
            vm.openLogin(); runCurrent(); vm.openLogin(); runCurrent()
            returned.complete(Unit); runCurrent()
            assertEquals("https://account.bilibili.com/test", vm.login.value.qrUrl)
            vm.closeLogin()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun successfulLoginPersistsThenLogoutClearsTheAccount() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val storage = Storage()
            val client = object : Client() { override suspend fun pollQr(key: String) = BiliApi.Poll(0, "SESSDATA=fake-test-session") }
            val vm = BiliAccountViewModel({ client }, storage) { currentTime }
            vm.openLogin(); runCurrent(); advanceTimeBy(2001); runCurrent()
            assertEquals(1, storage.saved); assertTrue(vm.login.value.success)
            assertFalse(vm.login.value.toString().contains("fake-test-session"))
            vm.logout(); runCurrent(); assertNull(storage.account.value); assertEquals(1, storage.cleared)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun unscannedQrExpiresWithoutUnboundedPolling() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var polls = 0
            val client = object : Client() { override suspend fun pollQr(key: String): BiliApi.Poll { polls++; return BiliApi.Poll(86101) } }
            val vm = BiliAccountViewModel({ client }, Storage()) { currentTime }
            vm.openLogin(); runCurrent(); advanceTimeBy(181_000); runCurrent()
            assertTrue(vm.login.value.refreshable); assertNull(vm.login.value.qrUrl)
            assertEquals(90, polls)
        } finally { Dispatchers.resetMain() }
    }
}
