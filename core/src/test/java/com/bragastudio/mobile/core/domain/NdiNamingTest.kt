package com.bragastudio.mobile.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdiNamingTest {
    @Test
    fun `nome vazio cai no nome do aparelho`() {
        assertEquals("Galaxy S20 FE", NdiNaming.sourceName("", "Galaxy S20 FE"))
        assertEquals("Galaxy S20 FE", NdiNaming.sourceName("   ", "Galaxy S20 FE"))
        assertEquals("Galaxy S20 FE", NdiNaming.sourceName(null, "Galaxy S20 FE"))
    }

    @Test
    fun `prefixo antigo BDSM e removido`() {
        assertEquals("SM-G780G", NdiNaming.sourceName("BDSM - SM-G780G", "x"))
        assertEquals("CAM A", NdiNaming.sourceName("bdsm-CAM A", "x"))
        assertEquals("x", NdiNaming.sourceName("BDSM - ", "x"))
    }

    @Test
    fun `nome definido pelo usuario e preservado`() {
        assertEquals("Camera Palco", NdiNaming.sourceName("Camera Palco", "x"))
        assertEquals("BDSM Studio Cam", NdiNaming.sourceName("BDSM Studio Cam", "x"))
    }

    @Test
    fun `controle e tamanho sao saneados`() {
        assertEquals("AB", NdiNaming.sourceName("A\u0000B", "x"))
        assertEquals(64, NdiNaming.sourceName("a".repeat(200), "x").length)
    }

    @Test
    fun `config fixa a maquina em BDSM`() {
        assertEquals("{\"ndi\":{\"machinename\":\"BDSM\"}}", NdiNaming.configJson())
        assertTrue(NdiNaming.configJson("A\"B").contains("A\\\"B"))
    }
}
