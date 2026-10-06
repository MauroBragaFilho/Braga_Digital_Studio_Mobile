package com.bragastudio.mobile.featuresettings

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bragastudio.mobile.core.domain.HardwareMonitorService
import com.bragastudio.mobile.core.domain.NdiSettings
import com.bragastudio.mobile.core.domain.SettingsRepository
import com.bragastudio.mobile.corecapture.status.CameraStatus
import com.bragastudio.mobile.corecapture.status.CameraStatusProvider
import com.bragastudio.mobile.coremedia.domain.NdiManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Configuração do transmissor NDI com opções tipadas. */
data class NdiConfigUiState(
    val enabled: Boolean = false,
    val audioEnabled: Boolean = true,
    /** Nome gravado (nunca vazio: o repositório troca vazio pelo padrão). */
    val streamName: String = "",
    val preset: StreamPreset = StreamPreset.DEFAULT,
)

/** Métricas reais do transmissor — 0/null significa "sem dado" e a UI mostra "--". */
data class NdiMetricsUiState(
    val bitrateMbps: Int = 0,
    val pipelineLatencyMs: Int = 0,
    val frameDropPct: Float = 0f,
    val connectionCount: Int = 0,
    val lastSentResolution: Pair<Int, Int>? = null,
    /** Entrada bruta (RGBA entregue ao SDK) em Mbps; 0 = sem dado. */
    val rawInputMbps: Int = 0,
    /** true quando [bitrateMbps] é estimativa (o SDK não reporta o bitrate real de rede). */
    val bitrateIsEstimate: Boolean = true,
)

/** IP nulo = sem rede ("Sem rede" na UI). */
data class NdiNetworkUiState(
    val ipAddress: String? = null,
    val wifiConnected: Boolean = false,
)

/** ViewModel da tela de NDI, separado do SettingsViewModel (M48). */
@HiltViewModel
class NdiSetupViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val ndiManager: NdiManager,
    hardwareMonitorService: HardwareMonitorService,
    cameraStatusProvider: CameraStatusProvider,
) : ViewModel() {

    /** Estado da câmera (cache compartilhado; não reabre a câmera). */
    val cameraStatus: StateFlow<CameraStatus> = cameraStatusProvider.status

    private fun <T> Flow<T>.share(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    /** Nome padrão (o mesmo que o repositório grava quando o campo fica vazio). */
    val defaultStreamName: String = com.bragastudio.mobile.core.domain.NdiNaming.fallbackName()

    private val ndiSettings: StateFlow<NdiSettings> =
        settingsRepository.ndiSettings.share(NdiSettings())

    val config: StateFlow<NdiConfigUiState> = ndiSettings
        .map {
            NdiConfigUiState(
                enabled = it.isEnabled,
                audioEnabled = it.isAudioEnabled,
                streamName = SettingsRules.effectiveStreamName(it.cameraName, defaultStreamName),
                preset = StreamPreset.fromPersisted(it.resolution),
            )
        }
        .distinctUntilChanged()
        .share(NdiConfigUiState(streamName = defaultStreamName))

    val metrics: StateFlow<NdiMetricsUiState> = combine(
        ndiManager.ndiBitrateMbps,
        ndiManager.ndiLatencyMs,
        ndiManager.ndiFrameDropPct,
        ndiManager.connectionCount,
        ndiManager.lastSentResolution,
    ) { bitrate, latency, drop, connections, resolution ->
        NdiMetricsUiState(bitrate, latency, drop, connections, resolution)
    }.combine(ndiManager.ndiRawInputMbps) { state, raw ->
        state.copy(rawInputMbps = raw, bitrateIsEstimate = ndiManager.ndiBitrateIsEstimate)
    }.share(NdiMetricsUiState())

    /** O sender NDI está de fato no ar (o interruptor só pede; o MediaGraph liga o motor). */
    val active: StateFlow<Boolean> = ndiManager.isNdiActive.share(false)

    private val failed = MutableStateFlow(false)

    /** Estado da tela simples: desativado, iniciando, ativo ou erro (com tentar de novo). */
    val phase: StateFlow<NdiScreenPhase> = combine(
        ndiSettings.map { it.isEnabled }.distinctUntilChanged(),
        active,
        failed,
    ) { enabled, isActive, hasFailed -> NdiScreenLogic.phase(enabled, isActive, hasFailed) }
        .distinctUntilChanged()
        .share(NdiScreenPhase.OFF)

    init {
        // Erros do motor NDI enquanto o usuário quer a transmissão ligada.
        viewModelScope.launch {
            ndiManager.errorEvents.collect { if (ndiSettings.value.isEnabled) failed.value = true }
        }
        // Cada vez que liga: se o sender não subir a tempo, a tela mostra o erro com "Tentar novamente".
        viewModelScope.launch {
            ndiSettings.map { it.isEnabled }.distinctUntilChanged().collectLatest { enabled ->
                failed.value = false
                if (enabled) {
                    delay(NdiScreenLogic.START_TIMEOUT_MS)
                    if (!ndiManager.isNdiActive.value) failed.value = true
                }
            }
        }
    }

    /** "Tentar novamente": desliga e liga de novo, com a mesma pausa usada ao trocar o nome. */
    fun retry() {
        viewModelScope.launch {
            failed.value = false
            applyAndRestartIfActive { }
            if (!ndiSettings.value.isEnabled) settingsRepository.setNdiEnabled(true)
        }
    }

    // Botão "Atualizar": reinicia a observação da rede (re-registra o callback e relê o IP).
    private val refreshTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ipAddress: StateFlow<String?> = refreshTick
        .flatMapLatest { LocalIp.observe(appContext) }
        .share(null)

    val network: StateFlow<NdiNetworkUiState> = combine(
        ipAddress,
        hardwareMonitorService.metrics.map { it.isWifiConnected }.distinctUntilChanged(),
    ) { ip, wifi -> NdiNetworkUiState(ip, wifi) }
        .share(NdiNetworkUiState())

    fun refreshNetwork() {
        refreshTick.value = refreshTick.value + 1
    }

    fun setNdiEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNdiEnabled(enabled) }
    }

    fun setNdiAudioEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNdiAudioEnabled(enabled) }
    }

    /**
     * Grava o nome do stream (chamado em onDone / perda de foco, não a cada
     * tecla). Vazio volta ao nome padrão. O MediaGraph só reage a isEnabled,
     * então com o NDI ativo o sender é reiniciado aqui para o novo nome valer.
     */
    fun commitStreamName(typed: String) {
        viewModelScope.launch {
            val current = ndiSettings.value
            if (!SettingsRules.streamNameChanged(typed, current.cameraName, defaultStreamName)) return@launch
            val sanitized = SettingsRules.sanitizeStreamName(typed)
            applyAndRestartIfActive { settingsRepository.setNdiCameraName(sanitized) }
        }
    }

    fun setPreset(preset: StreamPreset) {
        viewModelScope.launch {
            if (preset == StreamPreset.fromPersisted(ndiSettings.value.resolution)) return@launch
            applyAndRestartIfActive { settingsRepository.setNdiResolution(preset.persisted) }
        }
    }

    // Serializa mudanças que reiniciam o sender (evita off/on intercalados).
    private val restartMutex = Mutex()

    private suspend fun applyAndRestartIfActive(change: suspend () -> Unit) {
        // NonCancellable: sair da tela no meio não pode deixar o NDI desligado.
        withContext(NonCancellable) {
            restartMutex.withLock {
                val wasActive = ndiSettings.value.isEnabled
                change()
                if (wasActive) {
                    settingsRepository.setNdiEnabled(false)
                    delay(RESTART_GAP_MS) // tempo para o coletor do MediaGraph parar o sender
                    settingsRepository.setNdiEnabled(true)
                }
            }
        }
    }

    private companion object {
        const val RESTART_GAP_MS = 700L
    }
}
