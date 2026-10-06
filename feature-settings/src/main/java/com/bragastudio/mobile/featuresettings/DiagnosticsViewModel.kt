package com.bragastudio.mobile.featuresettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bragastudio.mobile.core.domain.BspSettings
import com.bragastudio.mobile.core.domain.NdiSettings
import com.bragastudio.mobile.core.domain.SettingsRepository
import com.bragastudio.mobile.corecapture.domain.CameraRepository
import com.bragastudio.mobile.coremedia.bsp.BspConnectionState
import com.bragastudio.mobile.coremedia.domain.BspManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** ViewModel da tela de Diagnóstico (M48): câmeras + ferramenta de teste NDI/BSP. */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    bspManager: BspManager,
    cameraRepository: CameraRepository,
) : ViewModel() {

    private fun <T> Flow<T>.share(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val availableCameras = cameraRepository.availableCameras

    val ndiSettings: StateFlow<NdiSettings> = settingsRepository.ndiSettings.share(NdiSettings())
    val bspSettings: StateFlow<BspSettings> = settingsRepository.bspSettings.share(BspSettings())

    val bspConnectionState: StateFlow<BspConnectionState> =
        bspManager.connectionState.share(BspConnectionState.DISCONNECTED)
    val bspRttMs: StateFlow<Long> = bspManager.rttMs.share(0L)
    val bspBitrateMbps: StateFlow<Float> = bspManager.bitrateMbps.share(0f)
    val bspPacketLossPercent: StateFlow<Float> = bspManager.packetLossPercent.share(0f)

    /** Grava o IP do receptor BSP somente se for um host válido. Devolve se foi aceito. */
    fun setBspTargetHost(host: String): Boolean {
        val clean = host.trim()
        if (clean.isNotEmpty() && !SettingsRules.isValidHost(clean)) return false
        viewModelScope.launch { settingsRepository.setBspTargetHost(clean) }
        return true
    }

    /**
     * Switch de desenvolvimento: garante que só um protocolo de transmissão
     * fique ligado por vez. Desliga o outro primeiro — o MediaGraph reage a
     * cada flag via seus próprios coletores. BSP sem host válido não liga.
     */
    fun setActiveStreamingProtocol(useBsp: Boolean) {
        viewModelScope.launch {
            if (useBsp) {
                if (!SettingsRules.isValidHost(bspSettings.value.targetHost)) return@launch
                settingsRepository.setNdiEnabled(false)
                settingsRepository.setBspEnabled(true)
            } else {
                settingsRepository.setBspEnabled(false)
                settingsRepository.setNdiEnabled(true)
            }
        }
    }
}
