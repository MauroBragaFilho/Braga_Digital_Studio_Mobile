package com.bragastudio.mobile.featurepreview

import com.bragastudio.mobile.corecapture.domain.CaptureMetadata
import com.bragastudio.mobile.corecapture.domain.ManualLimits
import com.bragastudio.mobile.coremedia.domain.RecordingEvent
import com.bragastudio.mobile.coremedia.domain.StopReason
import com.bragastudio.mobile.network.TallyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudLogicTest {

    // ---- Eventos de gravação -> mensagens ------------------------------------

    @Test
    fun eventos_viramMensagens() {
        assertEquals("Fonte perdida — gravação finalizada", recordingEventMessage(RecordingEvent.SourceLost("usb")))
        assertEquals("Disco cheio — gravação finalizada", recordingEventMessage(RecordingEvent.DiskFull(1024L)))
        assertEquals(
            "Erro no codificador — gravação finalizada (muxer)",
            recordingEventMessage(RecordingEvent.EncoderError("muxer")),
        )
        assertEquals(
            "Erro no codificador — gravação finalizada",
            recordingEventMessage(RecordingEvent.EncoderError("  ")),
        )
    }

    @Test
    fun stopped_ehSilencioso() {
        assertNull(recordingEventMessage(RecordingEvent.Stopped(StopReason.USER, null)))
        assertNull(recordingEventMessage(RecordingEvent.Stopped(StopReason.DISK_FULL, "/x.mp4")))
    }

    @Test
    fun finalizando_soComPendencias() {
        assertNull(finalizingMessage(0))
        assertNull(finalizingMessage(-1))
        assertEquals("Finalizando gravação…", finalizingMessage(1))
        assertEquals("Finalizando gravação…", finalizingMessage(3))
    }

    // ---- Tally -> cor ---------------------------------------------------------

    @Test
    fun tally_programVermelhoPreviewVerde() {
        assertEquals(TallyIndicator.RED, tallyIndicator(TallyState.PROGRAM, false))
        assertEquals(TallyIndicator.GREEN, tallyIndicator(TallyState.PREVIEW, false))
        assertEquals(TallyIndicator.NONE, tallyIndicator(TallyState.OFF, false))
    }

    @Test
    fun tally_recLocalSempreVermelho() {
        assertEquals(TallyIndicator.RED, tallyIndicator(TallyState.OFF, true))
        assertEquals(TallyIndicator.RED, tallyIndicator(TallyState.PREVIEW, true))
        assertEquals(TallyIndicator.RED, tallyIndicator(TallyState.PROGRAM, true))
    }

    // ---- Estado manual inicial (L2) -------------------------------------------

    @Test
    fun manualInicial_aeAutoEhAuto() {
        val m = manualExposureFrom(CaptureMetadata(iso = 400, exposureTimeNs = 8_000_000L, aeAuto = true))
        assertNull(m.iso)
        assertNull(m.shutterNs)
    }

    @Test
    fun manualInicial_aeManualUsaValoresReais() {
        val m = manualExposureFrom(CaptureMetadata(iso = 800, exposureTimeNs = 16_666_666L, aeAuto = false))
        assertEquals(800, m.iso)
        assertEquals(16_666_666L, m.shutterNs)
    }

    // ---- Coerção de faixas -----------------------------------------------------

    private val limits = ManualLimits(
        isoMin = 100, isoMax = 3200,
        exposureMinNs = 1_000_000L, exposureMaxNs = 100_000_000L,
        minFocusDiopters = 10f,
    )

    @Test
    fun iso_coercao() {
        assertNull(coerceIsoToLimits(null, limits))
        assertEquals(100, coerceIsoToLimits(50, limits))
        assertEquals(3200, coerceIsoToLimits(6400, limits))
        assertEquals(800, coerceIsoToLimits(800, limits))
        // sem faixa conhecida: não altera
        assertEquals(6400, coerceIsoToLimits(6400, ManualLimits()))
    }

    @Test
    fun obturador_coercao() {
        assertEquals(1_000_000L, coerceShutterToLimits(500_000L, limits))
        assertEquals(100_000_000L, coerceShutterToLimits(1_000_000_000L, limits))
        assertEquals(8_000_000L, coerceShutterToLimits(8_000_000L, limits))
        assertNull(coerceShutterToLimits(null, limits))
    }

    @Test
    fun foco_coercao() {
        assertEquals(10f, coerceFocusToLimits(20f, limits))
        assertEquals(0f, coerceFocusToLimits(-1f, limits))
        assertNull(coerceFocusToLimits(null, limits))
        // foco fixo: só infinito
        assertEquals(0f, coerceFocusToLimits(5f, limits.copy(minFocusDiopters = 0f)))
        assertTrue(supportsManualFocus(limits))
        assertFalse(supportsManualFocus(limits.copy(minFocusDiopters = 0f)))
        assertTrue(supportsManualFocus(ManualLimits())) // desconhecido: não bloqueia
    }

    @Test
    fun opcoes_filtradasPelaFaixa() {
        val iso = listOf("AUTO", "50", "100", "400", "6400")
        assertEquals(listOf("AUTO", "100", "400"), filterIsoOptions(iso, limits))
        assertEquals(iso, filterIsoOptions(iso, ManualLimits()))

        val shutter = listOf("AUTO", "1/2000", "1/1000", "1/60", "1\"")
        // 1/2000 = 0,5 ms < 1 ms ; 1" = 1000 ms > 100 ms
        assertEquals(listOf("AUTO", "1/1000", "1/60"), filterShutterOptions(shutter, limits))

        val focus = listOf("AUTO", "∞", "1m", "0.1m")
        // 0.1m = 10 dioptrias (no limite); 0.05 m não existe na lista
        assertEquals(focus, filterFocusOptions(focus, limits))
        assertEquals(listOf("AUTO", "∞", "1m"), filterFocusOptions(focus, limits.copy(minFocusDiopters = 2f)))
        assertEquals(listOf("AUTO", "∞"), filterFocusOptions(focus, limits.copy(minFocusDiopters = 0f)))
    }
}
