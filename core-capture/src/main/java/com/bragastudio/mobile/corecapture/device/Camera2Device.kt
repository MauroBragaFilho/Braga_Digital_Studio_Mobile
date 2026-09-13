package com.bragastudio.mobile.corecapture.device

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.params.DynamicRangeProfiles
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

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

    // Surface do encoder HDR (10-bit) atrelado à sessão durante uma gravação
    // "Caminho A". Quando != null a sessão tem DOIS outputs: o preview SDR (que
    // segue pelo GL com LUT/HUD) e este HDR em HLG10, alimentado direto pela
    // câmera. Serializa as reconfigurações de sessão com o switch de câmera.
    private var hdrRecordSurface: Surface? = null
    private val sessionMutex = Mutex()

    // Retry automático: falhas transitórias de abertura/configuração (ex.:
    // câmera ainda sendo liberada pela HAL durante a recriação da Activity por
    // rotação "fullSensor") são reabertas com backoff curto em vez de cair
    // direto em CaptureState.ERROR permanente. "generation" invalida retries
    // pendentes quando start()/stop()/switchCamera() iniciam um novo ciclo.
    private var openAttempts = 0
    private var generation = 0L
    private val maxOpenAttempts = 5
    private val openRetryDelayMs = 400L

    private var currentCameraId: String? = null
private var currentChars: CameraCharacteristics? = null

    // Estados dos controles avanÃƒÂ§ados (torch / estabilizaÃƒÂ§ÃƒÂ£o / HDR). SÃƒÂ£o
    // StateFlows expostos ÃƒÂ  UI via CaptureDevice; a aplicaÃƒÂ§ÃƒÂ£o no sensor
    // acontece em updateCaptureRequest(), no mesmo padrÃƒÂ£o dos controles
    // manuais (ISO/obturador/WB/foco).
    private val _torchEnabled = MutableStateFlow(false)
    private val _videoStabilizationEnabled = MutableStateFlow(false)
    private val _hdrEnabled = MutableStateFlow(false)

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

    /**
     * A câmera ATUAL suporta DPR HLG10 (API 33+) — pré-requisito do Caminho A
     * (gravação HDR 10-bit direta câmera→encoder). Lida dinamicamente do
     * CameraCharacteristics para acompanhar o switch de lente.
     */
    override val supportsTrueHdr: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (currentChars?.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                ?.getSupportedProfiles()
                ?.contains(DynamicRangeProfiles.HLG10) == true)

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

        // Nova geração: invalida retries pendentes de um ciclo anterior
        // (rotação/background) e zera o contador de tentativas de abertura.
        generation++
        openAttempts = 0

        _state.value = CaptureState.INITIALIZING
        startCameraThread()

        val idToOpen = currentCameraId ?: cameraRepository.getMainCamera()?.id ?: cameraRepository.getRearCameras().firstOrNull()?.id ?: cameraRepository.getAvailableCameras().firstOrNull()?.id ?: run {
            // Se estiver vazio, forÃƒÂ§amos um refresh que escaneia o hardware e tentamos de novo
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
            currentChars = cameraManager.getCameraCharacteristics(id)
            android.util.Log.i("BDSM-CAMERA", "Tentando abrir a cÃƒÂ¢mera $id...")
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    android.util.Log.i("BDSM-CAMERA", "CÃƒÂ¢mera $id aberta com SUCESSO. Iniciando sessÃƒÂ£o de preview...")
                    openAttempts = 0 // Sucesso: zera o contador de tentativas
                    cameraDevice = camera
                    startPreviewSession(camera, currentSurfaces)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    android.util.Log.w("BDSM-CAMERA", "CÃƒÂ¢mera $id desconectada.")
                    try {
                        camera.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    cameraDevice = null
                    // Desconexão também pode ser transitória (rotação/background):
                    // tenta reabrir a mesma lente com backoff.
                    scheduleRetry(id)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    android.util.Log.e("BDSM-CAMERA", "Erro na cÃƒÂ¢mera $id (Erro cÃƒÂ³digo: $error)")
                    try {
                        camera.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    cameraDevice = null
                    // Erro transitório (ex.: ERROR_CAMERA_IN_USE durante rotação)
                    // — tenta reabrir com backoff; só ERROR de verdade após N falhas.
                    scheduleRetry(id)
                }
            }, cameraHandler)
        } catch (e: Exception) {
            android.util.Log.e("BDSM-CAMERA", "Falha ao abrir a cÃƒÂ¢mera $id", e)
            scheduleRetry(id)
        }
    }

    private fun startPreviewSession(camera: CameraDevice, surfaces: List<Surface>) {
        try {
            captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            val outputConfigs = mutableListOf<OutputConfiguration>()
            
            // Quando o encoder HDR (10-bit) está atrelado (Caminho A), ele entra como
            // segundo output: o preview SDR continua no GL e o HLG10 vai direto
            // para o codec. O request inteiro roda em HLG10 e a HAL faz o
            // tone-map do preview (output companion).
            val targets = surfaces + (hdrRecordSurface?.let { listOf(it) } ?: emptyList())

            for (surface in targets) {
                captureRequestBuilder?.addTarget(surface)
                val oc = OutputConfiguration(surface)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    surface == hdrRecordSurface
                ) {
                    oc.setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
                }
                outputConfigs.add(oc)
            }
            
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    updateCaptureRequest()
                    _state.value = CaptureState.READY
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    android.util.Log.e("BDSM-CAMERA", "onConfigureFailed para a cÃƒÂ¢mera $currentCameraId")
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
                                android.util.Log.e("BDSM-CAMERA", "Fallback: Mudando da lÃƒÂ³gica ($currentId) para a fÃƒÂ­sica ($physicalMain)")
                                CoroutineScope(Dispatchers.Default).launch {
                                    switchCamera(physicalMain)
                                }
                                return
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    // Sem fallback físico disponível: tenta reabrir a mesma lente
                    // com backoff (falha de configuração costuma ser transitória).
                    scheduleRetry(currentId)
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
                    android.util.Log.w("BDSM-CAMERA", "SessionConfiguration falhou ou nÃƒÂ£o ÃƒÂ© totalmente suportada na HAL. Usando fallback createCaptureSession legado.", e)
                }
            }
            
            // Fallback legado com alta compatibilidade para Exynos / MediaTek
            @Suppress("DEPRECATION")
            camera.createCaptureSession(targets, callback, cameraHandler)
        } catch (e: Exception) {
            android.util.Log.e("BDSM-CAMERA", "Erro ao criar sessÃƒÂ£o de captura na cÃƒÂ¢mera $currentCameraId", e)
            currentCameraId?.let { scheduleRetry(it) } ?: run {
                _state.value = CaptureState.ERROR
            }
        }
    }

    /** Agenda a reabertura da câmera após uma falha transitória (HAL ocupada,
     *  desconexão momentânea, onConfigureFailed) com backoff curto. Só entra em
     *  CaptureState.ERROR de verdade após MAX_OPEN_ATTEMPTS seguidos. */
    private fun scheduleRetry(id: String) {
        openAttempts++
        if (openAttempts > maxOpenAttempts) {
            openAttempts = 0
            _state.value = CaptureState.ERROR
            return
        }
        val handler = cameraHandler
        if (handler == null) {
            // Thread de câmera já encerrada (stop) — sem como reabrir aqui.
            _state.value = CaptureState.ERROR
            return
        }
        val gen = generation
        android.util.Log.w(
            "BDSM-CAMERA",
            "Falha transitória ao abrir a câmera $id (tentativa $openAttempts/$maxOpenAttempts) — " +
                "nova tentativa em ${openRetryDelayMs}ms"
        )
        handler.postDelayed({
            if (gen != generation) return@postDelayed   // contexto inválido (stop/switch/start)
            if (currentCameraId != id || currentSurfaces.isEmpty()) return@postDelayed
            _state.value = CaptureState.INITIALIZING
            runCatching { cameraDevice?.close() }
            cameraDevice = null
            openCamera(id)
        }, openRetryDelayMs)
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

            // ---- Controles avançados (torch / estabilização / HDR) ----
            // Perguntamos à HAL pela câmera ATUAL o que ela realmente suporta,
            // para nunca pedir um recurso inexistente (ex.: OIS_ON numa lente
            // sem estabilização), o que algumas HALs tratam como erro e matam
            // a atualização do request.
            val chars = currentChars
            val hasFlash = chars?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val hasVideoStab = chars?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
            val hasOis = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
            val hasHdrScene = chars?.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
                ?.contains(CaptureRequest.CONTROL_SCENE_MODE_HDR) == true

            // Lanterna (torch): só liga quando a câmera tem flash.
            if (_torchEnabled.value && hasFlash) {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
            } else {
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            }

            // Estabilização de vídeo: EIS (CONTROL_VIDEO_STABILIZATION_MODE) e
            // OIS (LENS_OPTICAL_STABILIZATION_MODE) quando disponíveis.
            if (_videoStabilizationEnabled.value && hasVideoStab) {
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            } else {
                builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
            if (_videoStabilizationEnabled.value && hasOis) {
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
            } else {
                builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }

            // HDR real 10-bit (Caminho A): com o encoder 10-bit atrelado à sessão, o DPR
            // HLG10 vem do OutputConfiguration (não existe key de DPR no request em
            // API 33/34) e a HAL entrega o preview SDR tone-mapped no output
            // companion. Nesse modo NÃO usamos o scene mode HDR (merge 8-bit), pois
            // a captura já é HDR nativa — pedir os dois conflita em muitas HALs.
            if (hdrRecordSurface != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            } else if (_hdrEnabled.value && hasHdrScene) {
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
            } else {
                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }

            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        }.onFailure { e ->
            android.util.Log.w("BDSM-CAMERA", "Aviso ao atualizar CaptureRequest: ${e.message}")
        }
    }

    override val torchEnabled: StateFlow<Boolean> = _torchEnabled.asStateFlow()
    override val videoStabilizationEnabled: StateFlow<Boolean> = _videoStabilizationEnabled.asStateFlow()
    override val hdrEnabled: StateFlow<Boolean> = _hdrEnabled.asStateFlow()

    override fun setTorchEnabled(enabled: Boolean) {
        _torchEnabled.value = enabled
        updateCaptureRequest()
    }

    override fun setVideoStabilizationEnabled(enabled: Boolean) {
        _videoStabilizationEnabled.value = enabled
        updateCaptureRequest()
    }

    override fun setHdrEnabled(enabled: Boolean) {
        _hdrEnabled.value = enabled
        updateCaptureRequest()
    }

    override suspend fun setCameraHdrSurface(surface: Surface?): Boolean {
        if (surface == hdrRecordSurface) return true
        return sessionMutex.withLock {
            if (surface == null) hdrRecordSurface = null
            val camera = cameraDevice
            if (camera == null || captureSession == null) {
                // Sem sessão/câmera ativos é impossível atrelar o encoder — limpa
                // o estado para não sobrar um Surface morto como target HLG10 na
                // próxima sessão, e falha de forma limpa (chamador cai no GL).
                if (surface != null) hdrRecordSurface = null
                return@withLock (surface == null)
            }

            hdrRecordSurface = surface
            val success = reconfigureSession(camera)
            if (!success) {
                // Reconfiguração recusada pela HAL (ex.: HLG10 + preview SDR não
                // suportado juntos nesta resolução). Destaca o HDR e tenta
                // restaurar a sessão só-preview para a UI não perder a imagem.
                hdrRecordSurface = null
                runCatching { reconfigureSession(camera) }
                return@withLock false
            }
            true
        }
    }

    /**
     * Recria a CaptureSession do zero com os alvos atuais (preview +, quando
     * ativo, o surface do encoder HDR 10-bit). É necessário porque o Camera2
     * não permite adicionar/remover targets de uma sessão já configurada.
     * Suspende até o onConfigured/onConfigureFailed e devolve o resultado.
     */
    private suspend fun reconfigureSession(camera: CameraDevice): Boolean {
        val surfaces = currentSurfaces + (hdrRecordSurface?.let { listOf(it) } ?: emptyList())
        if (surfaces.isEmpty()) return true

        return try {
            suspendCancellableCoroutine { cont ->
                val oldSession = captureSession
                captureSession = null

                val outputConfigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    surfaces.map { s ->
                        OutputConfiguration(s).also { oc ->
                            if (s == hdrRecordSurface) {
                                oc.setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
                            }
                        }
                    }
                } else {
                    surfaces.map { s -> OutputConfiguration(s) }
                }

                val callback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        // Cria um builder novo com os alvos da sessao recem
                        // configurada (nao ha como enumerar targets do builder
                        // antigo: CaptureRequest nao expoe getTargets no SDK).
                        runCatching {
                            captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            surfaces.forEach { s -> captureRequestBuilder?.addTarget(s) }
                        }
                        updateCaptureRequest()
                        if (!cont.isCancelled) cont.resume(true)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        android.util.Log.e(
                            "BDSM-CAMERA",
                            "Reconfiguração de sessão (HDR direto) falhou: ${session.device.id}"
                        )
                        if (!cont.isCancelled) cont.resume(false)
                    }
                }

                // Fecha a sessão antiga antes de criar a nova (a troca direta é
                // tolerada pela maioria das HALs; em falha, o fallback do Caminho
                // A reabre a sessão só-preview).
                if (oldSession != null) {
                    try {
                        oldSession.close()
                    } catch (e: Exception) {
                        // Sessão já encerrada — segue para criar a nova.
                    }
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        camera.createCaptureSession(
                            SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                outputConfigs,
                                Executors.newSingleThreadExecutor(),
                                callback
                            )
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(surfaces, callback, cameraHandler)
                    }
                } catch (e: Throwable) {
                    if (!cont.isCancelled) cont.resume(false)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BDSM-CAMERA", "Erro ao reconfigurar sessão", e)
            false
        }
    }

    override suspend fun stop() {
        // Nova geração: invalida retries pendentes para que nenhum reabra a
        // câmera depois que o app saiu da preview / background.
        generation++
        sessionMutex.withLock {
            captureSession?.close()
            captureSession = null
            hdrRecordSurface = null
            cameraDevice?.close()
            cameraDevice = null
        }
        stopCameraThread()
        _state.value = CaptureState.IDLE
    }

    // Ã¢Å“â€¦ MELHORIA 2: Gerenciamento de estado seguro durante a troca
    override suspend fun switchCamera(cameraId: String) {
        if (currentCameraId == cameraId) return
        generation++
        openAttempts = 0
        sessionMutex.withLock {
            _state.value = CaptureState.INITIALIZING
            currentCameraId = cameraId

            try {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                _sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

                // Fecha a sessão e dispositivo atuais de forma limpa. O switch é
                // serializado com a reconfigureSession (HDR) pela sessionMutex:
                // se o encoder 10-bit está atrelado, o hdrRecordSurface sobrevive
                // ao switch e a startPreviewSession reanexa o HLG10 na nova lente.
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
                scheduleRetry(cameraId)
            }
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
        // TODO: Implementar configuraÃƒÂ§ÃƒÂ£o de resoluÃƒÂ§ÃƒÂ£o/FPS se necessÃƒÂ¡rio
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

            // Caso nÃƒÂ£o exista, busca o mais prÃƒÂ³ximo que mantenha a mesma proporÃƒÂ§ÃƒÂ£o (Aspect Ratio)
            val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()
            var bestMatch = sizes[0]
            var minDiff = Float.MAX_VALUE

            for (size in sizes) {
                val ratio = size.width.toFloat() / size.height.toFloat()
                if (Math.abs(ratio - targetRatio) < 0.05f) { // TolerÃƒÂ¢ncia de 5% no Aspect Ratio
                    val diff = Math.abs(size.width - targetWidth).toFloat()
                    if (diff < minDiff) {
                        minDiff = diff
                        bestMatch = size
                    }
                }
            }
            
            // Se nenhum bateu no Aspect Ratio, pega o mais prÃƒÂ³ximo da largura
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
