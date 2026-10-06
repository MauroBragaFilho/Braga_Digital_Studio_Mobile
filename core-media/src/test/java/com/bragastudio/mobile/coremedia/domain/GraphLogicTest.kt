package com.bragastudio.mobile.coremedia.domain

import com.bragastudio.mobile.corecapture.domain.CaptureState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphLogicTest {

    @Test
    fun estadosSaudaveis() {
        assertTrue(SourceHealth.isHealthy(CaptureState.READY))
        assertTrue(SourceHealth.isHealthy(CaptureState.RECORDING))
        assertTrue(SourceHealth.isHealthy(CaptureState.INITIALIZING))
        assertFalse(SourceHealth.isHealthy(CaptureState.ERROR))
        assertFalse(SourceHealth.isHealthy(CaptureState.IDLE))
    }

    @Test
    fun perdaDaFonteSoComGravacaoEEstadoRuim() {
        assertTrue(SourceHealth.shouldFinalizeAfterGrace(true, CaptureState.ERROR))
        assertTrue(SourceHealth.shouldFinalizeAfterGrace(true, CaptureState.IDLE))
        assertFalse(SourceHealth.shouldFinalizeAfterGrace(true, CaptureState.READY))
        assertFalse(SourceHealth.shouldFinalizeAfterGrace(true, CaptureState.INITIALIZING))
        assertFalse(SourceHealth.shouldFinalizeAfterGrace(false, CaptureState.ERROR))
    }

    @Test
    fun fpsEfetivoComFallback() {
        assertEquals(24, FpsResolver.effective(24, 60))
        assertEquals(60, FpsResolver.effective(0, 60))
        assertEquals(1, FpsResolver.effective(0, 0))
    }

    @Test
    fun taxaNdi() {
        assertEquals(30000 to 1000, FpsResolver.ndiFrameRate(30))
        assertEquals(60000 to 1000, FpsResolver.ndiFrameRate(60))
        assertEquals(30000 to 1000, FpsResolver.ndiFrameRate(0))
    }
}
