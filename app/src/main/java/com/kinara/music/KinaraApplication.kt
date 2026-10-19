/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music

import android.app.Application
import com.kinara.music.account.BiliSession

class KinaraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BiliSession.initialize(this)
    }
}
