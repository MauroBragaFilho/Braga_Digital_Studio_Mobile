package com.bragastudio.mobile.featuresettings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bragastudio.mobile.core.domain.BspSettings
import com.bragastudio.mobile.core.domain.NdiNaming
import com.bragastudio.mobile.core.domain.NdiSettings
import com.bragastudio.mobile.core.domain.SettingsRepository
import com.bragastudio.mobile.coremedia.bsp.BspTxt
import com.bragastudio.mobile.coremedia.domain.BspManager
import com.bragastudio.mobile.coremedia.domain.BspReceiver
import com.bragastudio.mobile.coremedia.domain.BspStats
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Estado da tela BSP (um único objeto para a composição). */
data class BspUiState(
    val phase: BspScreenPhase = BspScreenPhase.OFF,
    /** Nome com que a fonte aparece na rede (`BDSM (nome)`); só o nome, nunca o IP. */
    val announcedName: String = "",
    val receivers: List<BspReceiver> = emptyList(),
    val stats: BspStats = BspStats(),
    val allowPlainMedia: Boolean = false,
    /** Mensagem de erro legível (só em [BspScreenPhase.ERROR]). */
    val error: String? = null,
)

/** ViewModel da tela BSP: liga/desliga a transmissão (persistido, como o NDI) e expõe estado e estatísticas. */
@HiltViewModel
class BspViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val bspManager: BspManager,
) : ViewModel() {

    private fun <T> Flow<T>.share(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    private val bspSettings: StateFlow<BspSettings> = settingsRepository.bspSettings.share(BspSettings())

    private val failed = MutableStateFlow(false)
    private val lastError = MutableStateFlow<String?>(null)

    /** Nome da fonte igual ao do NDI (`BDSM (nome)`), mesmo com o BSP desligado. */
    private val announcedName: StateFlow<String> = settingsRepository.ndiSettings
        .map { ndi: NdiSettings -> BspTxt.instanceName(NdiNaming.MACHINE_NAME, NdiNaming.sourceName(ndi.cameraName, NdiNaming.deviceName(appContext))) }
        .distinctUntilChanged()
        .share(BspTxt.instanceName(NdiNaming.MACHINE_NAME, NdiNaming.fallbackName()))

    val state: StateFlow<BspUiState> = combine(
        bspSettings,
        bspManager.isBspActive,
        failed,
        combine(bspManager.receivers, bspManager.captureFlowing, bspManager.stats) { r, f, s -> Triple(r, f, s) },
        combine(announcedName, lastError) { n, e -> n to e },
    ) { settings, active, hasFailed, live, meta ->
        val (receivers, flowing, stats) = live
        val phase = BspScreenLogic.phase(settings.isEnabled, active, hasFailed, receivers.count { it.streaming }, flowing)
        BspUiState(
            phase = phase,
            announcedName = meta.first,
            receivers = receivers,
            stats = stats,
            allowPlainMedia = settings.allowPlainMedia,
            error = if (phase == BspScreenPhase.ERROR) BspScreenLogic.readableError(meta.second) else null,
        )
    }.distinctUntilChanged().share(BspUiState(announcedName = announcedName.value))

    init {
        // Erros da fonte enquanto o usuário quer a transmissão ligada.
        viewModelScope.launch {
            bspManager.errorEvents.collect {
                if (bspSettings.value.isEnabled) {
                    lastError.value = it
                    failed.value = true
                }
            }
        }
        // Cada vez que liga: se a fonte não subir a tempo, a tela mostra o erro com "Tentar novamente".
        viewModelScope.launch {
            bspSettings.map { it.isEnabled }.distinctUntilChanged().collectLatest { enabled ->
                failed.value = false
                lastError.value = null
                if (enabled) {
                    delay(BspScreenLogic.START_TIMEOUT_MS)
                    if (!bspManager.isBspActive.value) failed.value = true
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBspEnabled(enabled) }
    }

    /** "Tentar novamente": desliga e liga de novo com a mesma pausa usada no NDI. */
    fun retry() {
        viewModelScope.launch {
            failed.value = false
            lastError.value = null
            // NonCancellable: sair da tela no meio não pode deixar a transmissão desligada.
            withContext(NonCancellable) {
                settingsRepository.setBspEnabled(false)
                delay(RESTART_GAP_MS)
                settingsRepository.setBspEnabled(true)
            }
        }
    }

    fun setAllowPlainMedia(allow: Boolean) {
        viewModelScope.launch { settingsRepository.setBspAllowPlainMedia(allow) }
    }

    private companion object {
        const val RESTART_GAP_MS = 700L
    }
}
