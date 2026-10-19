/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.kinara.music.data.safeCoverUrl
import com.kinara.music.online.mediaHttpClient

class KinaraApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { mediaHttpClient({ safeCoverUrl(it) == it }) }.build()

    override fun onCreate() {
        super.onCreate()
        // Upgrade cleanup: credentials are now held only by the private Worker.
        android.util.AtomicFile(java.io.File(noBackupFilesDir, "bili-session.enc")).delete()
        runCatching {
            java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry("kinara.bilibili.session.v1")
        }
    }
}
