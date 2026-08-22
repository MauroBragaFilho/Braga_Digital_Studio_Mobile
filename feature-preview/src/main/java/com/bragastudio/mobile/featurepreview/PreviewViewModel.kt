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
import com.bragastudio.mobile.core.domain.SettingsRepository
import com.bragastudio.mobile.core.domain.VideoSettings
import com.bragastudio.mobile.corecapture.domain.AudioCaptureService
import com.bragastudio.mobile.corecapture.domain.CameraInfoModel
import com.bragastudio.mobile.corecapture.domain.CameraRepository
import com.bragastudio.mobile.coremedia.domain.AudioManagerService
import com.bragastudio.mobile.coremedia.domain.MediaGraph
import com.bragastudio.mobile.core.domain.NdiSettings
import com.bragastudio.mobile.core.repository.LutRepository
import com.bragastudio.mobile.core.model.Lut
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PreviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaGraph: MediaGraph,
    private val audioManagerService: AudioManagerService,
    private val audioCaptureService: AudioCaptureService,
    private val settingsRepository: SettingsRepository,
    private val hardwareMonitorService: HardwareMonitorService,
    private val lutRepository: LutRepository,
    private val cameraRepository: CameraRepository
) : ViewModel() {

    private val _selectedLutName = MutableStateFlow("Nenhum (Desativado)")

    val allLuts: StateFlow<List<Lut>> = lutRepository.getAllLuts().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )
    val activeLut: StateFlow<Lut?> = lutRepository.getActiveLut().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), null
    )

    // =========================================================================
    // 1. STATE EXPOSURE
    // =========================================================================
    val availableLenses = mediaGraph.captureDevice.availableLenses
    val captureState = mediaGraph.captureState
    val sensorOrientation: Int get() = mediaGraph.captureDevice.sensorOrientation
    val zoomFactor = mediaGraph.zoomFactor
    val panX = mediaGraph.panX
    val panY = mediaGraph.panY

    // Eventos de erro de gravação/NDI para a UI exibir num Snackbar/Toast.
    val errorEvents = mediaGraph.errorEvents

    private val _currentLens = MutableStateFlow<CameraInfoModel?>(null)
    val currentLens: StateFlow<CameraInfoModel?> = _currentLens.asStateFlow()

    private val _currentIso = MutableStateFlow<Int?>(null)
    val currentIso: StateFlow<Int?> = _currentIso.asStateFlow()

    private val _currentShutter = MutableStateFlow<Long?>(null)
    val currentShutter: StateFlow<Long?> = _currentShutter.asStateFlow()

    private val _currentWb = MutableStateFlow<Int?>(null)
    val currentWb: StateFlow<Int?> = _currentWb.asStateFlow()

    private val _currentFocus = MutableStateFlow<Float?>(null)
    val currentFocus: StateFlow<Float?> = _currentFocus.asStateFlow()

    val isRecording = mediaGraph.recordManager.isRecording
    val recordingTimeMs = mediaGraph.recordManager.recordingTimeMs

    val ndiSettings: StateFlow<NdiSettings> = settingsRepository.ndiSettings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), NdiSettings()
    )
    
    val videoScopes = mediaGraph.videoScopes
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
        initialValue = "Nenhum"
    )
    val audioLevels = audioCaptureService.audioLevels
    val hardwareMetrics = hardwareMonitorService.metrics

    val videoSettings: StateFlow<VideoSettings> = settingsRepository.videoSettings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), VideoSettings()
    )

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
        viewModelScope, SharingStarted.WhileSubscribed(5000), com.bragastudio.mobile.core.domain.MonitorSettings()
    )

    // =========================================================================
    // 2. INITIALIZATION
    // =========================================================================
    init {
        setupAudioRouting()
        setupInitialLens()
        
        viewModelScope.launch {
            settingsRepository.monitorSettings.collect { settings ->
                _selectedLutName.value = settings.selectedLut
            }
        }
    }

    private fun setupAudioRouting() {
        audioCaptureService.onAudioBufferAvailable = { pcmData, numSamples, numChannels, sampleRate ->
            mediaGraph.ndiManager.feedAudio(pcmData, numSamples, numChannels, sampleRate)
            mediaGraph.recordManager.feedAudio(pcmData, numSamples, numChannels, sampleRate)
        }
        viewModelScope.launch {
            selectedAudioDevice.collect { device ->
                audioCaptureService.startCapture(device)
            }
        }
    }

    private fun setupInitialLens() {
        viewModelScope.launch {
            availableLenses.collect { lenses ->
                if (_currentLens.value == null && lenses.isNotEmpty()) {
                    val targetLens = cameraRepository.getMainCamera() ?: lenses.firstOrNull()
                    if (targetLens != null) {
                        _currentLens.value = targetLens
                    }
                }
            }
        }
    }

    // =========================================================================
    // 3. ACTIONS
    // =========================================================================
    fun attachSurface(surface: Surface) = viewModelScope.launch { mediaGraph.attachPreviewSurface(surface) }
    fun detachSurface() = viewModelScope.launch { mediaGraph.detachPreviewSurface() }

    fun toggleRecording() {
        viewModelScope.launch {
            if (isRecording.value) mediaGraph.stopRecording()
            else {
                val settings = videoSettings.value
                mediaGraph.startRecording(settings.recordingDirectoryUri ?: "", settings)
            }
        }
    }

    fun selectLens(lensId: String) {
        viewModelScope.launch {
            mediaGraph.captureDevice.switchCamera(lensId)
            _currentLens.value = availableLenses.value.find { it.id == lensId }
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

    fun setIso(iso: Int?) { _currentIso.value = iso; mediaGraph.captureDevice.setIso(iso) }
    fun setShutter(shutter: Long?) { _currentShutter.value = shutter; mediaGraph.captureDevice.setShutterSpeed(shutter) }
    fun setWb(wb: Int?) { _currentWb.value = wb; mediaGraph.captureDevice.setWhiteBalance(wb) }
    fun setFocus(focus: Float?) { _currentFocus.value = focus; mediaGraph.captureDevice.setFocusDistance(focus) }

    // --- AÇÕES PARA O HUD ---
    fun toggleScopesVisibility() = mediaGraph.toggleScopesVisibility() // Esta função já existia, mas agora o HUD usa o histograma como toggle
    fun toggleHistogram() { _isHistogramVisible.value = !_isHistogramVisible.value }
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
    }
    fun setCodec(codec: String) = viewModelScope.launch { settingsRepository.setVideoCodec(codec) }

    fun toggleCameraSource() {
        viewModelScope.launch {
            val current = videoSettings.value.videoSource
            val next = if (current == "Camera") "USB" else "Camera"
            settingsRepository.setVideoSource(next)
        }
    }
    fun setCameraSource(source: String) = viewModelScope.launch { settingsRepository.setVideoSource(source) }
    
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
        mediaGraph.nativeRenderer.updateRotationDegrees(degrees)
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
