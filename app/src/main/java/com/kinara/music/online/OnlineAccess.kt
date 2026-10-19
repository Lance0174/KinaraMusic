/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.online

import com.kinara.music.data.KinaraSettings
import com.kinara.music.data.Track

/** 界面请求边界；状态与取消策略由 ViewModel 持有，协议实现可单独验证。 */
interface OnlineAccess {
    suspend fun search(keyword: String, generation: Long, settings: KinaraSettings, onResult: (OnlineResult, Long) -> Unit)
    suspend fun lyric(track: Track, settings: KinaraSettings): String?
    suspend fun lyricDocument(track: Track, settings: KinaraSettings): LyricDocument =
        lyric(track, settings)?.let(LrcParser::parseDocument) ?: LyricDocument()

    object Default : OnlineAccess {
        override suspend fun search(keyword: String, generation: Long, settings: KinaraSettings, onResult: (OnlineResult, Long) -> Unit) {
            OnlineRepository { settings.sources }.search(keyword, generation,
                if (settings.sources.solara) settings.solaraBaseUrl to settings.solaraSecret else null, onResult)
        }
        override suspend fun lyric(track: Track, settings: KinaraSettings): String? =
            OnlinePlayback(OnlineRepository { settings.sources }).lyricFor(track, settings)

        override suspend fun lyricDocument(track: Track, settings: KinaraSettings): LyricDocument {
            val key = track.onlineKey ?: return LyricDocument()
            if (!settings.sources.isEnabled(key.platform)) return LyricDocument()
            return if (key.platform == "netease") NeteaseSource.lyrics(key.id)
            else super.lyricDocument(track, settings)
        }
    }
}
