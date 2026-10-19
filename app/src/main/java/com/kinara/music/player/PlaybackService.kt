/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.player

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
    private val handler = Handler(Looper.getMainLooper())
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
        val retry = PlaybackRetryGate()
        val fallback = PlaybackFallback(scope, resolver::next)
        var selection = 0L
        var replacementUri: String? = null
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
                retry.select(mediaItem?.mediaId)
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) timer?.trackEnded()
            }
            override fun onPlaybackStateChanged(state: Int) {
                PlaybackDiagnostics.record(PlaybackDiagnostics.Event.STATE, state)
                if (state == Player.STATE_IDLE && player.playerError == null) fallback.reset()
                if (state == Player.STATE_ENDED) timer?.trackEnded()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) = PlaybackDiagnostics.record(PlaybackDiagnostics.Event.PLAYING, if (isPlaying) 1 else 0)
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                PlaybackDiagnostics.record(PlaybackDiagnostics.Event.PLAY_WHEN_READY, reason * 10 + if (playWhenReady) 1 else 0)
            }
            override fun onPlaybackSuppressionReasonChanged(reason: Int) = PlaybackDiagnostics.record(PlaybackDiagnostics.Event.SUPPRESSION, reason)
            override fun onVolumeChanged(volume: Float) = PlaybackDiagnostics.record(PlaybackDiagnostics.Event.PLAYER_VOLUME, volume)
            override fun onAudioSessionIdChanged(audioSessionId: Int) = PlaybackDiagnostics.record(PlaybackDiagnostics.Event.AUDIO_SESSION, audioSessionId)
            override fun onPlayerError(error: PlaybackException) {
                PlaybackDiagnostics.record(PlaybackDiagnostics.Event.ERROR, error.errorCode)
                val item = player.currentMediaItem ?: return
                val failed = item.localConfiguration?.uri?.toString()?.let(OnlineMediaUri::decode) ?: return
                if (!player.playWhenReady || error.errorCode !in 2000..4999) return
                val causes = generateSequence<Throwable>(error) { it.cause }.toList()
                val unavailable = causes.any { it is AudioResolutionException ||
                    (it is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && it.responseCode in 400..499) }
                if (!unavailable && error.errorCode in 2000..2999 && retry.take(item.mediaId)) {
                    resolver.invalidate()
                    val generation = selection
                    val position = player.currentPosition.coerceAtLeast(0)
                    handler.post {
                        if (generation == selection && player.currentMediaItem?.mediaId == item.mediaId && player.playWhenReady) {
                            PlaybackDiagnostics.record(PlaybackDiagnostics.Event.RETRY, 1)
                            player.seekTo(position); player.prepare()
                        }
                    }
                    return
                }
                val original = item.mediaMetadata.extras?.getString("kinara.track")?.let(::decodeTrackLine) ?: failed
                val generation = selection
                val index = player.currentMediaItemIndex
                fallback.start(original, failed, {
                    generation == selection && player.currentMediaItemIndex == index &&
                        player.currentMediaItem?.mediaId == item.mediaId &&
                        player.currentMediaItem?.localConfiguration == item.localConfiguration && player.playWhenReady
                }) { actual ->
                    val position = player.currentPosition.coerceAtLeast(0)
                    val replacement = fallbackMediaItem(player.currentMediaItem ?: item, actual)
                    PlaybackDiagnostics.record(PlaybackDiagnostics.Event.FALLBACK, com.kinara.music.online.sourceRank(actual.source))
                    replacementUri = replacement.localConfiguration?.uri.toString()
                    retry.select(item.mediaId)
                    player.replaceMediaItem(index, replacement)
                    // Use a time position in the new extractor, never the previous file's byte offset.
                    player.seekTo(index, position)
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
                    RETRY_COMMAND -> { fallback.reset(); resolver.invalidate(); retry.select(player.currentMediaItem?.mediaId); player.prepare(); player.play() }
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
        handler.removeCallbacksAndMessages(null)
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
