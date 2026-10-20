package com.kinara.music.player

import com.kinara.music.online.mediaHttpClient
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class AudioProbeTest {
    @Test fun realGetRejectsErrorPagesAndChecksRangeBytesWithoutKeepingCredentials() = runBlocking {
        val server = MockWebServer()
        server.start()
        val client = mediaHttpClient({ it.startsWith(server.url("/").toString()) })
            .newBuilder().callTimeout(2, TimeUnit.SECONDS).build()
        try {
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setBody("<html>not music</html>"))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-63/1000")
                .setBody("ID3" + "a".repeat(61)))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 8-71/1000")
                .setBody("ID3" + "a".repeat(61)))
            assertFalse(probeAudioUrl(server.url("/denied").toString(), client))
            assertFalse(probeAudioUrl(server.url("/html").toString(), client))
            assertTrue(probeAudioUrl(server.url("/audio").toString(), client))
            assertFalse(probeAudioUrl(server.url("/wrong-range").toString(), client))
            repeat(4) {
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("GET", request.method)
                assertEquals("bytes=0-63", request.getHeader("Range"))
                assertNull(request.getHeader("Cookie")); assertNull(request.getHeader("Authorization"))
            }
        } finally { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun cancellingASlowBodyCancelsTheUnderlyingHttpCall() = runBlocking {
        val server = MockWebServer()
        server.start()
        val client = mediaHttpClient({ true }).newBuilder().callTimeout(2, TimeUnit.SECONDS).build()
        try {
            server.enqueue(MockResponse().setBody("ID3" + "a".repeat(61)).setBodyDelay(1500, TimeUnit.MILLISECONDS))
            val pending = async { probeAudioUrl(server.url("/slow").toString(), client) }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)) }
            pending.cancelAndJoin()
            withTimeout(1000) { while (client.dispatcher.runningCallsCount() > 0) delay(10) }
            assertTrue(pending.isCancelled)
        } finally { server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
    }
}
