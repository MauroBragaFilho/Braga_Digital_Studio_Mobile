package com.bragastudio.mobile.coremedia.domain

import android.annotation.TargetApi
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.bragastudio.mobile.corecapture.domain.CaptureDevice
import com.bragastudio.mobile.core.domain.VideoSettings
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Singleton
@TargetApi(Build.VERSION_CODES.P)
class MediaGraph @Inject constructor(
    private val camera2Device: com.bragastudio.mobile.corecapture.device.Camera2Device,
    private val uvcCaptureDevice: com.bragastudio.mobile.corecapture.device.UvcCaptureDevice,
    val recordManager: RecordManager,
    val ndiManager: NdiManager,
    val nativeRenderer: com.bragastudio.mobile.coremedia.graphics.NativeRenderer,
    val settingsRepository: com.bragastudio.mobile.core.domain.SettingsRepository,
    val lutRepository: com.bragastudio.mobile.core.repository.LutRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext val context: android.content.Context
) {
    var captureDevice: CaptureDevice = camera2Device
    private var previewSurface: Surface? = null
    private var isCameraStarted = false
    
    private var ndiImageReader: ImageReader? = null
    private var ndiHandlerThread: HandlerThread? = null
    private var ndiHandler: Handler? = null
    
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isScopeRunning = false
    
    // ✅ Variáveis de estado locais para acessar configurações de forma síncrona
    private var currentVideoSettings = com.bragastudio.mobile.core.domain.VideoSettings()
    private var currentMonitorSettings = com.bragastudio.mobile.core.domain.MonitorSettings()
    
    private val _videoScopes = MutableStateFlow(com.bragastudio.mobile.core.domain.VideoScopes())
    val videoScopes: StateFlow<com.bragastudio.mobile.core.domain.VideoScopes> = _videoScopes.asStateFlow()

    private val _captureState = MutableStateFlow(com.bragastudio.mobile.corecapture.domain.CaptureState.IDLE)
    val captureState: StateFlow<com.bragastudio.mobile.corecapture.domain.CaptureState> = _captureState.asStateFlow()
    
    private var currentActiveLut: com.bragastudio.mobile.core.model.Lut? = null
    
    private var stateObserverJob: kotlinx.coroutines.Job? = null

    init {
        Log.d("MediaGraph", "MediaGraph inicializado (Hub OpenGL)")
        
        observeCurrentDeviceState()
        
        // Coleta Video Settings
        scope.launch {
            settingsRepository.videoSettings.collect { settings ->
                val previousSource = currentVideoSettings.videoSource
                currentVideoSettings = settings
                
                if (previousSource != settings.videoSource) {
                    captureDevice.stop()
                    captureDevice = if (settings.videoSource == "USB") {
                        uvcCaptureDevice
                    } else {
                        camera2Device
                    }
                    observeCurrentDeviceState()
                    if (previewSurface != null) {
                        restartSession()
                    }
                }
            }
        }

        // ✅ Coleta Monitor Settings para uso síncrono
        scope.launch {
            settingsRepository.monitorSettings.collect { settings ->
                currentMonitorSettings = settings
            }
        }

        // ✅ Coleta LUT ativa e aplica no motor nativo automaticamente
        scope.launch {
            lutRepository.getActiveLut().collect { activeLut ->
                currentActiveLut = activeLut
                if (activeLut != null) {
                    val lutData = if (activeLut.isBuiltIn) {
                        LutParser.parseFromAssets(context, activeLut.filePath)
                    } else {
                        LutParser.parseFromFile(java.io.File(activeLut.filePath))
                    }
                    if (lutData != null) {
                        applyLut(lutData)
                    } else {
                        disableLut()
                    }
                } else {
                    disableLut()
                }
            }
        }
    }

    private fun observeCurrentDeviceState() {
        stateObserverJob?.cancel()
        stateObserverJob = scope.launch {
            captureDevice.state.collect { st ->
                _captureState.value = st
            }
        }
    }

    suspend fun attachPreviewSurface(surface: Surface) {
        this.previewSurface = surface
        restartSession()
        startScopePolling()
        
        scope.launch {
            val activeLut = currentActiveLut
            if (activeLut != null) {
                val lutData = if (activeLut.isBuiltIn) {
                    LutParser.parseFromAssets(context, activeLut.filePath)
                } else {
                    LutParser.parseFromFile(java.io.File(activeLut.filePath))
                }
                if (lutData != null) {
                    val byteArray = LutParser.getLutByteArray(lutData)
                    nativeRenderer.setLutData(byteArray, lutData.size)
                }
            }
            nativeRenderer.updateSettings(
                falseColor = _isFalseColorEnabled.value,
                zebra = _isZebraEnabled.value,
                gridType = 0,
                aspectRatioMarker = 16f / 9f,
                focusPeaking = _isFocusPeakingEnabled.value,
                zoomFactor = _zoomFactor.value,
                panX = _panX.value,
                panY = _panY.value,
                lutEnabled = _isLutEnabled.value,
                scopeType = _videoScopes.value.activeType,
                zebraThreshold = currentMonitorSettings.zebraThreshold,
                focusPeakingColor = currentMonitorSettings.focusPeakingColor,
                focusPeakingSensitivity = currentMonitorSettings.focusPeakingSensitivity
            )
        }
    }

    suspend fun detachPreviewSurface() {
        this.previewSurface = null
        nativeRenderer.clearPreviewSurface()
        delay(100) // Delay to let C++ thread finish using the surface
        isCameraStarted = false
        stopScopePolling()
        captureDevice.stop()
        if (recordManager.isRecording.value) {
            stopRecording()
        }
        if (ndiManager.isNdiActive.value) {
            stopNdi()
        }
    }

    suspend fun startRecording(directoryUri: String, videoSettings: VideoSettings) {
        val surface = recordManager.prepareRecording(directoryUri, videoSettings)
        if (surface != null) {
            nativeRenderer.setRecordSurface(surface)
            delay(200)
            recordManager.startRecording()
        }
    }

    suspend fun stopRecording() {
        nativeRenderer.setRecordSurface(null)
        delay(100) // Small delay to prevent crashing if the C++ thread is drawing
        recordManager.stopRecording()
    }

    suspend fun startNdi(cameraName: String) {
        if (ndiManager.startNdi(cameraName)) {
            ndiHandlerThread = HandlerThread("BDSM-NDI-Thread").apply { start() }
            ndiHandler = Handler(ndiHandlerThread!!.looper)
            
            ndiImageReader = ImageReader.newInstance(1920, 1080, PixelFormat.RGBA_8888, 2, android.hardware.HardwareBuffer.USAGE_CPU_READ_OFTEN or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            ndiImageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    ndiManager.feedImage(image)
                    image.close()
                }
            }, ndiHandler)
            
            nativeRenderer.setNdiSurface(ndiImageReader?.surface)
        }
    }

    suspend fun stopNdi() {
        ndiManager.stopNdi()
        nativeRenderer.setNdiSurface(null)
        
        ndiImageReader?.close()
        ndiImageReader = null
        
        ndiHandlerThread?.quitSafely()
        try {
            ndiHandlerThread?.join()
        } catch (e: Exception) {}
        ndiHandlerThread = null
    }

    private suspend fun restartSession() {
        if (previewSurface == null) return
        
        val width = when(currentVideoSettings.resolution) {
            "4K" -> 3840
            "1440p" -> 2560
            else -> 1920
        }
        val height = when(currentVideoSettings.resolution) {
            "4K" -> 2160
            "1440p" -> 1440
            else -> 1080
        }
        
        val camSurface = nativeRenderer.prepareRenderer(previewSurface, width, height)
        
        if (camSurface != null) {
            captureDevice.start(camSurface)
        } else {
            captureDevice.start(previewSurface!!)
        }
    }
    
    private fun startScopePolling() {
        if (isScopeRunning) return
        isScopeRunning = true
        scope.launch {
            while (isScopeRunning) {
                val currentScopes = _videoScopes.value
                if (currentScopes.isVisible) {
                    val newScopes = when (currentScopes.activeType) {
                        1 -> currentScopes.copy(histogramR = nativeRenderer.getHistogramR(), histogramG = nativeRenderer.getHistogramG(), histogramB = nativeRenderer.getHistogramB())
                        2 -> currentScopes.copy(waveform = nativeRenderer.getWaveform())
                        3 -> currentScopes.copy(vectorscope = nativeRenderer.getVectorscope())
                        else -> currentScopes
                    }
                    _videoScopes.value = newScopes
                }
                delay(100) // Poll at 10fps
            }
        }
    }
    
    private fun stopScopePolling() {
        isScopeRunning = false
    }

    fun toggleScopesVisibility() {
        val newVisibility = !_videoScopes.value.isVisible
        _videoScopes.value = _videoScopes.value.copy(isVisible = newVisibility)
        val activeType = if (newVisibility) _videoScopes.value.activeType else 0
        nativeRenderer.updateScopeType(activeType)
    }

    fun cycleScopeType() {
        val current = _videoScopes.value.activeType
        val next = if (current >= 3) 1 else current + 1
        _videoScopes.value = _videoScopes.value.copy(activeType = next)
        nativeRenderer.updateScopeType(next)
    }

    private val _isFalseColorEnabled = MutableStateFlow(false)
    val isFalseColorEnabled: StateFlow<Boolean> = _isFalseColorEnabled.asStateFlow()

    private val _isZebraEnabled = MutableStateFlow(false)
    val isZebraEnabled: StateFlow<Boolean> = _isZebraEnabled.asStateFlow()

    private val _isLutEnabled = MutableStateFlow(false)
    val isLutEnabled: StateFlow<Boolean> = _isLutEnabled.asStateFlow()
    private var isLutLoaded = false

    fun toggleLut() {
        if (isLutLoaded) {
            _isLutEnabled.value = !_isLutEnabled.value
            nativeRenderer.updateLutEnabled(_isLutEnabled.value)
        }
    }

    // ✅ FOCUS PEAKING
    private val _isFocusPeakingEnabled = MutableStateFlow(false)
    val isFocusPeakingEnabled: StateFlow<Boolean> = _isFocusPeakingEnabled.asStateFlow()

    fun toggleFocusPeaking() {
        _isFocusPeakingEnabled.value = !_isFocusPeakingEnabled.value
        
        Log.d("MediaGraph", "🎯 Focus Peaking: ${_isFocusPeakingEnabled.value} | Cor: ${currentMonitorSettings.focusPeakingColor} | Sens: ${currentMonitorSettings.focusPeakingSensitivity}")

        // Envia TODOS os estados atuais para o NativeRenderer usando as variáveis locais sincronizadas
        nativeRenderer.updateSettings(
            falseColor = _isFalseColorEnabled.value,
            zebra = _isZebraEnabled.value,
            gridType = 0,
            aspectRatioMarker = 16f / 9f,
            focusPeaking = _isFocusPeakingEnabled.value,
            zoomFactor = _zoomFactor.value,
            panX = _panX.value,
            panY = _panY.value,
            lutEnabled = _isLutEnabled.value,
            scopeType = _videoScopes.value.activeType,
            zebraThreshold = currentMonitorSettings.zebraThreshold,
            focusPeakingColor = currentMonitorSettings.focusPeakingColor,
            focusPeakingSensitivity = currentMonitorSettings.focusPeakingSensitivity
        )
    }

    private val _zoomFactor = MutableStateFlow(1.0f)
    val zoomFactor: StateFlow<Float> = _zoomFactor.asStateFlow()
    
    private val _panX = MutableStateFlow(0.0f)
    val panX: StateFlow<Float> = _panX.asStateFlow()
    
    private val _panY = MutableStateFlow(0.0f)
    val panY: StateFlow<Float> = _panY.asStateFlow()

    fun toggleFalseColor() {
        _isFalseColorEnabled.value = !_isFalseColorEnabled.value
        nativeRenderer.updateFalseColor(_isFalseColorEnabled.value)
    }

    fun toggleZebra() {
        _isZebraEnabled.value = !_isZebraEnabled.value
        nativeRenderer.updateZebra(_isZebraEnabled.value)
    }

        fun applyLut(lutData: LutParser.LutData) {
        android.util.Log.d("LUT_TRACE", "5. MediaGraph.applyLut chamado. Size: ${lutData.size}")
        scope.launch {
            val byteArray = LutParser.getLutByteArray(lutData)
            nativeRenderer.setLutData(byteArray, lutData.size)
            isLutLoaded = true
            _isLutEnabled.value = true
            nativeRenderer.updateLutEnabled(true)
            android.util.Log.d("LUT_TRACE", "6. NativeRenderer atualizado: LUT Enabled = true")
        }
    }

    fun disableLut() {
        isLutLoaded = false
        _isLutEnabled.value = false
        nativeRenderer.updateLutEnabled(false)
    }

    fun updateZoomAndPan(zoom: Float, px: Float, py: Float) {
        _zoomFactor.value = zoom.coerceIn(1.0f, 5.0f)
        _panX.value = px
        _panY.value = py
        nativeRenderer.updateZoomAndPan(_zoomFactor.value, _panX.value, _panY.value)
    }

    fun updateShaderSettings(falseColor: Boolean, zebra: Boolean, gridType: Int, aspectRatioMarker: Float, focusPeaking: Boolean, zoomFactor: Float, panX: Float, panY: Float, lutEnabled: Boolean, scopeType: Int, zebraThreshold: Int, focusPeakingColor: String, focusPeakingSensitivity: String) {
        _videoScopes.value = _videoScopes.value.copy(activeType = scopeType)
        nativeRenderer.updateSettings(falseColor, zebra, gridType, aspectRatioMarker, focusPeaking, zoomFactor, panX, panY, lutEnabled, scopeType, zebraThreshold, focusPeakingColor, focusPeakingSensitivity)
    }

    suspend fun takeSnapshot(): android.graphics.Bitmap? {
        nativeRenderer.requestSnapshot()
        var attempts = 0
        while (attempts < 50) {
            val bmp = nativeRenderer.getSnapshot()
            if (bmp != null) return bmp
            kotlinx.coroutines.delay(16)
            attempts++
        }
        return null
    }
}
