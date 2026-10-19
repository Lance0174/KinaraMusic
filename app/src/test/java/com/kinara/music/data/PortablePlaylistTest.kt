/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PortablePlaylistTest {
    @Test fun exportsStableIdsWithoutLocalPathsOrSignedArtwork() {
        val online = Track("x", "歌曲", "歌手", "专辑", 12000, "netease", "content://private/one", OnlineKey("netease", "42"), "https://cdn.test/cover?token=secret")
        val local = online.copy(mediaId = "file", onlineKey = null, title = "本地曲目", localPath = "/private/song.wav")
        val text = PortablePlaylist.encode("旅行", listOf(online, local))
        assertFalse(text.contains("content://")); assertFalse(text.contains("/private")); assertFalse(text.contains("secret"))
        val parsed = PortablePlaylist.decode(text)
        assertEquals("旅行", parsed.first); assertEquals(1, parsed.second.size)
        assertEquals("42", parsed.second.first().onlineKey!!.id); assertNull(parsed.second.first().localPath)
    }
    @Test fun solaraArtistArraysAndDuplicateEntriesAreHandled() {
        val track = """{"source":"netease","id":"42","name":"歌","artist":["甲","乙"],"local_uri":"content://untrusted/file"}"""
        val (_, songs) = PortablePlaylist.decode("""{"items":[$track,$track]}""")
        assertEquals(1, songs.size); assertEquals("甲/乙", songs.first().artist); assertNull(songs.first().localPath)
    }
    @Test fun batchPlaylistAppendDeduplicatesWithinTheIncomingBatch() {
        val track = Track("x", "歌", "", "", 0, "local")
        val playlists = addTracksToPlaylist(listOf(Playlist("a", "歌单", 0)), "a", listOf(track, track))
        assertEquals(1, playlists.single().tracks.size)
    }
    @Test fun localUrisRemainContentUrisForPlaybackAndPersistence() {
        val original = Track("uri", "文件", "", "", 0, "local", "content://documents/audio/42")
        val restored = decodeTrackLine(encodeTrackLine(original))!!
        assertEquals("content", com.kinara.music.player.localPlaybackUri(restored.localPath!!).scheme)
        assertEquals(original, restored)
    }
}
