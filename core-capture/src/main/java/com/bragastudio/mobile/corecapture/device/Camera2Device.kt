package com.bragastudio.mobile.corecapture.device

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.bragastudio.mobile.corecapture.domain.CaptureDevice
import com.bragastudio.mobile.corecapture.domain.CaptureState
import com.bragastudio.mobile.corecapture.domain.LensInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Camera2Device @Inject constructor(
    @ApplicationContext private val context: Context
) : CaptureDevice {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val _state = MutableStateFlow(CaptureState.IDLE)
    
    private val _availableLenses = MutableStateFlow<List<LensInfo>>(emptyList())
    override val availableLenses: StateFlow<List<LensInfo>> = _availableLenses.asStateFlow()

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var currentSurfaces: List<Surface> = emptyList()

    private var currentCameraId: String? = null

    // Manual States
    private var manualIso: Int? = null
    private var manualShutter: Long? = null
    private var manualWb: Int? = null
    private var manualFocus: Float? = null

    override val deviceId: String = "Camera2"
    override val deviceName: String = "Primary Camera"
    override val state: StateFlow<CaptureState> = _state
    
    private var _sensorOrientation = 0
    override val sensorOrientation: Int
        get() = _sensorOrientation

    init {
        scanLenses()
    }

    // ✅ MELHORIA 1: Ordenação robusta por distância focal
            private fun scanLenses() {
        try {
            val backCameras = mutableListOf<Pair<String, Float>>()
            
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {
                    val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    val focal = focalLengths?.firstOrNull() ?: 0f
                    
                    // Filtra sensores de profundidade/ToF que às vezes aparecem como traseiras mas têm focal 0
                    if (focal > 0f) {
                        backCameras.add(Pair(id, focal))
                    }
                }
            }
            
            // Ordena da menor distância focal (Ultrawide) para a maior (Telephoto)
            backCameras.sortBy { it.second }
            
            // 🔍 LOG DE DEBUG: Veja isso no terminal do VS Code ao iniciar o app
            android.util.Log.d("BSM_Camera", "Total de câmeras traseiras válidas: ${backCameras.size}")
            backCameras.forEachIndexed { index, pair ->
                android.util.Log.d("BSM_Camera", "Câmera $index: ID=${pair.first}, Focal Length=${pair.second}mm")
            }

            val lenses = mutableListOf<LensInfo>()
            
            backCameras.forEachIndexed { index, cameraPair ->
                val id = cameraPair.first
                
                // Lógica adaptativa para 1, 2, 3 ou mais câmeras
                val name = when (backCameras.size) {
                    1 -> "0.5x Ultrawide"
                    2 -> if (index == 0) "0.5x Ultrawide" else ""
                    3 -> when (index) {
                        0 -> "0.5x Ultrawide"
                        1 -> "1x Wide"
                        2 -> "3x Telephoto"
                        else -> "1x Wide"
                    }
                    else -> { // 4 ou mais câmeras (ex: Samsung S22/S23 Ultra)
                        when (index) {
                            0 -> "0.5x Ultrawide"
                            1 -> "1x Wide"
                            backCameras.size - 1 -> "3x Telephoto" // A de maior zoom
                            else -> "2x Telephoto" // Câmeras intermediárias
                        }
                    }
                }
                
                val isPrimary = name.contains("1x") || index == 0
                
                if (isPrimary && currentCameraId == null) {
                    currentCameraId = id
                    _sensorOrientation = cameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                }
                
                lenses.add(LensInfo(id = id, name = name, isPrimary = isPrimary))
            }
            
            _availableLenses.value = lenses
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startCameraThread() {
        cameraThread = HandlerThread("BSM-CameraThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun stopCameraThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join()
            cameraThread = null
            cameraHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun start(vararg surfaces: Surface) {
        if (surfaces.isEmpty()) return
        currentSurfaces = surfaces.toList()
        
        _state.value = CaptureState.INITIALIZING
        startCameraThread()

        val idToOpen = currentCameraId ?: cameraManager.cameraIdList.firstOrNull { 
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: run {
            _state.value = CaptureState.ERROR
            return
        }

        openCamera(idToOpen)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(id: String) {
        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startPreviewSession(camera, currentSurfaces)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    _state.value = CaptureState.IDLE
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    _state.value = CaptureState.ERROR
                }
            }, cameraHandler)
        } catch (e: Exception) {
            e.printStackTrace()
            _state.value = CaptureState.ERROR
        }
    }

    private fun startPreviewSession(camera: CameraDevice, surfaces: List<Surface>) {
        captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        val outputConfigs = mutableListOf<OutputConfiguration>()
        
        for (surface in surfaces) {
            captureRequestBuilder?.addTarget(surface)
            outputConfigs.add(OutputConfiguration(surface))
        }
        
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputConfigs,
            Executors.newSingleThreadExecutor(),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    updateCaptureRequest()
                    _state.value = CaptureState.READY
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    _state.value = CaptureState.ERROR
                }
            }
        )

        camera.createCaptureSession(sessionConfig)
    }

    private fun updateCaptureRequest() {
        val builder = captureRequestBuilder ?: return
        val session = captureSession ?: return

        builder.set(CaptureRequest.JPEG_ORIENTATION, _sensorOrientation)

        // Auto vs Manual Exposure
        if (manualIso != null || manualShutter != null) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            if (manualIso != null) builder.set(CaptureRequest.SENSOR_SENSITIVITY, manualIso)
            if (manualShutter != null) builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualShutter)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }

        // Auto vs Manual Focus
        if (manualFocus != null) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocus)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }

        // Auto vs Manual White Balance
        if (manualWb != null) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, manualWb)
        } else {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        }

        try {
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun stop() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        stopCameraThread()
        _state.value = CaptureState.IDLE
    }

    // ✅ MELHORIA 2: Gerenciamento de estado seguro durante a troca
    override suspend fun switchCamera(cameraId: String) {
        if (currentCameraId == cameraId) return
        
        _state.value = CaptureState.INITIALIZING
        currentCameraId = cameraId
        
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            _sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            // Fecha a sessão e dispositivo atuais de forma limpa
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            
            // Reabre com a nova câmera
            if (currentSurfaces.isNotEmpty()) {
                openCamera(cameraId)
            } else {
                _state.value = CaptureState.IDLE
            }
        } catch (e: Exception) {
            e.printStackTrace()
            _state.value = CaptureState.ERROR
        }
    }

    override fun setIso(iso: Int?) {
        manualIso = iso
        updateCaptureRequest()
    }

    override fun setShutterSpeed(nanoseconds: Long?) {
        manualShutter = nanoseconds
        updateCaptureRequest()
    }

    override fun setWhiteBalance(mode: Int?) {
        manualWb = mode
        updateCaptureRequest()
    }

    override fun setFocusDistance(diopters: Float?) {
        manualFocus = diopters
        updateCaptureRequest()
    }

    override fun configure(resolution: String, fps: Int) {
        // TODO: Implementar configuração de resolução/FPS se necessário
    }
}