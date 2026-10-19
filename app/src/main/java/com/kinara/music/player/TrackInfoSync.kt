/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.player

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.kinara.music.data.*
import com.kinara.music.online.OnlineMediaUri
import kotlinx.coroutines.*
import java.io.File

/** Service-owned: also runs when the next track starts with the screen off. */
class TrackInfoSync(private val context: Context, private val player: Player, private val scope: CoroutineScope) {
    private val repository = TrackInfoRepository(File(context.filesDir, "track-info-v1"))
    private val preferences = context.getSharedPreferences("kinara_prefs", Context.MODE_PRIVATE)
    private val network = context.getSystemService(ConnectivityManager::class.java)
    private var job: Job? = null
    private var generation = 0L
    private var selected: Pair<String, Uri?>? = null
    private var observedItem: MediaItem? = null
    private var online = NetworkState.isOnline(context)
    private var syncSettings = KinaraPrefs.get(context).settings()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { refresh() }
    }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "auto_track_info" || key.startsWith("src_")) scope.launch {
            val next = KinaraPrefs.get(context).settings()
            val changed = next.autoTrackInfo != syncSettings.autoTrackInfo || next.sources != syncSettings.sources
            syncSettings = next
            if (changed) refresh(force = true)
        }
    }
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateNetwork()
        override fun onLost(network: Network) = updateNetwork()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = updateNetwork()
    }
    init {
        player.addListener(listener)
        preferences.registerOnSharedPreferenceChangeListener(prefsListener)
        runCatching { network.registerDefaultNetworkCallback(callback) }
        refresh()
    }
    private fun updateNetwork() { scope.launch {
        val next = NetworkState.isOnline(context)
        if (next != online) { online = next; refresh(force = true) }
    } }
    private fun refresh(force: Boolean = false) {
        val item = player.currentMediaItem
        val identity = item?.let { it.mediaId to it.localConfiguration?.uri }
        // Reselecting the same recording can create a new queue item with its original artwork.
        if (!force && identity == selected && item == observedItem) return
        selected = identity; observedItem = item; job?.cancel(); val request = ++generation
        val original = item?.mediaMetadata?.extras?.getString("kinara.track")?.let(::decodeTrackLine) ?: return
        // Keep the selected identity in the queue, but fetch artwork for the recording actually playing.
        val actual = item.localConfiguration?.uri?.toString()?.let(OnlineMediaUri::decode) ?: original
        val settings = KinaraPrefs.get(context).settings()
        job = scope.launch {
            repository.sync(actual, settings, online) { info ->
                val current = player.currentMediaItem
                if (request == generation && current?.mediaId == identity?.first && current?.localConfiguration?.uri == identity?.second && current != null) {
                    val shown = current.mediaMetadata.extras?.getString("kinara.track")?.let(::decodeTrackLine) ?: original
                    val updated = withDisplayMetadata(current, applyDisplayInfo(shown, info))
                    if (updated != current) {
                        observedItem = updated // Our own metadata event must not cancel/restart this request.
                        player.replaceMediaItem(player.currentMediaItemIndex, updated)
                    }
                }
            }
        }
    }
    fun close() {
        generation++; job?.cancel(); player.removeListener(listener)
        preferences.unregisterOnSharedPreferenceChangeListener(prefsListener)
        runCatching { network.unregisterNetworkCallback(callback) }
    }
}

/** Reuse the exact LocalConfiguration: changing a kinara:// URI would restart audio/cache resolution. */
internal fun withDisplayMetadata(item: MediaItem, track: Track): MediaItem = item.buildUpon()
    .setMediaMetadata(item.mediaMetadata.buildUpon().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album)
        .setArtworkUri(safeCoverUrl(track.coverUrl)?.let(Uri::parse))
        .setExtras(Bundle(item.mediaMetadata.extras ?: Bundle.EMPTY).apply { putString("kinara.track", encodeTrackLine(track)) }).build())
    .build()
