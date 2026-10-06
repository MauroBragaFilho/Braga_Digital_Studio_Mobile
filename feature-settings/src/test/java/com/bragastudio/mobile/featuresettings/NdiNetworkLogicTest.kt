package com.bragastudio.mobile.featuresettings

import com.bragastudio.mobile.coremedia.ndi.NdiDiscoveryPhase
import com.bragastudio.mobile.coremedia.ndi.NdiDiscoveryState
import com.bragastudio.mobile.coremedia.ndi.NdiSortMode
import com.bragastudio.mobile.coremedia.ndi.NdiSourceNames
import com.bragastudio.mobile.coremedia.ndi.NdiSourceType
import com.bragastudio.mobile.coremedia.ndi.ProximityBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NdiNetworkLogicTest {
    private fun src(name: String, ip: String = "10.0.0.7:5961", self: String? = null) = NdiSourceNames.build(name, ip, self)

    @Test
    fun `estados loading vazio erro e lista`() {
        assertEquals(NdiRadarUiState.Searching, NdiNetworkLogic.build(NdiDiscoveryState(NdiDiscoveryPhase.SEARCHING), NdiSortMode.PROXIMITY, true))
        assertEquals(NdiRadarUiState.Searching, NdiNetworkLogic.build(NdiDiscoveryState(NdiDiscoveryPhase.IDLE), NdiSortMode.PROXIMITY, true))
        assertEquals(NdiRadarUiState.Empty(true), NdiNetworkLogic.build(NdiDiscoveryState(NdiDiscoveryPhase.READY), NdiSortMode.PROXIMITY, true))
        assertEquals(NdiRadarUiState.Empty(false), NdiNetworkLogic.build(NdiDiscoveryState(NdiDiscoveryPhase.READY), NdiSortMode.PROXIMITY, false))
        assertEquals(NdiRadarUiState.Error, NdiNetworkLogic.build(NdiDiscoveryState(NdiDiscoveryPhase.ERROR), NdiSortMode.PROXIMITY, true))
        val state = NdiDiscoveryState(NdiDiscoveryPhase.READY, listOf(src("BDSM (A)")))
        assertTrue(NdiNetworkLogic.build(state, NdiSortMode.PROXIMITY, true) is NdiRadarUiState.Content)
        // procurando mas já com fontes: mostra a lista
        val partial = NdiDiscoveryState(NdiDiscoveryPhase.SEARCHING, listOf(src("BDSM (A)")))
        assertTrue(NdiNetworkLogic.build(partial, NdiSortMode.PROXIMITY, true) is NdiRadarUiState.Content)
    }

    @Test
    fun `limita a 24 fontes e informa as ocultas`() {
        val sources = (1..30).map { src("PC$it (Camera)") }
        val content = NdiNetworkLogic.build(NdiDiscoveryState(NdiDiscoveryPhase.READY, sources), NdiSortMode.PROXIMITY, true) as NdiRadarUiState.Content
        assertEquals(24, content.items.size)
        assertEquals(6, content.hiddenCount)
    }

    @Test
    fun `estados da fonte por proximidade e este aparelho`() {
        val self = NdiNetworkLogic.toUi(src("BDSM (Eu)", self = "Eu"), null)
        assertEquals(NdiDeviceState.SELF, self.state)
        assertFalse(self.canPreview)
        val measuring = NdiNetworkLogic.toUi(src("BDSM (B)"), null)
        assertTrue(measuring.isMeasuring)
        val noResponse = NdiNetworkLogic.toUi(src("BDSM (C)"), ProximityBand.UNKNOWN)
        assertEquals(NdiDeviceState.NO_RESPONSE, noResponse.state)
        val near = NdiNetworkLogic.toUi(src("BDSM (D)"), ProximityBand.NEAR)
        assertEquals(NdiDeviceState.AVAILABLE, near.state)
        assertTrue(near.canPreview)
    }

    @Test
    fun `ordem e aneis por proximidade e por tipo`() {
        val sources = listOf(src("BDSM (Longe)"), src("BDSM (Perto)"), src("PC (OBS)"))
        val prox = mapOf("BDSM (Longe)" to ProximityBand.FAR, "BDSM (Perto)" to ProximityBand.NEAR, "PC (OBS)" to ProximityBand.MEDIUM)
        val state = NdiDiscoveryState(NdiDiscoveryPhase.READY, sources, prox)
        val byProx = (NdiNetworkLogic.build(state, NdiSortMode.PROXIMITY, true) as NdiRadarUiState.Content).items
        assertEquals(listOf("Perto", "PC (OBS)", "Longe"), byProx.map { it.displayName })
        assertEquals(listOf(0, 1, 2), byProx.map { NdiNetworkLogic.ringFor(it, NdiSortMode.PROXIMITY) })
        val byType = (NdiNetworkLogic.build(state, NdiSortMode.TYPE, true) as NdiRadarUiState.Content).items
        assertEquals(NdiSourceType.OBS, byType.last().type)
        assertEquals(2, byType.map { NdiNetworkLogic.ringFor(it, NdiSortMode.TYPE) }.toSet().size)
    }

    @Test
    fun `layout do radar e estavel e sem sobreposicao no mesmo anel`() {
        val items = (1..6).map { NdiNetworkLogic.toUi(src("BDSM (D$it)"), ProximityBand.NEAR) }
        val a = NdiNetworkLogic.layout(items, NdiSortMode.PROXIMITY)
        val b = NdiNetworkLogic.layout(items.reversed(), NdiSortMode.PROXIMITY)
        assertEquals(a.sortedBy { it.id }, b.sortedBy { it.id })
        assertEquals(6, a.map { it.angle }.toSet().size)
        assertTrue(a.all { it.ring == 0 })
    }

    @Test
    fun `modelos de UI nunca expoem o IP`() {
        val ip = "192.168.1.23"
        val raw = NdiSourceNames.build("BDSM (Galaxy)", "$ip:5961", null)
        val ui = NdiNetworkLogic.toUi(raw, ProximityBand.NEAR)
        assertFalse(ui.toString().contains(ip))
        val state = NdiNetworkLogic.build(NdiDiscoveryState(NdiDiscoveryPhase.READY, listOf(raw)), NdiSortMode.PROXIMITY, true)
        assertFalse(state.toString().contains(ip))
        // nem por reflexão: nenhum campo do modelo de UI guarda o endereço
        val fields = NdiSourceUi::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(fields.any { it.contains("address") || it == "ip" || it == "url" })
        // nome com IP embutido é mascarado
        val tricky = NdiNetworkLogic.toUi(NdiSourceNames.build("$ip (Camera)", null, null), null)
        assertFalse(tricky.displayName.contains(ip))
    }
}
