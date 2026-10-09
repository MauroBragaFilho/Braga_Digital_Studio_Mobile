package com.bragastudio.mobile.featurepreview

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bragastudio.mobile.core.domain.HardwareMonitorService
import com.bragastudio.mobile.core.domain.NdiSettings
import com.bragastudio.mobile.core.domain.SettingsRepository
import com.bragastudio.mobile.core.domain.VideoSettings
import com.bragastudio.mobile.core.domain.VideoSources
import com.bragastudio.mobile.core.model.Lut
import com.bragastudio.mobile.core.repository.LutRepository
import com.bragastudio.mobile.corecapture.domain.AudioCaptureService
import com.bragastudio.mobile.corecapture.domain.CameraInfoModel
import com.bragastudio.mobile.corecapture.domain.CameraRepository
import com.bragastudio.mobile.coremedia.domain.AudioManagerService
import com.bragastudio.mobile.coremedia.domain.MediaGraph
import com.bragastudio.mobile.coremedia.domain.isTransitioning
import com.bragastudio.mobile.network.LinkTelemetry
import com.bragastudio.mobile.network.TallyState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PreviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaGraph: MediaGraph,
    private val audioManagerService: AudioManagerService,
    private val audioCaptureService: AudioCaptureService,
    private val settingsRepository: SettingsRepository,
    private val hardwareMonitorService: HardwareMonitorService,
    private val lutRepository: LutRepository,
    private val cameraRepository: CameraRepository,
    private val linkTelemetry: LinkTelemetry,
) : ViewModel() {

    private val _selectedLutName = MutableStateFlow("Nenhum (Desativado)")

    val allLuts: StateFlow<List<Lut>> = lutRepository.getAllLuts().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList(),
    )
    val activeLut: StateFlow<Lut?> = lutRepository.getActiveLut().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null,
    )

    // =========================================================================
    // 1. STATE EXPOSURE
    // =========================================================================
    // Lentes da FONTE ATIVA (muda ao trocar Camera/USB/Sony), não só as do Camera2.
    val availableLenses = mediaGraph.activeLenses
    val captureState = mediaGraph.captureState
    val usbStatus = mediaGraph.usbStatus
    val sensorOrientation: Int get() = mediaGraph.sensorOrientation
    val zoomFactor = mediaGraph.zoomFactor
    val panX = mediaGraph.panX
    val panY = mediaGraph.panY

    // Lanterna (torch) do Camera2 — transitória, não persiste. Os toggles de
    // estabilização/HDR vêm de videoSettings.stabilizationEnabled/hdrEnabled
    // diretamente (o PreviewScreen já coleta VideoSettings).
    val torchEnabled = mediaGraph.torchEnabled

    // Eventos de erro/aviso para a UI exibir num Snackbar: repassa os erros de
    // gravação/NDI/BSP do MediaGraph e acrescenta os avisos locais do Preview
    // (HDR sem suporte etc.). A gravação NÃO é mais interrompida ao sair do primeiro plano
    // (sessão de captura desacoplada da tela, ver MediaGraph).
    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    // Tally vindo do OBS via BDSM Link (OFF/PREVIEW/PROGRAM); o HUD desenha a
    // TallyBorder (vermelho PROGRAM/REC, verde PREVIEW).
    val tally: StateFlow<TallyState> = linkTelemetry.tally

    // Telemetria e comandos exclusivos da câmera Sony via Wi-Fi.
    val sonyTelemetry = mediaGraph.sonyTelemetry
    fun sonyTakePicture() = mediaGraph.sonyTakePicture()
    fun sonySetAperture(fNumber: String) = mediaGraph.sonySetAperture(fNumber)

    // Íris "AUTO" exige trocar o modo de exposição da câmera (não é setFNumber("AUTO")).
    fun sonySetIrisAuto() = mediaGraph.sonySetIrisAuto()

    // Telemetria real do sensor e faixas do hardware (Camera2). O HUD lê a metadata
    // por provedor, dentro da folha, para não recompor a raiz a cada resultado.
    val captureMetadata = mediaGraph.captureMetadata
    val manualLimits = mediaGraph.manualLimits

    // L2 (estado duplicado): ISO/obturador/WB/foco/lente abaixo são ESPELHOS locais
    // do que foi pedido ao CaptureDevice. O Camera2Device (singleton) guarda os
    // valores manuais reais, mas hoje NÃO expõe StateFlow nem getters, então o
    // ViewModel não tem de onde ler o estado atual ao ser recriado (ex.: botão
    // Home destrói o ViewModel): o HUD volta a mostrar AUTO/lente principal
    // enquanto o sensor segue em manual. Correção definitiva (fora deste escopo):
    // expor StateFlow no CaptureDevice/MediaGraph e só derivar aqui.
    private val _currentLens = MutableStateFlow<CameraInfoModel?>(null)
    val currentLens: StateFlow<CameraInfoModel?> = _currentLens.asStateFlow()

    // L2: ao recriar o ViewModel (ex.: botão Home) o estado manual é reconstruído a
    // partir da telemetria REAL do sensor (AE desligado = valores aplicados), em vez
    // de voltar sempre para AUTO enquanto a câmera segue em manual.
    private val initialExposure = manualExposureFrom(mediaGraph.captureMetadata.value)

    private val _currentIso = MutableStateFlow<Int?>(initialExposure.iso)
    val currentIso: StateFlow<Int?> = _currentIso.asStateFlow()

    private val _currentShutter = MutableStateFlow<Long?>(initialExposure.shutterNs)
    val currentShutter: StateFlow<Long?> = _currentShutter.asStateFlow()

    private val _currentWb = MutableStateFlow<Int?>(null)
    val currentWb: StateFlow<Int?> = _currentWb.asStateFlow()

    private val _currentFocus = MutableStateFlow<Float?>(null)
    val currentFocus: StateFlow<Float?> = _currentFocus.asStateFlow()

    val isRecording = mediaGraph.recordManager.isRecording

    // Ciclo do take: o botão REC fica desabilitado em Preparing/Stopping.
    val isRecTransitioning: StateFlow<Boolean> = mediaGraph.recState
        .map { it.isTransitioning }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Há take sendo finalizado (muxer/MediaStore) em segundo plano.
    val isFinalizing: StateFlow<Boolean> = mediaGraph.recordManager.pendingFinalizations
        .map { finalizingMessage(it) != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val recordingTimeMs = mediaGraph.recordManager.recordingTimeMs

    val ndiSettings: StateFlow<NdiSettings> = settingsRepository.ndiSettings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), NdiSettings(),
    )

    val videoScopes = mediaGraph.videoScopes

    // Só a visibilidade (muda raramente). O PreviewScreen raiz NÃO deve coletar
    // videoScopes inteiro: ele muda a ~10 Hz e recomporia a tela toda (M29).
    val isScopesVisible: StateFlow<Boolean> = videoScopes
        .map { it.isVisible }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val isFalseColorEnabled = mediaGraph.isFalseColorEnabled
    val isZebraEnabled = mediaGraph.isZebraEnabled
    val isLutEnabled = mediaGraph.isLutEnabled
    val isFocusPeakingEnabled = mediaGraph.isFocusPeakingEnabled

    val availableAudioDevices = audioManagerService.availableDevices
    val selectedAudioDevice = audioManagerService.selectedDevice

    // ✅ CORRIGIDO: Converte CharSequence para String
    val selectedAudioDeviceName: StateFlow<String> = selectedAudioDevice.map { device ->
        if (device == null) return@map "Nenhum"
        // Verifica se é um microfone interno do aparelho
        val isInternal = device.type == 15 || // TYPE_BUILTIN_MIC
            device.type == 1 ||  // TYPE_BUILTIN_EARPIECE
            device.productName?.toString()?.contains("Built-in", ignoreCase = true) == true

        if (isInternal) {
            "INTERNO"
        } else {
            device.productName?.toString() ?: "Desconhecido"
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "Nenhum",
    )
    val audioLevels = audioCaptureService.audioLevels

    // Pico por canal (linear) para o VU; o HUD converte para a escala da barra.
    val audioPeaks = audioCaptureService.audioPeaks
    val hardwareMetrics = hardwareMonitorService.metrics

    val videoSettings: StateFlow<VideoSettings> = settingsRepository.videoSettings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), VideoSettings(),
    )

    // isSonyActive deriva de videoSettings (mesma fonte que já pilota o resto do
    // HUD) em vez de mediaGraph.activeDeviceId para já refletir a seleção no
    // mesmo frame em que o usuário troca a FONTE, sem esperar o MediaGraph
    // reconectar. Precisa vir DEPOIS de videoSettings acima — variáveis de
    // instância em Kotlin são inicializadas em ordem de declaração, e usar
    // videoSettings antes dela existir causa erro de compilação.
    val isSonyActive: StateFlow<Boolean> = videoSettings
        .map { VideoSources.sonyWifiEnabled() && it.videoSource == VideoSources.SONY }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // HDR real 10-bit (Caminho A): o toggle só pode LIGAR quando a lente atual
    // suporta DynamicRangeProfile HLG10 E o codec está em HEVC (H.265) — em
    // qualquer outra combinação o app gravaria SDR 8-bit e a UI mentiria.
    // Calculado sob demanda: o antigo StateFlow (WhileSubscribed) nunca era
    // coletado, então `.value` ficava sempre false e ligar o HDR não fazia nada (M35).
    fun hdrSupportedNow(): Boolean = currentLens.value?.supportsHdr10 == true && videoSettings.value.codec == "H.265"

    // --- NOVOS ESTADOS PARA O HUD ---
    // Substitui o antigo isScopesVisible
    private val _isHistogramVisible = MutableStateFlow(false)
    val isHistogramVisible: StateFlow<Boolean> = _isHistogramVisible.asStateFlow()

    private val _currentAspectRatio = MutableStateFlow("OFF")
    val currentAspectRatio: StateFlow<String> = _currentAspectRatio.asStateFlow()

    private val _currentGrid = MutableStateFlow("OFF")
    val currentGrid: StateFlow<String> = _currentGrid.asStateFlow()

    // Exposto para permitir ajuste fino de zebra/peaking direto no monitor (HUD),
    // sem precisar navegar até a tela de Configurações.
    val monitorSettings = settingsRepository.monitorSettings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), com.bragastudio.mobile.core.domain.MonitorSettings(),
    )

    // O sink de áudio (REC antes de NDI) e o start/stop do microfone pertencem ao MediaGraph
    // (processo): sobrevivem a este ViewModel. Aqui só se observam níveis/picos para o VU.

    // Fonte de vídeo vista pelo coletor de lentes (detecta a troca de fonte).
    private var lastLensSource: String? = null

    // =========================================================================
    // 2. INITIALIZATION
    // =========================================================================
    init {
        viewModelScope.launch { mediaGraph.errorEvents.collect { _errorEvents.emit(it) } }
        // Eventos estruturados do take (fonte perdida, disco cheio, erro do encoder)
        // viram mensagens no mesmo Snackbar; "Stopped" é silencioso.
        viewModelScope.launch {
            mediaGraph.recordingEvents.collect { event ->
                recordingEventMessage(event)?.let { _errorEvents.emit(it) }
            }
        }
        // FPS/resolução alterados nas settings reconfiguram a captura da câmera (M9).
        // drop(1): a primeira emissão é o valor persistido, já aplicado ao abrir a câmera.
        viewModelScope.launch {
            settingsRepository.videoSettings
                .map { it.resolution to it.fps }
                .distinctUntilChanged()
                .drop(1)
                .collect { (resolution, fps) -> mediaGraph.configureCapture(resolution, fps) }
        }
        setupInitialLens()

        viewModelScope.launch {
            settingsRepository.monitorSettings.collect { settings ->
                _selectedLutName.value = settings.selectedLut
            }
        }
    }

    // Escolhe a lente inicial e reage à troca de FONTE (M36): ao mudar Camera/USB/Sony
    // a lente anterior não existe mais na fonte nova, então é descartada e reavaliada.
    private fun setupInitialLens() {
        viewModelScope.launch {
            combine(
                availableLenses,
                videoSettings.map { it.videoSource }.distinctUntilChanged(),
            ) { lenses, source -> lenses to source }.collect { (lenses, source) ->
                if (source != lastLensSource) {
                    lastLensSource = source
                    _currentLens.value = null
                }
                val current = _currentLens.value
                if ((current == null || lenses.none { it.id == current.id }) && lenses.isNotEmpty()) {
                    val main = cameraRepository.getMainCamera()?.takeIf { m -> lenses.any { it.id == m.id } }
                    val target = main ?: lenses.first()
                    _currentLens.value = target
                    mediaGraph.reportActiveLens(getLensLabel(target))
                } else if (lenses.isEmpty() && current != null) {
                    _currentLens.value = null
                    mediaGraph.reportActiveLens(null)
                }
            }
        }
    }

    // =========================================================================
    // 3. ACTIONS
    // =========================================================================

    /** Anexa (ou reanexa) a surface; com a sessão viva (REC/NDI/BSP em segundo plano) não reinicia a câmera. */
    fun attachSurface(surface: Surface) = mediaGraph.attachMonitorSurfaceAsync(surface)

    /**
     * Solta a surface. Roda no escopo do grafo (não no do ViewModel): o detach precisa acontecer
     * mesmo que o ViewModel seja limpo junto. Com REC/NDI/BSP ativos só a surface é solta.
     */
    fun detachSurface() = mediaGraph.detachPreviewSurfaceAsync()

    // Recuperação manual após CaptureState.ERROR (botão "Tentar novamente" no
    // overlay de erro): reabre o pipeline com a surface de preview atual.
    fun retryCamera() = viewModelScope.launch { mediaGraph.restartPreview() }

    fun toggleRecording() {
        viewModelScope.launch {
            if (isRecording.value) {
                mediaGraph.stopRecording()
            } else {
                val settings = videoSettings.value
                mediaGraph.startRecording(settings.recordingDirectoryUri ?: "", settings)
            }
        }
    }

    fun selectLens(lensId: String) {
        viewModelScope.launch {
            mediaGraph.switchLens(lensId)
            val lens = availableLenses.value.find { it.id == lensId }
            _currentLens.value = lens
            // Atualiza o nome da lente no dashboard do BDSM Link.
            mediaGraph.reportActiveLens(lens?.let { getLensLabel(it) })
        }
    }

    fun onPermissionsGranted() {
        viewModelScope.launch {
            cameraRepository.refresh()
        }
    }

    fun updateZoomAndPan(zoom: Float, px: Float, py: Float) {
        mediaGraph.updateZoomAndPan(zoom, px, py)
    }

    // Os valores manuais são limitados à faixa REAL da lente ativa (SENSOR_INFO_*,
    // LENS_INFO_MINIMUM_FOCUS_DISTANCE) antes de espelhar e enviar ao sensor.
    fun setIso(iso: Int?) {
        val v = coerceIsoToLimits(iso, mediaGraph.manualLimits.value)
        _currentIso.value = v
        mediaGraph.setManualIso(v)
    }
    fun setShutter(shutter: Long?) {
        val v = coerceShutterToLimits(shutter, mediaGraph.manualLimits.value)
        _currentShutter.value = v
        mediaGraph.setManualShutter(v)
    }
    fun setWb(wb: Int?) {
        _currentWb.value = wb
        mediaGraph.setManualWhiteBalance(wb)
    }
    fun setFocus(focus: Float?) {
        val v = coerceFocusToLimits(focus, mediaGraph.manualLimits.value)
        _currentFocus.value = v
        mediaGraph.setManualFocus(v)
    }

    // --- AÇÕES PARA O HUD ---
    fun toggleScopesVisibility() = mediaGraph.toggleScopesVisibility() // Esta função já existia, mas agora o HUD usa o histograma como toggle
    fun toggleHistogram() {
        _isHistogramVisible.value = !_isHistogramVisible.value
    }
    fun toggleAspectRatio() {
        val ratios = listOf("OFF", "2.35:1", "4:3", "1:1")
        val currentIndex = ratios.indexOf(_currentAspectRatio.value)
        val nextIndex = if (currentIndex == -1) 1 else (currentIndex + 1) % ratios.size
        _currentAspectRatio.value = ratios[nextIndex]
    }
    fun toggleGrid() {
        val grids = listOf("OFF", "3x3", "4x4", "Centro")
        val currentIndex = grids.indexOf(_currentGrid.value)
        val nextIndex = if (currentIndex == -1) 1 else (currentIndex + 1) % grids.size
        _currentGrid.value = grids[nextIndex]
    }

    fun setAspectRatio(ratio: String) {
        _currentAspectRatio.value = ratio
    }

    fun setGrid(grid: String) {
        _currentGrid.value = grid
    }

    fun setActiveLut(lutId: String?) {
        viewModelScope.launch {
            lutRepository.setActiveLut(lutId)
        }
    }
    fun cycleAudioDevice() {
        viewModelScope.launch {
            val devices = availableAudioDevices.value
            val currentDevice = selectedAudioDevice.value
            if (devices.isNotEmpty()) {
                val currentIndex = devices.indexOf(currentDevice)
                val nextIndex = (currentIndex + 1) % devices.size
                selectAudioDevice(devices[nextIndex])
            }
        }
    }

    fun cycleScopeType() = mediaGraph.cycleScopeType()
    fun toggleFalseColor() = mediaGraph.toggleFalseColor()
    fun toggleZebra() = mediaGraph.toggleZebra()
    fun toggleFocusPeaking() = mediaGraph.toggleFocusPeaking()

    // Ajuste fino direto no HUD do monitor (slider contínuo de 0-100).
    // O motor nativo (GlesEngine) já aceita esse valor via nativeSetSettings;
    // só persistimos a preferência e o MediaGraph re-envia no próximo frame.
    fun setZebraThreshold(threshold: Int) {
        viewModelScope.launch {
            settingsRepository.setZebraThreshold(threshold.coerceIn(0, 100))
        }
    }

    // Sensibilidade do focus peaking também é contínua no shader (0f-1f), mas o
    // repositório hoje só persiste um enum ("Low"/"Medium"/"High") usado em outras
    // telas. Mapeamos o valor do slider para o enum mais próximo para manter a
    // persistência compatível com a tela de Configurações.
    fun setFocusPeakingSensitivity(sliderValue: Float) {
        val bucket = when {
            sliderValue < 0.34f -> "Low"
            sliderValue < 0.67f -> "Medium"
            else -> "High"
        }
        viewModelScope.launch {
            settingsRepository.setFocusPeakingSensitivity(bucket)
        }
    }

    // Cor do Focus Peaking — o campo e a persistência (MonitorSettings.focusPeakingColor
    // / DataStore) já existiam no repositório, só não estavam expostos em nenhum
    // controle do HUD. Agora acessível segurando o botão de Peaking na leftbar.
    fun setFocusPeakingColor(color: String) {
        viewModelScope.launch {
            settingsRepository.setFocusPeakingColor(color)
        }
    }

    val resolutions = listOf("1080p", "1440p", "4K")
    val fpsOptions = listOf(24, 30, 60)
    val bitrates = listOf(25, 50, 100)
    val codecs = listOf("H.264", "H.265")
    val sources = listOf("Camera", "USB")

    fun cycleResolution() = viewModelScope.launch {
        val current = videoSettings.value.resolution
        val next = resolutions[(resolutions.indexOf(current) + 1).coerceAtLeast(0) % resolutions.size]
        settingsRepository.setVideoResolution(next)
    }
    fun setResolution(res: String) = viewModelScope.launch { settingsRepository.setVideoResolution(res) }

    fun cycleFps() = viewModelScope.launch {
        val current = videoSettings.value.fps
        val next = fpsOptions[(fpsOptions.indexOf(current) + 1).coerceAtLeast(0) % fpsOptions.size]
        settingsRepository.setVideoFps(next)
    }
    fun setFps(fps: Int) = viewModelScope.launch { settingsRepository.setVideoFps(fps) }

    fun cycleBitrate() = viewModelScope.launch {
        val current = videoSettings.value.bitrateMbps
        val next = bitrates[(bitrates.indexOf(current) + 1).coerceAtLeast(0) % bitrates.size]
        settingsRepository.setVideoBitrate(next)
    }
    fun setBitrate(bitrate: Int) = viewModelScope.launch { settingsRepository.setVideoBitrate(bitrate) }

    fun cycleCodec() = viewModelScope.launch {
        val current = videoSettings.value.codec
        val next = codecs[(codecs.indexOf(current) + 1).coerceAtLeast(0) % codecs.size]
        settingsRepository.setVideoCodec(next)
        // HDR real 10-bit exige HEVC: ao sair de H.265 desliga o HDR para a UI
        // não exibir um estado que o MediaGraph ignoraria (gravação SDR 8-bit).
        if (next != "H.265") settingsRepository.setVideoHdrEnabled(false)
    }
    fun setCodec(codec: String) = viewModelScope.launch {
        settingsRepository.setVideoCodec(codec)
        if (codec != "H.265") settingsRepository.setVideoHdrEnabled(false)
    }

    fun toggleCameraSource() {
        viewModelScope.launch {
            val current = videoSettings.value.videoSource
            // Ciclo só entre as fontes disponíveis (Camera -> USB -> [SONY] -> Camera).
            settingsRepository.setVideoSource(VideoSources.next(current))
        }
    }
    fun setCameraSource(source: String) = viewModelScope.launch { settingsRepository.setVideoSource(source) }

    // --- Controles avançados (Camera2) ---
    fun toggleTorch() = mediaGraph.setTorchEnabled(!mediaGraph.torchEnabled.value)

    fun toggleVideoStabilization() = viewModelScope.launch {
        settingsRepository.setVideoStabilizationEnabled(!videoSettings.value.stabilizationEnabled)
    }

    fun toggleHdr() = viewModelScope.launch {
        val newValue = !videoSettings.value.hdrEnabled
        // Só permite LIGAR com suporte real (HLG10 na lente + codec HEVC);
        // desligar tem sempre permissão. Se a HAL recusar a sessão no REC, o
        // MediaGraph cai no fallback SDR — aqui evitamos apenas abrir o toggle
        // sem suporte (evita também acionar o scene-mode HDR 8-bit à toa).
        if (newValue && !hdrSupportedNow()) {
            _errorEvents.emit("HDR indisponível: requer H.265 e uma lente compatível com HLG10")
            return@launch
        }
        settingsRepository.setVideoHdrEnabled(newValue)
    }

    fun toggleLut() = mediaGraph.toggleLut()

    fun selectAudioDevice(device: android.media.AudioDeviceInfo) {
        audioManagerService.selectDevice(device)
    }

    fun takeSnapshot() {
        viewModelScope.launch {
            val bmp = mediaGraph.takeSnapshot()
            if (bmp != null) {
                saveSnapshotToGallery(bmp)
                Toast.makeText(context, "Snapshot salvo!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Erro ao capturar snapshot", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun toggleNdi() {
        viewModelScope.launch {
            val currentValue = ndiSettings.value.isEnabled
            settingsRepository.setNdiEnabled(!currentValue)
        }
    }

    fun updateRotationDegrees(degrees: Float) {
        mediaGraph.updateRotationDegrees(degrees)
    }

    private fun saveSnapshotToGallery(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val filename = "BDSM_Snapshot_${System.currentTimeMillis()}.jpg"
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BDSM")
            }
            val uri: Uri? = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                }
            }
        }
    }
}
