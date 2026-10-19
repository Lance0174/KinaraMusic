/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.online

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaylistImporterRegressionTest {
    @Test fun neteaseImportKeepsTheSongAlbumCover() = runBlocking {
        val result = PlaylistImporter.importNetease("1") { url ->
            OnlineTransport.TransportResponse(200, if (url.contains("v3/playlist"))
                """{"code":200,"playlist":{"trackCount":1,"trackIds":[{"id":123}]}}"""
                else """{"songs":[{"id":123,"name":"song","album":{"name":"album","picUrl":"http://p1.music.126.net/cover.jpg"}}]}""")
        }
        val imported = result.tracks.single().toTrack("320")
        assertEquals("https://p1.music.126.net/cover.jpg", imported.coverUrl)
        assertEquals(imported.coverUrl, com.kinara.music.data.decodeTrackLine(com.kinara.music.data.encodeTrackLine(imported))!!.coverUrl)
    }
    @Test fun neteaseReportsHiddenTracksAndPreservesOriginalOrder() = runBlocking {
        val result = PlaylistImporter.importNetease("1") { url ->
            OnlineTransport.TransportResponse(200, if (url.contains("v3/playlist"))
                """{"code":200,"playlist":{"name":"顺序","trackCount":5,"trackIds":[{"id":3},{"id":1},{"id":2},{"id":1}]}}"""
                else """{"songs":[{"id":1,"name":"一"},{"id":3,"name":"三"},{"id":1,"name":"重复"},{"id":99,"name":"多余"}]}""")
        }
        assertEquals(listOf("3", "1"), result.tracks.map { it.id })
        assertEquals(5, result.total); assertEquals(3, result.failed)
        assertTrue(result.message.contains("3 首不可读取"))
    }
    @Test fun neteasePreservesPartialResultsWhenLaterBatchFails() = runBlocking {
        var requests = 0
        val ids = (1..201).joinToString(",") { """{"id":$it}""" }
        val result = PlaylistImporter.importNetease("1") { _ ->
            requests++
            when (requests) {
                1 -> OnlineTransport.TransportResponse(200, """{"code":200,"playlist":{"trackCount":201,"trackIds":[$ids]}}""")
                2 -> OnlineTransport.TransportResponse(200, """{"songs":[{"id":1,"name":"一"}]}""")
                else -> throw java.io.IOException("network failed")
            }
        }
        assertEquals(1, result.imported); assertEquals(200, result.failed); assertTrue(result.message.contains("中断"))
    }
    private fun page(vararg mids: String) = """{"code":0,"cdlist":[{"dissname":"Playlist","songnum":3,"songlist":[${mids.joinToString(",") { """{"songmid":"$it","songname":"$it"}""" }}]}]}"""
    @Test fun usesPlaylistEndpointAndActualPageLength() = runBlocking {
        val urls = mutableListOf<String>()
        val r = PlaylistImporter.importQQ("123") { url, headers ->
            urls += url
            assertTrue(url.contains("fcg_ucc_getcdinfo_byids_cp.fcg"))
            assertTrue(url.contains("disstid=123"))
            assertTrue(headers["Referer"]!!.contains("playlist"))
            OnlineTransport.TransportResponse(200, if (urls.size == 1) page("a", "b") else page("b", "c"))
        }
        assertEquals(listOf("a", "b", "c"), r.tracks.map { it.id })
        assertTrue(urls[1].contains("song_begin=2"))
        assertEquals(0, r.failed)
    }
    @Test fun partialFailurePreservesSongsAndReportsFailure() = runBlocking {
        var calls = 0
        val r = PlaylistImporter.importQQ("123") { _, _ ->
            if (++calls == 1) OnlineTransport.TransportResponse(200, page("a")) else OnlineTransport.TransportResponse(503, "")
        }
        assertEquals(1, r.imported); assertEquals(2, r.failed); assertTrue(r.message.contains("503"))
    }
    @Test fun repeatedPageStopsWithoutClaimingCompletion() = runBlocking {
        var calls = 0
        val r = PlaylistImporter.importQQ("123") { _, _ -> calls++; OnlineTransport.TransportResponse(200, page("a")) }
        assertEquals(2, calls); assertEquals(1, r.imported); assertTrue(r.message.contains("分页"))
    }
}
