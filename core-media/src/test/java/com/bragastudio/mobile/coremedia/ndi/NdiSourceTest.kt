package com.bragastudio.mobile.coremedia.ndi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NdiSourceTest {

    @Test
    fun `classifica BDSM OBS vMix e outros pelo nome`() {
        assertEquals(NdiSourceType.BDSM, NdiSourceNames.classify("BDSM (Galaxy A51)"))
        assertEquals(NdiSourceType.OBS, NdiSourceNames.classify("DESKTOP-1 (OBS)"))
        assertEquals(NdiSourceType.OBS, NdiSourceNames.classify("OBS-PC (Programa)"))
        assertEquals(NdiSourceType.VMIX, NdiSourceNames.classify("STUDIO (vMix - Output 1)"))
        assertEquals(NdiSourceType.OTHER, NdiSourceNames.classify("CAMERA-PTZ (Entrada 1)"))
        // "robson" contém "obs" mas não é a palavra OBS
        assertEquals(NdiSourceType.OTHER, NdiSourceNames.classify("ROBSON-PC (Camera)"))
    }

    @Test
    fun `nome de exibicao do BDSM e so o aparelho`() {
        assertEquals("Galaxy A51", NdiSourceNames.displayName("BDSM (Galaxy A51)"))
        assertEquals("DESKTOP-1 (OBS)", NdiSourceNames.displayName("DESKTOP-1 (OBS)"))
    }

    @Test
    fun `nunca expoe IP no nome nem no toString`() {
        val ip = "192.168.0.42"
        assertEquals("PC ••• (Cam)", NdiSourceNames.maskIps("PC $ip:5961 (Cam)"))
        assertFalse(NdiSourceNames.displayName("192.168.0.42 (Camera)").contains(ip))
        val source = NdiSourceNames.build("BDSM (Aparelho)", "$ip:5961", null)
        assertFalse(source.toString().contains(ip))
        assertFalse(source.displayName.contains(ip))
        assertFalse(source.name.contains(ip))
    }

    @Test
    fun `detecta este aparelho pelo nome do sender ativo`() {
        assertTrue(NdiSourceNames.isSelf("BDSM (Meu S20)", "Meu S20"))
        assertTrue(NdiSourceNames.isSelf("LOCALHOST (Meu S20)", "Meu S20"))
        assertFalse(NdiSourceNames.isSelf("LOCALHOST (Outro)", "Meu S20"))
        assertFalse(NdiSourceNames.isSelf("BDSM (Outro)", "Meu S20"))
        assertFalse(NdiSourceNames.isSelf("BDSM (Meu S20)", null))
        assertFalse(NdiSourceNames.isSelf("BDSM (Meu S20)", ""))
    }

    @Test
    fun `faixas de latencia`() {
        assertEquals(ProximityBand.NEAR, NdiProximity.band(0))
        assertEquals(ProximityBand.NEAR, NdiProximity.band(8))
        assertEquals(ProximityBand.MEDIUM, NdiProximity.band(9))
        assertEquals(ProximityBand.MEDIUM, NdiProximity.band(25))
        assertEquals(ProximityBand.FAR, NdiProximity.band(26))
        assertEquals(ProximityBand.UNKNOWN, NdiProximity.band(null))
    }

    @Test
    fun `mediana de amostras`() {
        assertNull(NdiProximity.median(emptyList()))
        assertEquals(5L, NdiProximity.median(listOf(5)))
        assertEquals(7L, NdiProximity.median(listOf(30, 7, 3)))
        assertEquals(10L, NdiProximity.median(listOf(8, 12, 100, 4)))
    }

    @Test
    fun `tracker suaviza picos e perde historico sem resposta`() {
        val t = NdiLatencyTracker(window = 5)
        assertEquals(5L, t.record("a", 5))
        assertEquals(5L, t.record("a", 5))
        assertEquals(5L, t.record("a", 200)) // um pico isolado nao muda a mediana
        assertEquals(5L, t.record("a", 6)) // mediana de [5,5,200,6]
        val tracker2 = NdiLatencyTracker(window = 2)
        tracker2.record("b", 10)
        assertNull(tracker2.record("b", null))
    }

    @Test
    fun `interpreta endereco ip porta`() {
        assertEquals("192.168.0.5" to 5961, NdiProximity.parseEndpoint("192.168.0.5:5961"))
        assertEquals("host" to 5960, NdiProximity.parseEndpoint("host"))
        assertEquals("fe80::1" to 5961, NdiProximity.parseEndpoint("[fe80::1]:5961"))
        assertNull(NdiProximity.parseEndpoint(""))
        assertNull(NdiProximity.parseEndpoint(null))
    }

    private data class Item(val name: String, val type: NdiSourceType, val band: ProximityBand)

    private val items = listOf(
        Item("c-obs", NdiSourceType.OBS, ProximityBand.NEAR),
        Item("a-bdsm-far", NdiSourceType.BDSM, ProximityBand.FAR),
        Item("b-bdsm-near", NdiSourceType.BDSM, ProximityBand.NEAR),
        Item("z-unknown", NdiSourceType.OTHER, ProximityBand.UNKNOWN),
        Item("self", NdiSourceType.BDSM, ProximityBand.SELF),
    )

    @Test
    fun `ordena por proximidade e por tipo`() {
        val byBand = NdiSourceSorting.sorted(items, NdiSortMode.PROXIMITY, { it.type }, { it.band }, { it.name })
        assertEquals(listOf("self", "b-bdsm-near", "c-obs", "a-bdsm-far", "z-unknown"), byBand.map { it.name })
        val byType = NdiSourceSorting.sorted(items, NdiSortMode.TYPE, { it.type }, { it.band }, { it.name })
        assertEquals(listOf("self", "b-bdsm-near", "a-bdsm-far", "c-obs", "z-unknown"), byType.map { it.name })
    }

    @Test
    fun `qualidade reduz a partir do termico moderado`() {
        assertEquals(NdiPreviewQuality.STANDARD, NdiQualityPolicy.forThermal(0))
        assertEquals(NdiPreviewQuality.STANDARD, NdiQualityPolicy.forThermal(1))
        assertEquals(NdiPreviewQuality.LOW, NdiQualityPolicy.forThermal(2))
        assertEquals(NdiPreviewQuality.LOW, NdiQualityPolicy.forThermal(4))
    }
}
