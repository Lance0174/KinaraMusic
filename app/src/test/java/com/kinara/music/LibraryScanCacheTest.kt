/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music

import com.kinara.music.data.KinaraSettings
import com.kinara.music.data.Track
import com.kinara.music.online.OnlineAccess
import com.kinara.music.online.OnlineResult
import com.kinara.music.ui.LibraryUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 本地曲库扫描的"只扫一次"回归测试。
 *
 * 背景：曲库状态原本挂在 MainActivity 的字段和 lifecycleScope 上。旋转屏幕时 Activity 重建，
 * 状态回到 Loading，onResume 又会触发一次完整的 MediaStore 全量扫描——大曲库下每次转屏都卡一下。
 * 现在状态搬进 ViewModel（随配置变更存活），并在这里锁住行为：
 * 重复调用不能重复扫，用户主动刷新才允许重扫。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryScanCacheTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class NoAccess : OnlineAccess {
        override suspend fun search(keyword: String, generation: Long, settings: KinaraSettings, onResult: (OnlineResult, Long) -> Unit) = Unit
        override suspend fun lyric(track: Track, settings: KinaraSettings): String? = null
    }

    private fun vm(onScan: suspend () -> List<Track>) =
        MainViewModel(RuntimeEnvironment.getApplication(), NoAccess(),
            scanTracks = { onScan() }, networkAvailable = { false })

    @Test
    fun `repeat loadLibrary does not rescan`() = runTest(dispatcher) {
        var scans = 0
        val model = vm { scans++; listOf(Track("a", "a", "artist", "album", 1000, "local")) }

        model.loadLibrary()
        advanceUntilIdle()
        assertTrue(model.library.value is LibraryUiState.Ready)

        // 模拟旋转屏幕后 onResume 再次调用，以及任何额外的重复调用。
        model.loadLibrary()
        model.loadLibrary()
        advanceUntilIdle()

        assertEquals("重复调用不应重复全量扫描", 1, scans)
    }

    @Test
    fun `explicit refresh forces a rescan`() = runTest(dispatcher) {
        var scans = 0
        val model = vm { scans++; listOf(Track("a", "a", "artist", "album", 1000, "local")) }

        model.loadLibrary()
        advanceUntilIdle()
        model.loadLibrary(force = true)
        advanceUntilIdle()

        assertEquals("用户主动刷新必须真的重扫", 2, scans)
    }

    @Test
    fun `empty library counts as settled so it is not rescanned every resume`() = runTest(dispatcher) {
        var scans = 0
        val model = vm { scans++; emptyList() }

        model.loadLibrary(); advanceUntilIdle()
        assertEquals(LibraryUiState.Empty, model.library.value)
        model.loadLibrary(); advanceUntilIdle()

        assertEquals(1, scans)
    }

    @Test
    fun `denied state still allows a scan once permission returns`() = runTest(dispatcher) {
        var scans = 0
        val model = vm { scans++; listOf(Track("a", "a", "artist", "album", 1000, "local")) }

        model.markLibraryDenied()
        assertEquals(LibraryUiState.Denied, model.library.value)

        // 拿到权限后 MainActivity 会调用 loadLibrary()，此时必须真的去扫。
        model.loadLibrary()
        advanceUntilIdle()

        assertEquals(1, scans)
        assertTrue(model.library.value is LibraryUiState.Ready)
    }
}
