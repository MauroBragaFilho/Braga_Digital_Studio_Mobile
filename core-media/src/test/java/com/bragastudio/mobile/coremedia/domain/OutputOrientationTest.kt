package com.bragastudio.mobile.coremedia.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputOrientationTest {
    // Graus do shader por Surface.ROTATION_* (mesmo mapa de rotationDegreesFor do Monitor):
    // ROTATION_0 -> 0, ROTATION_90 -> 270, ROTATION_180 -> 180, ROTATION_270 -> 90.
    private val displays = listOf(0f, 270f, 180f, 90f)

    private val portraitFrame = 1080 to 1920
    private val landscapeFrame = 1920 to 1080

    /** Buffer paisagem 1920x1080 de uma Camera2 com sensor [sensor] (90 = traseira, 270 = frontal). */
    private fun cameraAspect(sensor: Int) = OutputOrientation.sourceAspect(1920, 1080, sensor)

    private fun eq(expected: Any?, actual: Any?, message: String = "") = org.junit.Assert.assertEquals(message, expected, actual)

    private fun assertNear(expected: Float, actual: Float, tolerance: Float = 1e-3f) {
        assertTrue("esperado $expected, veio $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }

    // ---- aspecto da fonte ---------------------------------------------------------------------

    @Test
    fun camera2Sensor90e270TrocamOsEixosDoBuffer() {
        assertNear(9f / 16f, cameraAspect(90))
        assertNear(9f / 16f, cameraAspect(270))
    }

    @Test
    fun uvcESonySensor0MantemAspectoDoBuffer() {
        assertNear(16f / 9f, OutputOrientation.sourceAspect(1920, 1080, 0))
        assertNear(4f / 3f, OutputOrientation.sourceAspect(1600, 1200, 0))
    }

    @Test
    fun sensor180NaoTrocaEixos() {
        assertNear(16f / 9f, OutputOrientation.sourceAspect(1920, 1080, 180))
    }

    @Test
    fun bufferDesconhecidoCaiEm16por9() {
        assertNear(16f / 9f, OutputOrientation.sourceAspect(0, 0, 90))
    }

    // ---- ângulo -------------------------------------------------------------------------------

    @Test
    fun normalizaGraus() {
        eq(0f, OutputOrientation.normalizeDegrees(0f))
        eq(270f, OutputOrientation.normalizeDegrees(-90f))
        eq(0f, OutputOrientation.normalizeDegrees(360f))
        eq(90f, OutputOrientation.normalizeDegrees(450f))
        eq(180f, OutputOrientation.normalizeDegrees(179f))
    }

    @Test
    fun camera2AcompanhaODisplayNosQuatroGiros() {
        for (d in displays) {
            eq(d, OutputOrientation.outputRotation(d, followsDisplay = true))
        }
    }

    @Test
    fun fonteExternaNuncaGira() {
        for (d in displays) {
            eq(0f, OutputOrientation.outputRotation(d, followsDisplay = false))
        }
    }

    // ---- tamanho do quadro escolhido na partida -----------------------------------------------

    @Test
    fun camera2IniciadaEmRetratoGeraQuadroRetrato() {
        for (sensor in listOf(90, 270)) {
            for (d in listOf(0f, 180f)) {
                eq(1080 to 1920, OutputOrientation.startFrameSize(1920, 1080, cameraAspect(sensor), d, true), "sensor=$sensor d=$d")
            }
        }
    }

    @Test
    fun camera2IniciadaEmPaisagemGeraQuadroPaisagemNasDuasPaisagens() {
        for (sensor in listOf(90, 270)) {
            for (d in listOf(270f, 90f)) {
                eq(1920 to 1080, OutputOrientation.startFrameSize(1920, 1080, cameraAspect(sensor), d, true), "sensor=$sensor d=$d")
            }
        }
    }

    @Test
    fun fonteExternaSempreGeraQuadroPaisagemMesmoComAparelhoEmRetrato() {
        for (d in displays) {
            eq(1920 to 1080, OutputOrientation.startFrameSize(1920, 1080, 16f / 9f, d, false))
        }
    }

    @Test
    fun tamanhoBaseJaTrocadoNaoMudaOResultado() {
        eq(1080 to 1920, OutputOrientation.frameSize(1080, 1920, 9f / 16f))
        eq(1920 to 1080, OutputOrientation.frameSize(1080, 1920, 16f / 9f))
        eq(1280 to 720, OutputOrientation.frameSize(1280, 720, 1f))
    }

    // ---- encaixe com barras (matriz sensor x display x quadro x lente) -------------------------

    @Test
    fun matrizCompletaNuncaDistorceEAnguloSegueODisplay() {
        // sensor 90 = traseira, 270 = frontal. O espelhamento da frontal (uMirrorX) é um flip
        // horizontal APÓS a rotação e não entra no cálculo de ângulo/escala.
        for (sensor in listOf(90, 270)) {
            for (frame in listOf(portraitFrame, landscapeFrame)) {
                for (d in displays) {
                    val src = cameraAspect(sensor)
                    val l = OutputOrientation.layout(frame.first, frame.second, src, d, true)
                    val tag = "sensor=$sensor frame=$frame d=$d"
                    eq(d, l.rotationDegrees, tag)
                    assertTrue(tag, l.scaleX <= 1f && l.scaleY <= 1f)
                    assertTrue("uma escala é sempre 1: $tag", l.scaleX == 1f || l.scaleY == 1f)
                    // Aspecto REAL do quad em pixels == aspecto do conteúdo girado => sem esticar.
                    val quad = (frame.first * l.scaleX) / (frame.second * l.scaleY)
                    assertNear(OutputOrientation.rotatedAspect(src, d), quad)
                }
            }
        }
    }

    @Test
    fun quadroRetratoComAparelhoEmRetratoPreencheSemBarras() {
        for (sensor in listOf(90, 270)) {
            for (d in listOf(0f, 180f)) {
                val l = OutputOrientation.layout(1080, 1920, cameraAspect(sensor), d, true)
                assertFalse("sensor=$sensor d=$d", l.hasBars)
            }
        }
    }

    @Test
    fun quadroPaisagemComAparelhoEmPaisagemPreencheSemBarras() {
        for (sensor in listOf(90, 270)) {
            for (d in listOf(270f, 90f)) {
                val l = OutputOrientation.layout(1920, 1080, cameraAspect(sensor), d, true)
                assertFalse("sensor=$sensor d=$d", l.hasBars)
            }
        }
    }

    @Test
    fun gravacaoRetratoQueVaiParaPaisagemGanhaBarrasSemReiniciar() {
        // Quadro 1080x1920 fixo; conteúdo girado vira 16:9 => barras em cima e embaixo.
        for (d in listOf(270f, 90f)) {
            val l = OutputOrientation.layout(1080, 1920, cameraAspect(90), d, true)
            eq(1f, l.scaleX)
            assertNear((9f / 16f) / (16f / 9f), l.scaleY)
            eq(d, l.rotationDegrees)
        }
    }

    @Test
    fun gravacaoPaisagemQueVaiParaRetratoGanhaBarrasLaterais() {
        for (d in listOf(0f, 180f)) {
            val l = OutputOrientation.layout(1920, 1080, cameraAspect(90), d, true)
            assertNear((9f / 16f) / (16f / 9f), l.scaleX)
            eq(1f, l.scaleY)
            eq(d, l.rotationDegrees)
        }
    }

    @Test
    fun paisagemInvertidaSoMudaOAnguloNaoOEncaixe() {
        val a = OutputOrientation.layout(1920, 1080, cameraAspect(90), 270f, true)
        val b = OutputOrientation.layout(1920, 1080, cameraAspect(90), 90f, true)
        eq(a.scaleX, b.scaleX)
        eq(a.scaleY, b.scaleY)
        eq(180f, kotlin.math.abs(a.rotationDegrees - b.rotationDegrees))
        val c = OutputOrientation.layout(1080, 1920, cameraAspect(90), 0f, true)
        val d = OutputOrientation.layout(1080, 1920, cameraAspect(90), 180f, true)
        eq(c.scaleX, d.scaleX)
        eq(c.scaleY, d.scaleY)
        eq(180f, kotlin.math.abs(c.rotationDegrees - d.rotationDegrees))
    }

    @Test
    fun uvcNoQuadroPaisagemPreencheEIgnoraOGiroDoAparelho() {
        for (d in displays) {
            val l = OutputOrientation.layout(1920, 1080, 16f / 9f, d, false)
            eq(0f, l.rotationDegrees)
            assertFalse("d=$d", l.hasBars)
        }
    }

    @Test
    fun uvcEmQuadroRetratoFicaComBarrasEmCimaEEmbaixo() {
        val l = OutputOrientation.layout(1080, 1920, 16f / 9f, 0f, false)
        eq(1f, l.scaleX)
        assertNear((9f / 16f) / (16f / 9f), l.scaleY)
    }

    @Test
    fun aspectoQuaseIgualNaoCriaBarraDeUmPixel() {
        // 1920x1080 (1.7778) contra conteúdo 1.7800: dentro da tolerância => escala 1.
        val l = OutputOrientation.layout(1920, 1080, 1.78f, 0f, false)
        assertFalse(l.hasBars)
    }

    @Test
    fun cameraComSensor4x3EmQuadro16x9GanhaBarrasLateraisEmVezDeEsticar() {
        // Buffer 4:3 (4000x3000) em paisagem, quadro 16:9: antes esticava, agora encaixa.
        val src = OutputOrientation.sourceAspect(4000, 3000, 90)
        val l = OutputOrientation.layout(1920, 1080, src, 270f, true)
        assertNear(((4f / 3f) / (16f / 9f)), l.scaleX)
        eq(1f, l.scaleY)
    }

    @Test
    fun quadroOuFonteInvalidosPreenchemSemBarras() {
        val l = OutputOrientation.layout(0, 0, 16f / 9f, 90f, true)
        eq(1f, l.scaleX)
        eq(1f, l.scaleY)
        val m = OutputOrientation.layout(1920, 1080, 0f, 90f, true)
        eq(1f, m.scaleX)
        eq(1f, m.scaleY)
    }
}
