/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music

import android.app.Application
import com.kinara.music.account.BiliSession
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.kinara.music.data.safeCoverUrl
import com.kinara.music.online.mediaHttpClient

class KinaraApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { mediaHttpClient({ safeCoverUrl(it) == it }) }.build()

    override fun onCreate() {
        super.onCreate()
        BiliSession.initialize(this)
    }
}
