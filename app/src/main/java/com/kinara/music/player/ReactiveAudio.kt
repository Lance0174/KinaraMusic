/* Kinara — Copyright (C) 2026 Kinara contributors. GPL-3.0. */
package com.kinara.music.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sqrt

data class AudioBands(val power: Float = 0f, val bass: Float = 0f, val treble: Float = 0f)

/** Small, in-process PCM meter. No microphone permission and no changes to the decoded audio. */
object ReactiveAudio {
    val consumers = AtomicInteger(0)
    private val frames = ArrayDeque<Pair<Long, AudioBands>>()
    @Synchronized fun clear() = frames.clear()
    @Synchronized fun add(timeMs: Long, bands: AudioBands) {
        if (frames.peekLast()?.first?.let { it > timeMs } == true) frames.clear()
        frames.addLast(timeMs to bands)
        while (frames.size > 256) frames.removeFirst()
    }
    @Synchronized fun at(timeMs: Long): AudioBands = frames.lastOrNull { it.first <= timeMs && timeMs - it.first <= 500 }?.second ?: AudioBands()
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class PcmBandMeter {
    private var low = 0.0
    private var highLowPass = 0.0
    fun reset() { low = 0.0; highLowPass = 0.0 }
    fun measure(input: ByteBuffer, encoding: Int, channels: Int, rate: Int): AudioBands {
        val size = when (encoding) { C.ENCODING_PCM_16BIT -> 2; C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4; else -> return AudioBands() }
        if (channels !in 1..8 || rate <= 0) return AudioBands()
        val bytes = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val lowK = 1 - exp(-2 * PI * 180 / rate)
        val highK = 1 - exp(-2 * PI * 2500 / rate)
        var power = 0.0; var bass = 0.0; var treble = 0.0; var count = 0
        while (bytes.remaining() >= size * channels) {
            var sample = 0.0
            repeat(channels) {
                sample += when (encoding) {
                    C.ENCODING_PCM_FLOAT -> bytes.float.toDouble().takeIf { it.isFinite() }?.coerceIn(-1.0, 1.0) ?: 0.0
                    C.ENCODING_PCM_32BIT -> bytes.int / 2147483648.0
                    else -> bytes.short / 32768.0
                }
            }
            sample /= channels
            low += lowK * (sample - low)
            highLowPass += highK * (sample - highLowPass)
            power += sample * sample; bass += low * low; treble += (sample - highLowPass) * (sample - highLowPass); count++
        }
        fun level(energy: Double): Float = if (count == 0) 0f else (sqrt(energy / count) * 2.5).toFloat().coerceIn(0f, 1f)
        return AudioBands(level(power), level(bass), level(treble))
    }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class ReactiveAudioSink(sink: AudioSink) : ForwardingAudioSink(sink) {
    private val meter = PcmBandMeter()
    private var format = Format.Builder().build()
    private var previousBuffer: ByteBuffer? = null
    private var previousTime = Long.MIN_VALUE
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        format = inputFormat; clearMeter()
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }
    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (ReactiveAudio.consumers.get() > 0 && (buffer !== previousBuffer || presentationTimeUs != previousTime)) {
            previousBuffer = buffer; previousTime = presentationTimeUs
            ReactiveAudio.add(presentationTimeUs / 1000, meter.measure(buffer, format.pcmEncoding, format.channelCount, format.sampleRate))
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }
    private fun clearMeter() { previousBuffer = null; previousTime = Long.MIN_VALUE; meter.reset(); ReactiveAudio.clear() }
    override fun flush() { clearMeter(); super.flush() }
    override fun reset() { clearMeter(); super.reset() }
    override fun setVolume(volume: Float) {
        super.setVolume(volume)
        PlaybackDiagnostics.record(PlaybackDiagnostics.Event.OUTPUT_GAIN, volume)
    }
}
