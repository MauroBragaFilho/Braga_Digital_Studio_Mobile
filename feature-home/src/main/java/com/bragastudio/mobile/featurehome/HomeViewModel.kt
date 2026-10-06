package com.bragastudio.mobile.featurehome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bragastudio.mobile.core.database.RecordingStatus
import com.bragastudio.mobile.core.domain.HardwareMetrics
import com.bragastudio.mobile.core.domain.HardwareMonitorService
import com.bragastudio.mobile.core.domain.SettingsRepository
import com.bragastudio.mobile.core.repository.LutRepository
import com.bragastudio.mobile.core.repository.RecordingRepository
import com.bragastudio.mobile.corecapture.domain.CaptureState
import com.bragastudio.mobile.corecapture.status.CameraStatusProvider
import com.bragastudio.mobile.coremedia.domain.MediaGraph
import com.bragastudio.mobile.network.LinkServerController
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class HomeViewModel @Inject constructor(
    hardwareMonitorService: HardwareMonitorService,
    settingsRepository: SettingsRepository,
    lutRepository: LutRepository,
    recordingRepository: RecordingRepository,
    linkController: LinkServerController,
    mediaGraph: MediaGraph,
    private val cameraStatusProvider: CameraStatusProvider,
) : ViewModel() {

    private val metrics: Flow<HardwareMetrics> = hardwareMonitorService.metrics

    // IP local relido só quando o estado do Wi-Fi muda (a varredura de interfaces é feita em IO).
    private val ip: Flow<String?> = metrics
        .map { it.isWifiConnected }
        .distinctUntilChanged()
        .map { localIpv4() }
        .flowOn(Dispatchers.IO)

    private val equipment = combine(metrics, ip) { m, address -> m to address }

    private val configuration = combine(
        settingsRepository.videoSettings,
        settingsRepository.ndiSettings.map { it.isEnabled }.distinctUntilChanged(),
        linkController.enabled,
        linkController.serverRunning,
        lutRepository.getActiveLut().map { it?.displayName }.distinctUntilChanged(),
    ) { video, ndiEnabled, linkEnabled, linkRunning, lutName ->
        HomeUiState(
            loaded = true,
            formatSummary = HomeStatus.plainFormat(video),
            source = HomeStatus.sourceKind(video.videoSource),
            ndiEnabled = ndiEnabled,
            link = when {
                !linkEnabled -> LinkPhase.Off
                linkRunning -> LinkPhase.Running
                else -> LinkPhase.Starting
            },
            activeLutName = lutName,
            bitrateMbps = video.bitrateMbps,
        )
    }

    // Gravação em andamento (linha IN_PROGRESS recente): só muda quando o Room muda.
    private val recordingStart: Flow<Long?> = recordingRepository.getAllRecordings()
        .map { rows ->
            HomeStatus.activeRecordingStart(
                rows.filter { it.status == RecordingStatus.IN_PROGRESS }.map { it.createdAt },
                System.currentTimeMillis(),
            )
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    // Câmera "ativa" = sessão aberta (Monitor, gravação ou NDI); com a Home sozinha o grafo fica ocioso.
    private val cameraActive: Flow<Boolean> = mediaGraph.captureState
        .map { it == CaptureState.READY || it == CaptureState.RECORDING }
        .distinctUntilChanged()

    private val equipmentAndCamera = combine(equipment, cameraActive, cameraStatusProvider.status) { eq, active, status -> Triple(eq, active, status) }

    val uiState: StateFlow<HomeUiState> = combine(equipmentAndCamera, configuration, recordingStart) { (eq, active, status), config, rec ->
        val (m, address) = eq
        config.copy(metrics = m, ipAddress = address, recordingStartedAt = rec, cameraActive = active, camera = status)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState(),
    )

    /** Relê as câmeras (ao voltar para a Home: permissão concedida, câmera USB conectada...). */
    fun refreshCamera() = cameraStatusProvider.refresh()

    private fun localIpv4(): String? = try {
        Collections.list(NetworkInterface.getNetworkInterfaces() ?: return null)
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
