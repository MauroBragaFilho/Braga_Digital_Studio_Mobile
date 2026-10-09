package com.bragastudio.mobile.featuresettings

import android.content.Context
import android.media.AudioDeviceInfo
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bragastudio.mobile.core.domain.HardwareMetrics
import com.bragastudio.mobile.core.domain.HardwareMonitorService
import com.bragastudio.mobile.core.domain.LandscapeNavSide
import com.bragastudio.mobile.core.domain.MonitorSettings
import com.bragastudio.mobile.core.domain.NdiNaming
import com.bragastudio.mobile.core.domain.SettingsRepository
import com.bragastudio.mobile.core.domain.ThemeMode
import com.bragastudio.mobile.core.domain.VideoSettings
import com.bragastudio.mobile.core.repository.LutRepository
import com.bragastudio.mobile.coremedia.domain.AudioManagerService
import com.bragastudio.mobile.coremedia.domain.NdiManager
import com.bragastudio.mobile.network.LinkServerController
import com.bragastudio.mobile.network.auth.PairedClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------
// Estados de UI fatiados (M48): cada tela/seção observa só o que usa.
// ---------------------------------------------------------------------------

/** Vídeo/codificação com opções tipadas; os valores crus persistidos continuam em [VideoSettings]. */
data class VideoUiState(
    val resolution: Resolution = Resolution.DEFAULT,
    val fps: Int = Fps.DEFAULT.value,
    /** Null quando o fps gravado não é uma das opções da UI (ex.: 25). */
    val fpsOption: Fps? = Fps.DEFAULT,
    val bitrateMbps: Int = 50,
    val codec: Codec = Codec.DEFAULT,
    val videoSource: VideoSourceOption = VideoSourceOption.DEFAULT,
    val hdrEnabled: Boolean = false,
) {
    /** HDR HLG10 só faz sentido com H.265 (mesma regra do HUD do Preview). */
    val hdrAvailable: Boolean get() = codec == Codec.H265
}

data class MonitorUiState(
    val zebraThreshold: Int = 100,
    val peakingColor: PeakingColor = PeakingColor.DEFAULT,
    val peakingSensitivity: PeakingSensitivity = PeakingSensitivity.DEFAULT,
)

data class AudioUiState(
    val devices: List<AudioDeviceInfo> = emptyList(),
    val selected: AudioDeviceInfo? = null,
)

/** Armazenamento: destino REAL, rótulo real e situação da pasta escolhida (M44). */
data class StorageUiState(
    val destination: StorageDestination = StorageDestination.APP,
    val folderUri: String? = null,
    val folderLabel: String? = null,
    val folderStatus: FolderStatus = FolderStatus.NONE,
    /** Caminho (relativo) do diretório privado do app onde o arquivo sempre é gravado primeiro. */
    val appDirLabel: String = "Android/data/…/files/Movies",
    val galleryAvailable: Boolean = StorageDestinationRules.galleryAvailable(android.os.Build.VERSION.SDK_INT),
)

/** Estado do BDSM Link (servidor para o OBS). */
data class LinkUiState(
    val enabled: Boolean = false,
    val serverRunning: Boolean = false,
    val paired: List<PairedClient> = emptyList(),
)

/** Aparência: modo de tema persistido e cores dinâmicas (Material You, só Android 12+). */
data class AppearanceUiState(
    val themeMode: ThemeMode = ThemeMode.Default,
    val dynamicColor: Boolean = false,
    val landscapeNavSide: LandscapeNavSide = LandscapeNavSide.Default,
    /** Nome da saudação da Home escolhido pela pessoa ("" = usa o nome do aparelho). */
    val displayName: String = "",
    /** Nome do aparelho (padrão da saudação). */
    val deviceName: String = "",
    val dynamicColorAvailable: Boolean = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S,
)

data class ConnectivityUiState(
    val ndiEnabled: Boolean = false,
    val ndiFrameDropPct: Float = 0f,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val audioManagerService: AudioManagerService,
    hardwareMonitorService: HardwareMonitorService,
    private val ndiManager: NdiManager,
    private val linkController: LinkServerController,
    // Injetado só pra ler qual LUT está ativa de verdade (mesma fonte usada
    // pelo Preview) — a seleção continua só no LutsViewModel/PreviewViewModel.
    lutRepository: LutRepository,
) : ViewModel() {

    private fun <T> Flow<T>.share(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    // Mantido público: RecordingsScreen também lê as métricas por este ViewModel.
    val hardwareMetrics: StateFlow<HardwareMetrics> =
        hardwareMonitorService.metrics.share(HardwareMetrics())

    /** Configurações cruas (fonte de verdade persistida). */
    private val videoSettings: StateFlow<VideoSettings> =
        settingsRepository.videoSettings.share(VideoSettings())

    private val monitorSettings: StateFlow<MonitorSettings> =
        settingsRepository.monitorSettings.share(MonitorSettings())

    val video: StateFlow<VideoUiState> = videoSettings
        .map { it.toUi() }
        .distinctUntilChanged()
        .share(VideoUiState())

    val monitor: StateFlow<MonitorUiState> = monitorSettings
        .map {
            MonitorUiState(
                zebraThreshold = it.zebraThreshold,
                peakingColor = PeakingColor.fromPersisted(it.focusPeakingColor),
                peakingSensitivity = PeakingSensitivity.fromPersisted(it.focusPeakingSensitivity),
            )
        }
        .distinctUntilChanged()
        .share(MonitorUiState())

    val audio: StateFlow<AudioUiState> = combine(
        audioManagerService.availableDevices,
        audioManagerService.selectedDevice,
    ) { devices, selected -> AudioUiState(devices, selected) }
        .share(AudioUiState())

    // Nome de exibição da LUT realmente ativa (mesma fonte que o Preview usa).
    val activeLutName: StateFlow<String> = lutRepository.getActiveLut()
        .map { it?.displayName ?: "Nenhum (Desativado)" }
        .share("Nenhum (Desativado)")

    val connectivity: StateFlow<ConnectivityUiState> = combine(
        settingsRepository.ndiSettings.map { it.isEnabled },
        ndiManager.ndiFrameDropPct,
    ) { enabled, drop -> ConnectivityUiState(enabled, drop) }
        .distinctUntilChanged()
        .share(ConnectivityUiState())

    // Nome do aparelho (padrão da saudação da Home); lido uma vez, só local.
    private val deviceName: String = NdiNaming.deviceName(appContext)

    // ---- Aparência ----
    val appearance: StateFlow<AppearanceUiState> = combine(
        settingsRepository.themeMode,
        settingsRepository.dynamicColorEnabled,
        settingsRepository.landscapeNavSide,
        settingsRepository.displayName,
    ) { mode, dynamic, side, name ->
        AppearanceUiState(themeMode = mode, dynamicColor = dynamic, landscapeNavSide = side, displayName = name, deviceName = deviceName)
    }
        .distinctUntilChanged()
        .share(AppearanceUiState())

    /** Nome de exibição da Home: vazio volta ao nome do aparelho; sem quebras de linha e com limite de tamanho. */
    fun setDisplayName(name: String) {
        viewModelScope.launch { settingsRepository.setDisplayName(name) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    fun setLandscapeNavSide(side: LandscapeNavSide) {
        viewModelScope.launch { settingsRepository.setLandscapeNavSide(side) }
    }

    fun setDynamicColorEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDynamicColorEnabled(enabled) }
    }

    // ---- BDSM Link (A1) ----
    val link: StateFlow<LinkUiState> = combine(
        linkController.enabled,
        linkController.serverRunning,
        linkController.paired,
    ) { enabled, running, paired -> LinkUiState(enabled, running, paired) }
        .share(LinkUiState())

    fun setLinkEnabled(enabled: Boolean) = linkController.setEnabled(enabled)
    fun revokeLinkClient(clientId: String) = linkController.revoke(clientId)
    fun revokeAllLinkClients() = linkController.revokeAll()

    // ---- Armazenamento (M44) ----
    private val folderStatus = MutableStateFlow(FolderStatus.NONE)

    val storage: StateFlow<StorageUiState> = combine(videoSettings, folderStatus) { vs, status ->
        val uri = vs.recordingDirectoryUri?.takeIf { it.isNotBlank() }
        StorageUiState(
            destination = StorageDestinationRules.resolve(uri, vs.saveToGallery),
            folderUri = uri,
            folderLabel = uri?.let { StorageAccess.describeFolder(it) },
            folderStatus = if (uri == null) FolderStatus.NONE else status,
            appDirLabel = StorageAccess.appDirLabel(appContext),
        )
    }.distinctUntilChanged().share(StorageUiState(appDirLabel = StorageAccess.appDirLabel(appContext)))

    /** Mensagens pontuais para a UI (Toast/Snackbar). */
    private val _events = Channel<String>(Channel.BUFFERED)
    val events: Flow<String> = _events.receiveAsFlow()

    init {
        // NDI usa o fps de gravação como alvo da métrica de perda de quadros.
        viewModelScope.launch {
            videoSettings.map { it.fps }.distinctUntilChanged().collect { ndiManager.setTargetFps(it) }
        }
    }

    // ---- Vídeo ----
    fun setVideoResolution(resolution: Resolution) {
        viewModelScope.launch { settingsRepository.setVideoResolution(resolution.persisted) }
    }

    fun setVideoFps(fps: Fps) {
        viewModelScope.launch { settingsRepository.setVideoFps(fps.value) }
    }

    fun setVideoBitrate(bitrateMbps: Int) {
        viewModelScope.launch { settingsRepository.setVideoBitrate(SettingsRules.coerceBitrate(bitrateMbps)) }
    }

    fun setVideoCodec(codec: Codec) {
        viewModelScope.launch { settingsRepository.setVideoCodec(codec.persisted) }
    }

    /** Aplica um preset de qualidade (resolução + fps + codec + bitrate) de uma vez. */
    fun applyQualityPreset(preset: QualityPreset) {
        viewModelScope.launch {
            settingsRepository.setVideoResolution(preset.resolution.persisted)
            settingsRepository.setVideoFps(preset.fps.value)
            settingsRepository.setVideoCodec(preset.codec.persisted)
            settingsRepository.setVideoBitrate(SettingsRules.coerceBitrate(preset.bitrateMbps))
        }
    }

    fun setVideoSource(source: VideoSourceOption) {
        viewModelScope.launch { settingsRepository.setVideoSource(source.persisted) }
    }

    // Preferência padrão de HDR (HLG10). A disponibilidade final em tempo real
    // ainda depende da lente ativa (supportsHdr10) — validado no PreviewViewModel.
    fun setVideoHdrEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setVideoHdrEnabled(enabled) }
    }

    // ---- Monitoramento ----
    fun setZebraThreshold(threshold: Int) {
        viewModelScope.launch { settingsRepository.setZebraThreshold(SettingsRules.coerceZebra(threshold)) }
    }

    fun setFocusPeakingColor(color: PeakingColor) {
        viewModelScope.launch { settingsRepository.setFocusPeakingColor(color.persisted) }
    }

    fun setFocusPeakingSensitivity(sensitivity: PeakingSensitivity) {
        viewModelScope.launch { settingsRepository.setFocusPeakingSensitivity(sensitivity.persisted) }
    }

    fun selectAudioDevice(device: AudioDeviceInfo) {
        viewModelScope.launch { audioManagerService.selectDevice(device) }
    }

    // ---- Destino da gravação (M44) ----

    /** Chamado ao abrir a tela: confere se a pasta SAF ainda existe e é gravável; senão volta ao app. */
    fun validateStorage() {
        viewModelScope.launch {
            val uri = videoSettings.value.recordingDirectoryUri?.takeIf { it.isNotBlank() }
            if (uri == null) {
                folderStatus.value = FolderStatus.NONE
                return@launch
            }
            val ok = withContext(Dispatchers.IO) { StorageAccess.isWritable(appContext, uri) }
            if (ok) {
                folderStatus.value = FolderStatus.OK
            } else {
                // Fallback automático: sem acesso à pasta a gravação falharia ao preparar o take.
                withContext(Dispatchers.IO) { StorageAccess.release(appContext, uri) }
                settingsRepository.setRecordingDirectoryUri(null)
                folderStatus.value = FolderStatus.NONE
                _events.trySend("A pasta de gravação não está mais acessível. Voltando para o armazenamento do app.")
            }
        }
    }

    /** O usuário escolheu uma pasta no seletor do sistema (OpenDocumentTree). */
    fun onFolderPicked(uri: Uri) {
        viewModelScope.launch {
            val newUri = uri.toString()
            val oldUri = videoSettings.value.recordingDirectoryUri?.takeIf { it.isNotBlank() }
            val taken = withContext(Dispatchers.IO) { StorageAccess.takePersistable(appContext, uri) }
            if (!taken) {
                _events.trySend("Não foi possível manter o acesso a essa pasta. Escolha outra.")
                return@launch
            }
            val writable = withContext(Dispatchers.IO) { StorageAccess.isWritable(appContext, newUri) }
            if (!writable) {
                if (newUri != oldUri) withContext(Dispatchers.IO) { StorageAccess.release(appContext, newUri) }
                _events.trySend("Sem permissão de escrita nessa pasta. Escolha outra.")
                return@launch
            }
            persistDestination(StorageDestination.FOLDER, newUri)
            // Libera só a permissão da URI antiga (e só se for outra pasta).
            if (oldUri != null && oldUri != newUri) {
                withContext(Dispatchers.IO) { StorageAccess.release(appContext, oldUri) }
            }
            folderStatus.value = FolderStatus.OK
        }
    }

    /** Troca para App ou Galeria (Pasta passa por [onFolderPicked]). */
    fun selectDestination(destination: StorageDestination) {
        if (destination == StorageDestination.FOLDER) return
        viewModelScope.launch {
            val oldUri = videoSettings.value.recordingDirectoryUri?.takeIf { it.isNotBlank() }
            persistDestination(destination, null)
            if (oldUri != null) withContext(Dispatchers.IO) { StorageAccess.release(appContext, oldUri) }
            folderStatus.value = FolderStatus.NONE
        }
    }

    /** Botão "Restaurar padrão": sem pasta e com os padrões do domínio. */
    fun restoreStorageDefaults() {
        viewModelScope.launch {
            val oldUri = videoSettings.value.recordingDirectoryUri?.takeIf { it.isNotBlank() }
            settingsRepository.setRecordingDirectoryUri(null)
            settingsRepository.setSaveToGallery(VideoSettings().saveToGallery)
            if (oldUri != null) withContext(Dispatchers.IO) { StorageAccess.release(appContext, oldUri) }
            folderStatus.value = FolderStatus.NONE
        }
    }

    private suspend fun persistDestination(destination: StorageDestination, folderUri: String?) {
        val p = StorageDestinationRules.persistedFor(destination, folderUri)
        settingsRepository.setRecordingDirectoryUri(p.directoryUri)
        settingsRepository.setSaveToGallery(p.saveToGallery)
    }

    private fun VideoSettings.toUi() = VideoUiState(
        resolution = Resolution.fromPersisted(resolution),
        fps = fps,
        fpsOption = Fps.fromValueOrNull(fps),
        bitrateMbps = bitrateMbps,
        codec = Codec.fromPersisted(codec),
        videoSource = VideoSourceOption.fromPersisted(videoSource),
        hdrEnabled = hdrEnabled,
    )
}
