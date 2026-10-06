package com.bragastudio.mobile.featurepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VuMeterModelTest {

    @Test
    fun levelFollowsInput() {
        val vu = VuMeterModel()
        vu.update(0.5f, 0.5f, 0L)
        assertEquals(0.5f, vu.level, 0f)
        assertFalse(vu.isClipping)
    }

    @Test
    fun clipNeedsThreeConsecutiveSamples() {
        val vu = VuMeterModel()
        vu.update(0.9f, 1.0f, 0L)
        assertFalse(vu.isClipping)
        vu.update(0.9f, 1.0f, 40L)
        assertFalse(vu.isClipping)
        vu.update(0.9f, 1.0f, 80L)
        assertTrue(vu.isClipping)
    }

    @Test
    fun clipRunIsBrokenByLowSample() {
        val vu = VuMeterModel()
        vu.update(0.9f, 1.0f, 0L)
        vu.update(0.9f, 1.0f, 40L)
        vu.update(0.5f, 0.5f, 80L) // quebra a sequência
        vu.update(0.9f, 1.0f, 120L)
        vu.update(0.9f, 1.0f, 160L)
        assertFalse(vu.isClipping)
    }

    @Test
    fun peakBelowThresholdNeverClips() {
        val vu = VuMeterModel()
        for (i in 0 until 20) vu.update(0.95f, 0.998f, i * 40L)
        assertFalse(vu.isClipping)
    }

    @Test
    fun clipStaysLitForHoldTimeThenReleases() {
        val vu = VuMeterModel(clipHoldMs = 2000L)
        for (i in 0 until 3) vu.update(1f, 1f, i * 40L) // clip em t=80
        assertTrue(vu.isClipping)
        vu.update(0.1f, 0.1f, 1000L)
        assertTrue(vu.isClipping)
        vu.update(0.1f, 0.1f, 2100L) // 80 + 2000 = 2080 < 2100
        assertFalse(vu.isClipping)
    }

    @Test
    fun peakHoldKeepsMaximumForOneSecond() {
        val vu = VuMeterModel(peakHoldMs = 1000L)
        vu.update(0.9f, 0.9f, 0L)
        vu.update(0.3f, 0.3f, 500L)
        assertEquals(0.9f, vu.peakHold, 0f)
        vu.update(0.3f, 0.3f, 999L)
        assertEquals(0.9f, vu.peakHold, 0f)
        vu.update(0.3f, 0.3f, 1000L) // expirou: cai para o pico atual
        assertEquals(0.3f, vu.peakHold, 0f)
    }

    @Test
    fun higherPeakReplacesHeldPeakAndRestartsTimer() {
        val vu = VuMeterModel(peakHoldMs = 1000L)
        vu.update(0.5f, 0.5f, 0L)
        vu.update(0.7f, 0.7f, 800L)
        vu.update(0.2f, 0.2f, 1500L) // só 700 ms desde o novo pico
        assertEquals(0.7f, vu.peakHold, 0f)
    }

    @Test
    fun peakNeverBelowLevel() {
        val vu = VuMeterModel()
        vu.update(0.8f, 0.2f, 0L) // pico menor que o nível (entrada inconsistente)
        assertEquals(0.8f, vu.peakHold, 0f)
    }

    @Test
    fun inputIsSanitized() {
        val vu = VuMeterModel()
        vu.update(Float.NaN, 2f, 0L)
        assertEquals(0f, vu.level, 0f)
        assertEquals(1f, vu.peakHold, 0f)
    }
}
