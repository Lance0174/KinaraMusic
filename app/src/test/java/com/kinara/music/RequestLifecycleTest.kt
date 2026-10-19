/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music

import com.kinara.music.data.*
import com.kinara.music.online.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RequestLifecycleTest {
    private fun track(id: String) = Track(id, id, "artist", "album", 1000, "netease", onlineKey = OnlineKey("netease", id))
    private class DelayedAccess : OnlineAccess {
        var searches = 0
        override suspend fun search(keyword: String, generation: Long, settings: KinaraSettings, onResult: (OnlineResult, Long) -> Unit) {
            searches++
            // 模拟取消后仍晚到的第三方回调，必须由 ViewModel 的实时代次拒绝。
            withContext(NonCancellable) {
                delay(if (keyword == "old") 3000 else 50)
                onResult(OnlineResult("netease", listOf(OnlineTrack("netease", keyword, keyword, "artist", "album"))), generation)
            }
        }
        override suspend fun lyric(track: Track, settings: KinaraSettings): String? = withContext(NonCancellable) {
            delay(if (track.mediaId == "old") 3000 else 50)
            "[00:01.00]${track.mediaId}"
        }
    }
    @Test fun lateSearchCannotReplaceNewSearchOrClearedInput() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = MainViewModel(RuntimeEnvironment.getApplication(), DelayedAccess()) { true }
            vm.searchOnline("old"); advanceTimeBy(901)
            vm.searchOnline("new"); advanceUntilIdle()
            assertEquals(listOf("new"), vm.online.value.displayTracks.map { it.id })
            vm.searchOnline("old"); advanceTimeBy(901)
            vm.searchOnline(""); advanceUntilIdle()
            assertEquals(OnlineSearchState.Phase.IDLE, vm.online.value.phase)
            assertTrue(vm.online.value.displayTracks.isEmpty())
        } finally { Dispatchers.resetMain() }
    }
    @Test fun lyricsFollowLatestTrackAndClearForLocalPlayback() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = MainViewModel(RuntimeEnvironment.getApplication(), DelayedAccess()) { true }
            vm.loadLyric(track("old")); runCurrent()
            vm.loadLyric(track("new")); advanceUntilIdle()
            assertEquals("new", vm.lyrics.first { it.lines.isNotEmpty() }.lines.single().text)
            assertEquals("netease:new", vm.lyrics.value.identity)
            vm.loadLyric(track("old")); runCurrent()
            vm.loadLyric(track("local").copy(onlineKey = null, localPath = "/music.mp3")); advanceUntilIdle()
            assertTrue(vm.lyrics.value.lines.isEmpty())
        } finally { Dispatchers.resetMain() }
    }
    @Test fun offlineSearchMakesNoRequestAndSettingsKeepLatestChoice() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val access = DelayedAccess()
            val app = RuntimeEnvironment.getApplication()
            val vm = MainViewModel(app, access) { false }
            vm.searchOnline("test"); advanceUntilIdle()
            assertEquals(0, access.searches)
            assertEquals(OnlineSearchState.Phase.ERROR, vm.online.value.phase)
            repeat(20) { index -> vm.setSource("gdFallback", index % 2 == 0) }
            assertFalse(KinaraPrefs.get(app).settings().sources.gdFallback)
        } finally { Dispatchers.resetMain() }
    }
}
