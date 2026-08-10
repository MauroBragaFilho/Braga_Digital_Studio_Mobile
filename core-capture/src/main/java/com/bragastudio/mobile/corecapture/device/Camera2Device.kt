package com.bragastudio.mobile.corecapture.device

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.bragastudio.mobile.corecapture.domain.CaptureDevice
import com.bragastudio.mobile.corecapture.domain.CaptureState
import com.bragastudio.mobile.corecapture.domain.CameraInfoModel
import com.bragastudio.mobile.corecapture.domain.CameraRepository
import com.bragastudio.mobile.corecapture.domain.LensType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Singleton
class Camera2Device @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraRepository: CameraRepository
) : CaptureDevice {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val _state = MutableStateFlow(CaptureState.IDLE)
    
    override val availableLenses: StateFlow<List<CameraInfoModel>> = cameraRepository.availableCameras

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

        val idToOpen = currentCameraId ?: cameraRepository.getMainCamera()?.id ?: cameraRepository.getRearCameras().firstOrNull()?.id ?: cameraRepository.getAvailableCameras().firstOrNull()?.id ?: run {
            // Se estiver vazio, forçamos um refresh que escaneia o hardware e tentamos de novo
            cameraRepository.refresh()
            cameraRepository.getMainCamera()?.id ?: cameraRepository.getRearCameras().firstOrNull()?.id ?: cameraRepository.getAvailableCameras().firstOrNull()?.id ?: run {
                _state.value = CaptureState.ERROR
                return
            }
        }

        if (currentCameraId == null) {
            currentCameraId = idToOpen
            _sensorOrientation = cameraManager.getCameraCharacteristics(idToOpen)
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        }

        openCamera(idToOpen)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(id: String) {
        try {
            android.util.Log.i("BDSM-CAMERA", "Tentando abrir a câmera $id...")
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    android.util.Log.i("BDSM-CAMERA", "Câmera $id aberta com SUCESSO. Iniciando sessão de preview...")
                    cameraDevice = camera
                    startPreviewSession(camera, currentSurfaces)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    android.util.Log.w("BDSM-CAMERA", "Câmera $id desconectada.")
                    try {
                        camera.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    cameraDevice = null
                    _state.value = CaptureState.IDLE
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    android.util.Log.e("BDSM-CAMERA", "Erro na câmera $id (Erro código: $error)")
                    try {
                        camera.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    cameraDevice = null
                    _state.value = CaptureState.ERROR
                }
            }, cameraHandler)
        } catch (e: Exception) {
            android.util.Log.e("BDSM-CAMERA", "Falha ao abrir a câmera $id", e)
            _state.value = CaptureState.ERROR
        }
    }

    private fun startPreviewSession(camera: CameraDevice, surfaces: List<Surface>) {
        try {
            captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            val outputConfigs = mutableListOf<OutputConfiguration>()
            
            for (surface in surfaces) {
                captureRequestBuilder?.addTarget(surface)
                outputConfigs.add(OutputConfiguration(surface))
            }
            
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    updateCaptureRequest()
                    _state.value = CaptureState.READY
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    android.util.Log.e("BDSM-CAMERA", "onConfigureFailed para a câmera $currentCameraId")
                    val currentId = currentCameraId ?: run {
                        _state.value = CaptureState.ERROR
                        return
                    }
                    
                    try {
                        val chars = cameraManager.getCameraCharacteristics(currentId)
                        val isLogical = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
                        
                        if (isLogical) {
                            val physicalMain = cameraRepository.getRearCameras().firstOrNull { 
                                it.id != currentId && it.lensType == LensType.MAIN 
                            }?.id ?: cameraRepository.getRearCameras().firstOrNull { it.id != currentId }?.id
                            
                            if (physicalMain != null) {
                                android.util.Log.e("BDSM-CAMERA", "Fallback: Mudando da lógica ($currentId) para a física ($physicalMain)")
                                CoroutineScope(Dispatchers.Default).launch {
                                    switchCamera(physicalMain)
                                }
                                return
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    
                    _state.value = CaptureState.ERROR
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val sessionConfig = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputConfigs,
                        Executors.newSingleThreadExecutor(),
                        callback
                    )
                    camera.createCaptureSession(sessionConfig)
                    return
                } catch (e: Exception) {
                    android.util.Log.w("BDSM-CAMERA", "SessionConfiguration falhou ou não é totalmente suportada na HAL. Usando fallback createCaptureSession legado.", e)
                }
            }
            
            // Fallback legado com alta compatibilidade para Exynos / MediaTek
            @Suppress("DEPRECATION")
            camera.createCaptureSession(surfaces, callback, cameraHandler)
        } catch (e: Exception) {
            android.util.Log.e("BDSM-CAMERA", "Erro ao criar sessão de captura na câmera $currentCameraId", e)
            _state.value = CaptureState.ERROR
        }
    }

    private fun updateCaptureRequest() {
        runCatching {
            val builder = captureRequestBuilder ?: return@runCatching
            val session = captureSession ?: return@runCatching

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

            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        }.onFailure { e ->
            android.util.Log.w("BDSM-CAMERA", "Aviso ao atualizar CaptureRequest: ${e.message}")
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
    
    override fun getBestSupportedSize(targetWidth: Int, targetHeight: Int): Pair<Int, Int>? {
        val id = currentCameraId ?: cameraRepository.getMainCamera()?.id ?: cameraRepository.getRearCameras().firstOrNull()?.id ?: cameraRepository.getAvailableCameras().firstOrNull()?.id ?: cameraManager.cameraIdList.firstOrNull() ?: return null
        return try {
            val chars = cameraManager.getCameraCharacteristics(id)
            val map = chars.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            if (sizes == null || sizes.isEmpty()) return Pair(targetWidth, targetHeight)
            
            // Tenta achar exatamente o formato solicitado (ex: 1920x1080)
            val exact = sizes.firstOrNull { it.width == targetWidth && it.height == targetHeight }
            if (exact != null) return Pair(exact.width, exact.height)

            // Caso não exista, busca o mais próximo que mantenha a mesma proporção (Aspect Ratio)
            val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()
            var bestMatch = sizes[0]
            var minDiff = Float.MAX_VALUE

            for (size in sizes) {
                val ratio = size.width.toFloat() / size.height.toFloat()
                if (Math.abs(ratio - targetRatio) < 0.05f) { // Tolerância de 5% no Aspect Ratio
                    val diff = Math.abs(size.width - targetWidth).toFloat()
                    if (diff < minDiff) {
                        minDiff = diff
                        bestMatch = size
                    }
                }
            }
            
            // Se nenhum bateu no Aspect Ratio, pega o mais próximo da largura
            if (minDiff == Float.MAX_VALUE) {
                for (size in sizes) {
                    val diff = Math.abs(size.width - targetWidth).toFloat()
                    if (diff < minDiff) {
                        minDiff = diff
                        bestMatch = size
                    }
                }
            }
            
            Pair(bestMatch.width, bestMatch.height)
        } catch (e: Exception) {
            Pair(targetWidth, targetHeight)
        }
    }
}