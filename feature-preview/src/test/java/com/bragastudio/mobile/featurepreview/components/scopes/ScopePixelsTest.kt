package com.bragastudio.mobile.featurepreview.components.scopes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScopePixelsTest {

    @Test
    fun histogramPeak_ignoraNulosEUsaMinimoUm() {
        assertEquals(1, histogramPeak(null, null))
        assertEquals(1, histogramPeak(IntArray(256)))
        val g = IntArray(256).also { it[10] = 40 }
        val b = IntArray(256).also { it[200] = 90 }
        assertEquals(90, histogramPeak(null, g, b))
    }

    @Test
    fun waveform_semDadosFicaTransparente() {
        val out = IntArray(SCOPE_PIXELS) { 0x7F7F7F7F }
        waveformToPixels(IntArray(SCOPE_PIXELS), out)
        assertTrue(out.all { it == 0 })
    }

    @Test
    fun waveform_nivelZeroFicaEmbaixoENivel255NoTopo() {
        val data = IntArray(SCOPE_PIXELS)
        data[0 * SCOPE_SIZE + 5] = 0x00000001   // nível 0, coluna 5, só luma
        data[255 * SCOPE_SIZE + 7] = 0x00000001 // nível 255, coluna 7
        val out = IntArray(SCOPE_PIXELS)
        waveformToPixels(data, out)
        assertTrue(out[255 * SCOPE_SIZE + 5] ushr 24 > 0) // última linha = nível 0
        assertTrue(out[0 * SCOPE_SIZE + 7] ushr 24 > 0)   // primeira linha = nível 255
        assertEquals(0, out[0 * SCOPE_SIZE + 5])
    }

    @Test
    fun waveform_apenasVermelhoGeraPixelVermelho() {
        val data = IntArray(SCOPE_PIXELS)
        data[100 * SCOPE_SIZE + 3] = 5 shl 24 // 5 contagens de R
        val out = IntArray(SCOPE_PIXELS)
        waveformToPixels(data, out)
        val px = out[(255 - 100) * SCOPE_SIZE + 3]
        assertEquals(255, (px ushr 16) and 0xFF)
        assertEquals(0, (px ushr 8) and 0xFF)
        assertEquals(0, px and 0xFF)
    }

    @Test
    fun waveform_bufferMenorQueOEsperadoLimpaSaida() {
        val out = IntArray(SCOPE_PIXELS) { 1 }
        waveformToPixels(IntArray(10), out)
        assertTrue(out.all { it == 0 })
    }

    @Test
    fun vectorscope_densidadeZeroTransparenteEPontoInvertidoEmV() {
        val data = IntArray(SCOPE_PIXELS)
        data[128 * SCOPE_SIZE + 128] = 4 // centro (U=V=128)
        data[255 * SCOPE_SIZE + 128] = 4 // V máximo -> topo
        val out = IntArray(SCOPE_PIXELS)
        vectorscopeToPixels(data, out)
        assertTrue(out[(255 - 255) * SCOPE_SIZE + 128] ushr 24 > 0)
        assertTrue(out[(255 - 128) * SCOPE_SIZE + 128] ushr 24 > 0)
        assertEquals(0, out[0])
    }

    @Test
    fun vectorscope_centroEhNeutro() {
        val data = IntArray(SCOPE_PIXELS)
        data[128 * SCOPE_SIZE + 128] = 1
        val out = IntArray(SCOPE_PIXELS)
        vectorscopeToPixels(data, out)
        val px = out[(255 - 128) * SCOPE_SIZE + 128]
        assertEquals(255, (px ushr 16) and 0xFF)
        assertEquals(255, (px ushr 8) and 0xFF)
        assertEquals(255, px and 0xFF)
    }
}
