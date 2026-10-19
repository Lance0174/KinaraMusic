package com.kinara.music.player

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class ReactiveAudioTest {
    @Test fun audioIsReadOnlyAndFrequencyResponseIsReal() {
        fun tone(hz: Int): ByteBuffer = ByteBuffer.allocate(4800 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(4800) { putShort((sin(2 * PI * hz * it / 48000) * 16000).toInt().toShort()) }; flip()
        }
        val low = tone(80); val saved = low.array().copyOf()
        val bass = PcmBandMeter().measure(low, C.ENCODING_PCM_16BIT, 1, 48000)
        val high = PcmBandMeter().measure(tone(7000), C.ENCODING_PCM_16BIT, 1, 48000)
        assertTrue(bass.bass > bass.treble * 3); assertTrue(high.treble > high.bass * 3)
        assertEquals(0, low.position()); assertArrayEquals(saved, low.array())
    }
    @Test fun decodedAudioIsNotShownBeforeItsPresentationTime() {
        ReactiveAudio.clear()
        ReactiveAudio.add(1000, AudioBands(.5f,.4f,.1f))
        assertEquals(AudioBands(), ReactiveAudio.at(500))
        assertEquals(.5f, ReactiveAudio.at(1100).power, 0f)
        assertEquals(AudioBands(), ReactiveAudio.at(2000))
        ReactiveAudio.add(0, AudioBands(.1f))
        assertEquals(.1f, ReactiveAudio.at(0).power, 0f)
        ReactiveAudio.clear()
    }
}
