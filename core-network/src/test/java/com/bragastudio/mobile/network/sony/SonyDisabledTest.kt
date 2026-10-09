package com.bragastudio.mobile.network.sony

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Com a fonte Sony Wi-Fi desativada (CaptureFeatureFlags.SONY_WIFI_ENABLED = false) nada abre rede:
 * descoberta SSDP, comandos HTTP e vínculo de rede retornam na hora, sem socket nem requisição.
 * Estes testes protegem o estado "desligado"; se o interruptor for religado, devem ser ajustados.
 */
class SonyDisabledTest {

    @Test
    fun discoveryReturnsNullImmediatelyWithoutNetwork() = runBlocking {
        val start = System.nanoTime()
        assertNull(SonyCameraDiscovery().discoverCamera(timeoutMs = 4000))
        // Sem o guarda, o SSDP esperaria o timeout (4 s) e ainda tentaria o endpoint padrão por HTTP.
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500)
    }

    @Test
    fun rpcReturnsNullImmediatelyWithoutHttpRequest() = runBlocking {
        val start = System.nanoTime()
        val client = SonyCameraClient()
        assertNull(client.sendJsonRpc("getVersions"))
        assertNull(client.startLiveview())
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500)
    }

    @Test
    fun networkBindingIsNeverAcquired() = runBlocking {
        assertNull(SonyNetwork.acquire(timeoutMs = 50))
        assertNull(SonyNetwork.network)
    }
}
