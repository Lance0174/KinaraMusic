/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.online

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Validate every target before connecting. Automatic redirects must stay disabled on this client. */
internal class MediaRedirects(
    private val trusted: (String) -> Boolean,
    private val headersForTarget: (String) -> Map<String, String> = { emptyMap() },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        for (redirects in 0..3) {
            val url = request.url.toString()
            if (!trusted(url)) throw IOException("媒体地址不受信任")
            // Cookies/authorization never belong on artwork or audio requests.
            request = request.newBuilder().removeHeader("Cookie").removeHeader("Authorization")
                .removeHeader("Proxy-Authorization").removeHeader("Referer").removeHeader("Origin")
                .apply { headersForTarget(url).forEach { (name, value) -> header(name, value) } }.build()
            val response = chain.proceed(request)
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            val next = response.header("Location")?.let(request.url::resolve)
            response.close()
            if (redirects == 3 || next == null || !trusted(next.toString()) || (request.url.isHttps && !next.isHttps)) {
                throw IOException("媒体重定向目标不受信任或次数过多")
            }
            request = request.newBuilder().url(next).build()
        }
        throw IOException("媒体重定向次数过多")
    }
}

internal fun mediaHttpClient(trusted: (String) -> Boolean,
    headersForTarget: (String) -> Map<String, String> = { emptyMap() }): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false)
    .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
    .addInterceptor(MediaRedirects(trusted, headersForTarget)).build()
