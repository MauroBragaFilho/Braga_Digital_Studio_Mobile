package com.bragastudio.mobile.featuresettings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BspScreenLogicTest {

    @Test
    fun offWhenNotEnabledRegardlessOfTheRest() {
        assertEquals(BspScreenPhase.OFF, BspScreenLogic.phase(enabled = false, active = false, failed = false, receiversStreaming = 0, captureFlowing = false))
        assertEquals(BspScreenPhase.OFF, BspScreenLogic.phase(enabled = false, active = true, failed = true, receiversStreaming = 2, captureFlowing = true))
    }

    @Test
    fun startingUntilTheSourceIsUpThenErrorIfItFails() {
        assertEquals(BspScreenPhase.STARTING, BspScreenLogic.phase(true, active = false, failed = false, receiversStreaming = 0, captureFlowing = false))
        assertEquals(BspScreenPhase.ERROR, BspScreenLogic.phase(true, active = false, failed = true, receiversStreaming = 0, captureFlowing = false))
    }

    @Test
    fun activeSourceWaitsForAReceiverThenForTheMonitorThenStreams() {
        assertEquals(BspScreenPhase.WAITING_RECEIVER, BspScreenLogic.phase(true, true, false, receiversStreaming = 0, captureFlowing = true))
        assertEquals(BspScreenPhase.WAITING_RECEIVER, BspScreenLogic.phase(true, true, false, receiversStreaming = 0, captureFlowing = false))
        assertEquals(BspScreenPhase.WAITING_MONITOR, BspScreenLogic.phase(true, true, false, receiversStreaming = 1, captureFlowing = false))
        assertEquals(BspScreenPhase.STREAMING, BspScreenLogic.phase(true, true, false, receiversStreaming = 1, captureFlowing = true))
        assertEquals(BspScreenPhase.STREAMING, BspScreenLogic.phase(true, true, false, receiversStreaming = 2, captureFlowing = true))
    }

    @Test
    fun activeBeatsAStaleFailure() {
        assertEquals(BspScreenPhase.WAITING_RECEIVER, BspScreenLogic.phase(true, active = true, failed = true, receiversStreaming = 0, captureFlowing = false))
    }

    @Test
    fun metricFormatting() {
        assertEquals("--", BspScreenLogic.formatBitrate(0f))
        assertEquals("9.8 Mbps", BspScreenLogic.formatBitrate(9.83f))
        assertEquals("--", BspScreenLogic.formatRtt(0))
        assertEquals("12 ms", BspScreenLogic.formatRtt(12))
        assertEquals("--", BspScreenLogic.formatFps(0))
        assertEquals("30 fps", BspScreenLogic.formatFps(30))
        assertEquals("1.5%", BspScreenLogic.formatLoss(1.52f))
        assertEquals("0.0%", BspScreenLogic.formatLoss(0f))
    }

    @Test
    fun readableErrorTrimsAndCapsLongMessages() {
        assertNull(BspScreenLogic.readableError(null))
        assertNull(BspScreenLogic.readableError("   "))
        assertEquals("Falha", BspScreenLogic.readableError("  Falha "))
        val long = BspScreenLogic.readableError("x".repeat(500))!!
        assertTrue(long.length <= 140)
        assertTrue(long.endsWith("…"))
    }
}
