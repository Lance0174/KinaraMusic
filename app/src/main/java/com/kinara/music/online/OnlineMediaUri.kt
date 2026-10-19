/* Kinara — Android music client. Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.online

import com.kinara.music.data.Track
import com.kinara.music.data.decodeTrackLine
import com.kinara.music.data.encodeTrackLine
import java.net.URLDecoder
import java.net.URLEncoder

/** 稳定媒体 URI：服务在真正加载音频时解析，旧加载任务不能再覆盖用户新选的队列。 */
object OnlineMediaUri {
    private const val PREFIX = "kinara://online/"
    fun encode(track: Track): String = PREFIX + URLEncoder.encode(encodeTrackLine(track), "UTF-8")
    fun decode(uri: String): Track? = if (uri.startsWith(PREFIX)) {
        runCatching { decodeTrackLine(URLDecoder.decode(uri.removePrefix(PREFIX), "UTF-8")) }
            .getOrNull()?.takeIf { it.onlineKey != null }
    } else null
}
