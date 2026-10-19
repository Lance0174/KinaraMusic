package com.kinara.music.online

import okhttp3.Dns
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList

class MediaHttpTest {
    @Test fun redirectsAreCheckedBeforeDnsAndPreserveRangesWithoutCredentials() {
        val requests = CopyOnWriteArrayList<String>()
        val dns = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        val port = server.port
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request.path.orEmpty()
                return when (request.path) {
                    "/redirect", "/escape" -> {
                        val host = if (request.path == "/escape") "untrusted.test" else "two.test"
                        MockResponse().setResponseCode(302).setHeader("Location", "http://$host:$port/audio")
                    }
                    else -> {
                        val safe = request.getHeader("Cookie") == null && request.getHeader("Authorization") == null &&
                            request.getHeader("Range") == "bytes=8-11"
                        MockResponse().setResponseCode(if (safe) 206 else 400)
                            .setHeader("Content-Range", "bytes 8-11/100").setBody("abcd")
                    }
                }
            }
        }
        val client = mediaHttpClient({ it.toHttpUrl().host in setOf("one.test", "two.test") })
            .newBuilder().proxy(Proxy.NO_PROXY)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    dns += hostname; return listOf(InetAddress.getByName("127.0.0.1"))
                }
            }).build()
        fun request(path: String) = Request.Builder().url("http://one.test:$port/$path")
            .header("Cookie", "private=value").header("Authorization", "private")
            .header("Range", "bytes=8-11").build()
        try {
            client.newCall(request("redirect")).execute().use {
                assertEquals(206, it.code); assertEquals("abcd", it.body!!.string())
                assertEquals("bytes 8-11/100", it.header("Content-Range"))
                assertEquals("two.test", it.request.url.host)
            }
            assertThrows(IOException::class.java) { client.newCall(request("escape")).execute().close() }
            assertFalse(dns.contains("untrusted.test"))
            assertEquals(listOf("/redirect", "/audio", "/escape"), requests.toList())
        } finally {
            server.shutdown(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }
}
