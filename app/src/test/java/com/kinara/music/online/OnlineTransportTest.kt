package com.kinara.music.online

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OnlineTransportTest {
    private open class Connection(url: URL, private val code: Int, private val location: String? = null) : HttpURLConnection(url) {
        var disconnected = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode() = code
        override fun getHeaderField(name: String?) = if (name == "Location") location else null
        override fun getHeaderFields(): Map<String, List<String>> = emptyMap()
        override fun getInputStream() = "ok".byteInputStream()
    }

    @Test fun `real request loop follows multiple hops with one cancellation handler`() = runBlocking {
        val connections = mutableListOf<Connection>()
        val result = OnlineTransport.getTextUsing("https://api.example.test/one", mapOf("Cookie" to "test=value")) { url ->
            Connection(url, if (connections.size < 2) 302 else 200, "/next${connections.size}").also { connections += it }
        }
        assertEquals("ok", result.body)
        assertEquals(3, connections.size)
        assertTrue(connections.all { it.disconnected && it.getRequestProperty("Cookie") == "test=value" })
    }

    @Test fun `cross host redirect is rejected before opening second connection`() = runBlocking {
        var opened = 0
        try {
            OnlineTransport.getTextUsing("https://api.example.test/one") { url ->
                opened++; Connection(url, 302, "https://evil.example.test/leak")
            }
            fail("redirect must be rejected")
        } catch (_: TransportException) { assertEquals(1, opened) }
    }

    @Test fun `cancelling during second hop disconnects the current connection`() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        var opened = 0
        val job = launch(Dispatchers.Default) {
            OnlineTransport.getTextUsing("https://api.example.test/one") { url ->
                if (opened++ == 0) Connection(url, 302, "/two")
                else object : Connection(url, 200) {
                    override fun getResponseCode(): Int { entered.countDown(); check(released.await(5, TimeUnit.SECONDS)); return 200 }
                    override fun disconnect() { super.disconnect(); released.countDown() }
                }
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            job.cancel()
            assertTrue(released.await(5, TimeUnit.SECONDS))
            withTimeout(5000) { job.join() }
            assertTrue(job.isCancelled)
        } finally { job.cancel(); released.countDown() }
    }

    @Test fun `invalid initial URL never opens a connection`() = runBlocking {
        for (url in listOf("file:///private/file", "https://user:pass@api.example.test", "https://api.example.test:80", "broken")) {
            try { OnlineTransport.getTextUsing(url) { error("must not connect") }; fail(url) }
            catch (_: TransportException) { }
        }
    }
    @Test fun `redirects stay on the same host and never downgrade https`() {
        assertEquals("https://api.example.test/v2", redirectedUrl("https://api.example.test/v1", "/v2"))
        assertEquals("https://api.example.test/next", redirectedUrl("http://api.example.test/start", "https://api.example.test/next"))
        assertNull(redirectedUrl("https://api.example.test/start", "http://api.example.test/leak"))
        assertNull(redirectedUrl("https://api.example.test/start", "https://evil.example.test/leak"))
        assertNull(redirectedUrl("https://api.example.test/start", "file:///private/token"))
    }

    @Test fun `worker bases cannot smuggle a path or cleartext endpoint`() {
        assertEquals("https://worker.example.test", BiliWorkerClient.normalizeBase("https://worker.example.test/"))
        assertNull(BiliWorkerClient.normalizeBase("https://worker.example.test/path"))
        assertNull(BiliWorkerClient.normalizeBase("http://worker.example.test"))
        assertNull(BiliWorkerClient.normalizeBase("https://worker.example.test:8443"))
        assertNull(BiliWorkerClient.normalizeBase("https://worker.example.test//"))
    }
}
