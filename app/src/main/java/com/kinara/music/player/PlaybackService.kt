/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.player

import android.content.Context
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.kinara.music.online.OnlineMediaUri
import com.kinara.music.data.decodeTrackLine
import kotlinx.coroutines.*

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private var timer: SleepTimer? = null
    private var trackInfo: TrackInfoSync? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val resolver = AudioResolver(this)
        val upstream = DataSource.Factory { ResolvedAudioSource(this, resolver) }
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
                val sink = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
                return ReactiveAudioSink(sink)
            }
        }
        val player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(AudioCache.factory(this, upstream))
                .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(0)))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_NETWORK).build()
        timer = SleepTimer(player::pause)
        val fallback = PlaybackFallback(scope, resolver::next)
        var selection = 0L
        var replacementUri: String? = null
        val unavailableQueue = UnavailableQueue()
        var automaticAdvance: Pair<Int, String>? = null
        var internalSeek: Triple<Int, String, Long>? = null
        var stablePlayback: Job? = null
        fun seek(index: Int, position: Long) {
            // Media3 queues listener callbacks during an existing event dispatch. Keep the target
            // until its callback arrives; a boolean around seekTo() is already false at that point.
            internalSeek = Triple(index, player.getMediaItemAt(index).mediaId, position)
            player.seekTo(index, position)
        }
        fun skipUnavailable() {
            val timeline = player.currentTimeline
            val next = unavailableQueue.next(player.currentMediaItemIndex, player.mediaItemCount,
                { player.getMediaItemAt(it).mediaId }, { index ->
                    timeline.getNextWindowIndex(index,
                        if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else player.repeatMode,
                        player.shuffleModeEnabled)
                })
            if (next == null) {
                PlaybackDiagnostics.record(PlaybackDiagnostics.Event.EXHAUSTED, player.mediaItemCount)
                player.pause()
                return
            }
            PlaybackDiagnostics.record(PlaybackDiagnostics.Event.SKIP, next)
            automaticAdvance = next to player.getMediaItemAt(next).mediaId
            seek(next, 0)
            player.prepare()
        }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED && replacementUri != null &&
                    mediaItem?.localConfiguration?.uri.toString() == replacementUri) {
                    replacementUri = null
                    return
                }
                replacementUri = null
                selection++
                fallback.reset()
                stablePlayback?.cancel()
                val advanced = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK &&
                    automaticAdvance == (player.currentMediaItemIndex to mediaItem?.mediaId)
                automaticAdvance = null
                if (!advanced) unavailableQueue.reset()
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) timer?.trackEnded()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                val expected = internalSeek
                internalSeek = null
                val ours = expected != null && expected.first == newPosition.mediaItemIndex &&
                    expected.second == newPosition.mediaItem?.mediaId && expected.third == newPosition.positionMs
                if (!ours) {
                    selection++
                    fallback.reset()
                    unavailableQueue.reset()
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                PlaybackDiagnostics.record(PlaybackDiagnostics.Event.STATE, state)
                if (state == Player.STATE_IDLE && player.playbackState == Player.STATE_IDLE && player.playerError == null) fallback.reset()
                if (state == Player.STATE_ENDED) timer?.trackEnded()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                PlaybackDiagnostics.record(PlaybackDiagnostics.Event.PLAYING, if (isPlaying) 1 else 0)
                stablePlayback?.cancel()
                if (isPlaying) stablePlayback = scope.launch {
                    delay(5000)
                    if (player.isPlaying) unavailableQueue.reset()
                }
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                PlaybackDiagnostics.record(PlaybackDiagnostics.Event.PLAY_WHEN_READY, reason * 10 + if (playWhenReady) 1 else 0)
                if (!playWhenReady) fallback.reset()
            }
            override fun onPlaybackSuppressionReasonChanged(reason: Int) = PlaybackDiagnostics.record(PlaybackDiagnostics.Event.SUPPRESSION, reason)
            override fun onVolumeChanged(volume: Float) = PlaybackDiagnostics.record(PlaybackDiagnostics.Event.PLAYER_VOLUME, volume)
            override fun onAudioSessionIdChanged(audioSessionId: Int) = PlaybackDiagnostics.record(PlaybackDiagnostics.Event.AUDIO_SESSION, audioSessionId)
            override fun onPlayerError(error: PlaybackException) {
                PlaybackDiagnostics.record(PlaybackDiagnostics.Event.ERROR, error.errorCode)
                val item = player.currentMediaItem ?: return
                if (!player.playWhenReady || error.errorCode !in 2000..4999) return
                val failed = item.localConfiguration?.uri?.toString()?.let(OnlineMediaUri::decode)
                if (failed == null) { skipUnavailable(); return }
                val original = item.mediaMetadata.extras?.getString("kinara.track")?.let(::decodeTrackLine) ?: failed
                val generation = selection
                val index = player.currentMediaItemIndex
                fallback.start(original, failed, {
                    generation == selection && player.currentMediaItemIndex == index &&
                        player.currentMediaItem?.mediaId == item.mediaId &&
                        player.currentMediaItem?.localConfiguration == item.localConfiguration && player.playWhenReady
                }, exhausted = ::skipUnavailable) { actual ->
                    val position = player.currentPosition.coerceAtLeast(0)
                    val replacement = fallbackMediaItem(player.currentMediaItem ?: item, actual)
                    PlaybackDiagnostics.record(PlaybackDiagnostics.Event.FALLBACK, com.kinara.music.online.sourceRank(actual.source))
                    replacementUri = replacement.localConfiguration?.uri.toString()
                    player.replaceMediaItem(index, replacement)
                    // Use a time position in the new extractor, never the previous file's byte offset.
                    seek(index, position)
                    player.prepare()
                }
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                PlaybackDiagnostics.record(PlaybackDiagnostics.Event.UNDERRUN, elapsedSinceLastFeedMs)
            }
        })
        mediaSession = MediaSession.Builder(this, player).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (controller.uid != applicationInfo.uid) return MediaSession.ConnectionResult.accept(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS, externalPlayerCommands())
                return MediaSession.ConnectionResult.accept(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand(SleepTimer.COMMAND, Bundle.EMPTY)).add(SessionCommand(RETRY_COMMAND, Bundle.EMPTY)).build(),
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
            }
            override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                if (controller.uid != applicationInfo.uid) return Futures.immediateFuture(SessionResult(SessionError.ERROR_PERMISSION_DENIED))
                when (customCommand.customAction) {
                    SleepTimer.COMMAND -> timer?.set(args.getInt("minutes"), args.getBoolean("afterTrack"))
                    RETRY_COMMAND -> { selection++; fallback.reset(); unavailableQueue.reset(); resolver.invalidate(); player.prepare(); player.play() }
                    else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
        }).build()
        trackInfo = TrackInfoSync(this, player, scope)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession
    override fun onDestroy() {
        trackInfo?.close(); trackInfo = null
        scope.cancel()
        timer?.cancel(); timer = null
        mediaSession?.let { it.player.release(); it.release() }; mediaSession = null
        super.onDestroy()
    }
    companion object { const val RETRY_COMMAND = "kinara.retry" }
}

/** Lock-screen/Bluetooth controllers may control playback, but cannot inject a remote or private file URI. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun externalPlayerCommands(): Player.Commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
    .remove(Player.COMMAND_SET_MEDIA_ITEM).remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).build()
