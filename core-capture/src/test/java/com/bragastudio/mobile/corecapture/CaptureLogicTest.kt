package com.bragastudio.mobile.corecapture

import com.bragastudio.mobile.corecapture.domain.FpsPlanner
import com.bragastudio.mobile.corecapture.domain.FpsRange
import com.bragastudio.mobile.corecapture.domain.LensClassifier
import com.bragastudio.mobile.corecapture.domain.LensType
import com.bragastudio.mobile.corecapture.domain.ManualControls
import com.bragastudio.mobile.corecapture.domain.UvcFormatPicker
import com.bragastudio.mobile.corecapture.domain.UvcMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureLogicTest {

    private val typical = listOf(
        FpsRange(15, 15), FpsRange(15, 30), FpsRange(30, 30), FpsRange(24, 24), FpsRange(30, 60), FpsRange(60, 60),
    )

    // ---- FpsPlanner ----

    @Test
    fun fps_prefereFaixaFixa() {
        val plan = FpsPlanner.plan(typical, 30)
        assertEquals(FpsRange(30, 30), plan.aeRange)
        assertEquals(30, plan.effectiveFps)
    }

    @Test
    fun fps_60UsaFaixaFixa60() {
        val plan = FpsPlanner.plan(typical, 60)
        assertEquals(FpsRange(60, 60), plan.aeRange)
        assertEquals(60, plan.effectiveFps)
    }

    @Test
    fun fps_semFaixaFixaEscolheContendoComMenorFolga() {
        val ranges = listOf(FpsRange(15, 30), FpsRange(7, 60), FpsRange(30, 60))
        val plan = FpsPlanner.plan(ranges, 30)
        // [15,30] tem upper == alvo (folga 0)
        assertEquals(FpsRange(15, 30), plan.aeRange)
        assertEquals(30, plan.effectiveFps)
    }

    @Test
    fun fps_acimaDoMaximoAnunciadoEhLimitado() {
        val plan = FpsPlanner.plan(listOf(FpsRange(15, 30), FpsRange(30, 30)), 60)
        assertEquals(30, plan.effectiveFps)
        assertEquals(FpsRange(30, 30), plan.aeRange)
    }

    @Test
    fun fps_limitadoPelaDuracaoMinimaDeQuadro() {
        // 4K a ~30 fps: min frame duration 33,3 ms impede 60 fps mesmo com a faixa anunciada.
        val plan = FpsPlanner.plan(typical, 60, minFrameDurationNs = 33_333_333L)
        assertEquals(30, plan.effectiveFps)
        assertEquals(FpsRange(30, 30), plan.aeRange)
    }

    @Test
    fun fps_toleranciaPara29_97() {
        assertEquals(30, FpsPlanner.maxFpsFromMinFrameDuration(33_366_700L))
        assertEquals(60, FpsPlanner.maxFpsFromMinFrameDuration(16_666_666L))
    }

    @Test
    fun fps_todasFaixasComPisoAcimaDoAlvo() {
        val plan = FpsPlanner.plan(listOf(FpsRange(30, 60), FpsRange(60, 60)), 24)
        assertEquals(FpsRange(30, 60), plan.aeRange)
        assertEquals(30, plan.effectiveFps)
    }

    @Test
    fun fps_semFaixasUsaAlvo() {
        val plan = FpsPlanner.plan(emptyList(), 24)
        assertNull(plan.aeRange)
        assertEquals(24, plan.effectiveFps)
    }

    @Test
    fun fps_duracaoDeQuadro() {
        assertEquals(33_333_333L, FpsPlanner.frameDurationNs(30))
        assertEquals(30, FpsPlanner.fpsFromFrameDuration(33_333_333L))
        assertEquals(0, FpsPlanner.fpsFromFrameDuration(0L))
    }

    // ---- ManualControls ----

    @Test
    fun manual_coercaoDeFaixa() {
        assertEquals(800, ManualControls.coerceIso(3200, 100, 800))
        assertEquals(100, ManualControls.coerceIso(10, 100, 800))
        assertEquals(3200, ManualControls.coerceIso(3200, null, null))
        assertEquals(1_000_000L, ManualControls.coerceExposureNs(1L, 1_000_000L, 1_000_000_000L))
    }

    @Test
    fun manual_focoFixoRetornaNull() {
        assertNull(ManualControls.coerceFocus(5f, 0f))
        assertEquals(10f, ManualControls.coerceFocus(50f, 10f))
        assertEquals(0f, ManualControls.coerceFocus(-1f, 10f))
    }

    @Test
    fun manual_exposicaoAutomaticaRetornaNull() {
        assertNull(ManualControls.resolveExposure(null, null, 200, 1_000_000L))
    }

    @Test
    fun manual_soIsoCompletaObturadorDoUltimoResultado() {
        val e = ManualControls.resolveExposure(
            manualIso = 400, manualShutterNs = null, lastIso = 100, lastExposureNs = 8_000_000L,
            isoMin = 50, isoMax = 3200,
        )!!
        assertEquals(400, e.iso)
        assertEquals(8_000_000L, e.exposureNs)
    }

    @Test
    fun manual_soObturadorCompletaIsoEAplicaFaixa() {
        val e = ManualControls.resolveExposure(
            manualIso = null, manualShutterNs = 500_000_000_000L, lastIso = 6400, lastExposureNs = null,
            isoMin = 50, isoMax = 3200, exposureMinNs = 10_000L, exposureMaxNs = 1_000_000_000L,
        )!!
        assertEquals(3200, e.iso)
        assertEquals(1_000_000_000L, e.exposureNs)
    }

    @Test
    fun manual_semUltimoResultadoUsaReserva() {
        val e = ManualControls.resolveExposure(null, 4_000_000L, null, null)!!
        assertEquals(100, e.iso)
        assertEquals(4_000_000L, e.exposureNs)
    }

    // ---- LensClassifier ----

    @Test
    fun lente_focalEquivalente() {
        // Sensor 1/1.31" ~ 9,8 x 7,3 mm, f = 6,9 mm -> ~23-24 mm equivalentes.
        val eq = LensClassifier.equivalentFocal35mm(6.9f, 9.8f, 7.3f)
        assertTrue("eq=$eq", eq in 22f..26f)
        assertEquals(0f, LensClassifier.equivalentFocal35mm(0f, 5f, 4f), 0f)
        assertEquals(0f, LensClassifier.equivalentFocal35mm(4f, 0f, 4f), 0f)
    }

    @Test
    fun lente_classificacaoPorFaixas() {
        assertEquals(LensType.ULTRAWIDE, LensClassifier.classify(13f))
        assertEquals(LensType.MAIN, LensClassifier.classify(24f))
        assertEquals(LensType.TELEPHOTO, LensClassifier.classify(70f))
        assertEquals(LensType.SUPER_TELEPHOTO, LensClassifier.classify(230f))
        assertNull(LensClassifier.classify(0f))
    }

    @Test
    fun lente_macroDedicada() {
        assertEquals(LensType.MACRO, LensClassifier.classify(26f, minFocusDiopters = 25f, megapixels = 2f))
        // Ultrawide com AF próximo mas sensor grande NÃO é macro.
        assertEquals(LensType.ULTRAWIDE, LensClassifier.classify(14f, minFocusDiopters = 25f, megapixels = 12f))
    }

    // ---- UvcFormatPicker ----

    @Test
    fun uvc_preferMjpeg1080() {
        val modes = listOf(
            UvcMode(false, 1920, 1080, 5), UvcMode(true, 1920, 1080, 30), UvcMode(true, 1280, 720, 30),
        )
        val p = UvcFormatPicker.pick(modes)!!
        assertEquals(1920, p.width)
        assertTrue(p.isMjpeg)
    }

    @Test
    fun uvc_semMjpegNoAlvoUsaYuyvNoTamanhoDisponivel() {
        val p = UvcFormatPicker.pick(listOf(UvcMode(false, 1920, 1080, 5), UvcMode(false, 640, 480, 30)))!!
        assertEquals(1080, p.height)
    }

    @Test
    fun uvc_tudoAcimaDoAlvoPegaOMenor() {
        val p = UvcFormatPicker.pick(listOf(UvcMode(true, 3840, 2160, 30), UvcMode(true, 2560, 1440, 30)))!!
        assertEquals(2560, p.width)
    }

    @Test
    fun uvc_listaVazia() {
        assertNull(UvcFormatPicker.pick(emptyList()))
        assertNotNull(UvcFormatPicker.pick(listOf(UvcMode(true, 640, 480))))
    }
}
