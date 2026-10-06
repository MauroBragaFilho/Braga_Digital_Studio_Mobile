package com.bragastudio.mobile.coremedia.ndi

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class NdiPreviewPhase { IDLE, CONNECTING, LIVE, NO_SIGNAL, ERROR }

/** Estado da prévia. Tamanho em pixels do que está sendo exibido (já limitado a 720p). */
data class NdiPreviewState(
    val phase: NdiPreviewPhase = NdiPreviewPhase.IDLE,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val fps: Float = 0f,
    val quality: NdiPreviewQuality = NdiPreviewQuality.STANDARD,
) {
    /** Proporção para o letterbox (16:9 enquanto não chega o 1º quadro). */
    val aspectRatio: Float get() = if (videoWidth > 0 && videoHeight > 0) videoWidth.toFloat() / videoHeight else 16f / 9f
}

/**
 * Recebimento de UMA fonte NDI por vez para a prévia em tela cheia (NDIlib_recv numa thread
 * nativa dedicada; os quadros vão direto para a Surface, sem Bitmap). Só vídeo, teto de 720p.
 * Sobe/derruba com a tela: [open] ao ficar visível, [close] ao sair, escurecer ou ir ao segundo
 * plano. Com o aparelho quente (térmico >= MODERATE) usa a banda mais baixa.
 */
@Singleton
class NdiReceiver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val discovery: NdiDiscovery,
) {
    private val _state = MutableStateFlow(NdiPreviewState())
    val state: StateFlow<NdiPreviewState> = _state.asStateFlow()

    // Operações nativas serializadas (start/stop podem esperar a thread de captura terminar).
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var pollJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    @Volatile private var sourceName: String? = null

    @Volatile private var quality = NdiPreviewQuality.STANDARD

    /** Abre a prévia da fonte [name]. Fecha qualquer prévia anterior (1 por vez). */
    @Synchronized
    fun open(name: String) {
        sourceName = name
        quality = NdiQualityPolicy.forThermal(currentThermalStatus())
        _state.value = NdiPreviewState(NdiPreviewPhase.CONNECTING, quality = quality)
        acquireLock()
        registerThermal()
        startNative(name, quality)
        startPolling()
    }

    /** Fecha o receptor e solta tudo (idempotente). */
    @Synchronized
    fun close() {
        sourceName = null
        pollJob?.cancel()
        pollJob = null
        unregisterThermal()
        releaseLock()
        ioScope.launch { if (NdiNative.loaded) NdiNative.nativeRecvStop() }
        _state.value = NdiPreviewState()
    }

    /** Surface de destino (null solta). Chamar com a Surface válida e soltar em surfaceDestroyed. */
    fun setSurface(surface: Surface?) {
        if (NdiNative.loaded) NdiNative.nativeRecvSetSurface(surface)
    }

    private fun startNative(name: String, q: NdiPreviewQuality) {
        val address = discovery.addressFor(name)
        ioScope.launch {
            val ok = NdiNative.loaded && NdiNative.nativeRecvStart(name, address, q == NdiPreviewQuality.LOW)
            if (!ok && sourceName == name) _state.value = _state.value.copy(phase = NdiPreviewPhase.ERROR)
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = pollScope.launch {
            while (isActive) {
                delay(POLL_MS)
                val raw = if (NdiNative.loaded) NdiNative.nativeRecvState() else null
                val current = _state.value
                if (sourceName == null) break
                if (raw == null || raw.size < 6) continue // o receptor ainda está subindo
                val phase = when (raw[0]) {
                    1 -> NdiPreviewPhase.LIVE
                    2 -> NdiPreviewPhase.NO_SIGNAL
                    3 -> NdiPreviewPhase.ERROR
                    else -> NdiPreviewPhase.CONNECTING
                }
                val next = current.copy(phase = phase, videoWidth = raw[3], videoHeight = raw[4], fps = raw[5] / 10f)
                if (next != current) _state.value = next
            }
        }
    }

    private fun currentThermalStatus(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        } else {
            0
        }
    } catch (_: Throwable) {
        0
    }

    private fun registerThermal() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || thermalListener != null) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val listener = PowerManager.OnThermalStatusChangedListener { status -> onThermalChanged(status) }
            pm.addThermalStatusListener(listener)
            thermalListener = listener
        } catch (t: Throwable) {
            Log.w(TAG, "Sem monitoramento térmico: ${t.message}")
        }
    }

    private fun unregisterThermal() {
        val listener = thermalListener ?: return
        thermalListener = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                (context.getSystemService(Context.POWER_SERVICE) as PowerManager).removeThermalStatusListener(listener)
            } catch (_: Throwable) {
            }
        }
    }

    @Synchronized
    private fun onThermalChanged(status: Int) {
        val name = sourceName ?: return
        val wanted = NdiQualityPolicy.forThermal(status)
        // Só desce de qualidade com calor; não volta sozinho (evita reiniciar o receptor em loop).
        if (wanted == NdiPreviewQuality.LOW && quality != NdiPreviewQuality.LOW) {
            quality = NdiPreviewQuality.LOW
            _state.value = _state.value.copy(quality = NdiPreviewQuality.LOW, phase = NdiPreviewPhase.CONNECTING)
            startNative(name, NdiPreviewQuality.LOW)
        }
    }

    private fun acquireLock() {
        try {
            if (multicastLock == null) {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("bdsm_ndi_recv_lock").apply { setReferenceCounted(false) }
            }
            multicastLock?.takeIf { !it.isHeld }?.acquire()
        } catch (t: Throwable) {
            Log.w(TAG, "MulticastLock indisponível: ${t.message}")
        }
    }

    private fun releaseLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val TAG = "NdiReceiver"
        const val POLL_MS = 500L
    }
}
