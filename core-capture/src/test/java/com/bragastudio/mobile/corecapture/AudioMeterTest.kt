package com.bragastudio.mobile.corecapture

import com.bragastudio.mobile.corecapture.domain.AudioMeter
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMeterTest {

    private fun sine(amplitude: Double, frames: Int, channels: Int = 2): ShortArray {
        val out = ShortArray(frames * channels)
        for (f in 0 until frames) {
            val v = (sin(2 * PI * f / 48.0) * amplitude * 32767).toInt().toShort()
            for (c in 0 until channels) out[f * channels + c] = v
        }
        return out
    }

    @Test
    fun picoDeSenoideCheiaEhMaiorQueRms() {
        val m = AudioMeter()
        val r = m.process(sine(0.95, 4800), 9600, 2, 0L)
        assertEquals(0.95f, r.peakLeft, 0.02f)
        assertEquals(0.95f / 1.4142f, r.rmsLeft, 0.02f)
        assertTrue(r.peakDbLeft > -1f)
    }

    @Test
    fun silencioFicaNoPiso() {
        val r = AudioMeter().process(ShortArray(960), 960, 2, 0L)
        assertEquals(0f, r.peakLeft, 0f)
        assertEquals(AudioMeter.FLOOR_DB, r.peakDbLeft, 0f)
        assertFalse(r.clipLeft)
    }

    @Test
    fun clipExigeTresAmostrasConsecutivas() {
        val m = AudioMeter()
        val two = ShortArray(20) // estéreo, 10 quadros
        two[0] = Short.MAX_VALUE
        two[2] = Short.MAX_VALUE // L: 2 consecutivas
        assertFalse(m.process(two, 20, 2, 0L).clipLeft)

        val three = ShortArray(20)
        three[0] = Short.MAX_VALUE
        three[2] = Short.MAX_VALUE
        three[4] = Short.MIN_VALUE
        val r = m.process(three, 20, 2, 10L)
        assertTrue(r.clipLeft)
        assertFalse(r.clipRight)
    }

    @Test
    fun monoEspelhaNosDoisCanais() {
        val r = AudioMeter().process(sine(0.5, 480, 1), 480, 1, 0L)
        assertEquals(r.peakLeft, r.peakRight, 0f)
        assertEquals(r.rmsLeft, r.rmsRight, 0f)
    }

    @Test
    fun peakHoldSeguraPor1sECai() {
        val m = AudioMeter(holdMs = 1000L)
        m.process(sine(0.9, 480), 960, 2, 0L)
        val held = m.process(sine(0.1, 480), 960, 2, 500L)
        assertEquals(0.9f, held.holdLeft, 0.02f)
        val released = m.process(sine(0.1, 480), 960, 2, 1600L)
        assertEquals(0.1f, released.holdLeft, 0.02f)
    }

    @Test
    fun blocoVazioNaoQuebra() {
        val r = AudioMeter().process(ShortArray(0), 0, 2, 0L)
        assertEquals(0f, r.peakLeft, 0f)
    }
}
