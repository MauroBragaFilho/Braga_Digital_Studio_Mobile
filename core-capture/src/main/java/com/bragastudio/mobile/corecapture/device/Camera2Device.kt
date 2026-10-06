package com.bragastudio.mobile.corecapture.device

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.os.ExecutorCompat
import com.bragastudio.mobile.corecapture.domain.CameraInfoModel
import com.bragastudio.mobile.corecapture.domain.CameraRepository
import com.bragastudio.mobile.corecapture.domain.CaptureDevice
import com.bragastudio.mobile.corecapture.domain.CaptureMetadata
import com.bragastudio.mobile.corecapture.domain.CaptureState
import com.bragastudio.mobile.corecapture.domain.FpsPlanner
import com.bragastudio.mobile.corecapture.domain.FpsRange
import com.bragastudio.mobile.corecapture.domain.LensType
import com.bragastudio.mobile.corecapture.domain.ManualControls
import com.bragastudio.mobile.corecapture.domain.ManualLimits
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class Camera2Device @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraRepository: CameraRepository,
) : CaptureDevice {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val _state = MutableStateFlow(CaptureState.IDLE)

    override val availableLenses: StateFlow<List<CameraInfoModel>> = cameraRepository.availableCameras

    // ------------------------------------------------------------------
    // Thread/executor ÚNICOS por vida do singleton (M8). Antes cada start()
    // criava um HandlerThread + Executor novos e o stop() encerrava a thread
    // com callbacks ainda pendentes (onOpened tardio, retries). Agora a thread
    // nunca é encerrada: todos os callbacks da câmera e da sessão rodam nela
    // e o descarte de callbacks antigos é feito por token de geração.
    // ------------------------------------------------------------------
    private val cameraHandler: Handler by lazy {
        val thread = HandlerThread("BDSM-CameraThread").also { it.start() }
        Handler(thread.looper)
    }
    private val cameraExecutor: Executor by lazy { ExecutorCompat.create(cameraHandler) }

    @Volatile private var cameraDevice: CameraDevice? = null

    @Volatile private var captureSession: CameraCaptureSession? = null

    @Volatile private var captureRequestBuilder: CaptureRequest.Builder? = null

    @Volatile private var currentSurfaces: List<Surface> = emptyList()
    private val requestLock = Any()

    // Surface do encoder HDR (10-bit) atrelado à sessão durante uma gravação
    // "Caminho A". Quando != null a sessão tem DOIS outputs: o preview SDR (que
    // segue pelo GL com LUT/HUD) e este HDR em HLG10, alimentado direto pela
    // câmera. Serializa as reconfigurações de sessão com start/stop/switch.
    @Volatile private var hdrRecordSurface: Surface? = null
    private val sessionMutex = Mutex()

    // Token de geração: incrementado a CADA ciclo (start/stop/switch/falha
    // definitiva). Todo callback (CameraDevice e CameraCaptureSession) captura o
    // token da sua criação e, se ele não for mais o atual, apenas fecha o
    // recurso que recebeu e sai sem tocar no estado — assim um onOpened tardio
    // não deixa a câmera aberta nem um onDisconnected antigo apaga o device novo.
    private val generation = AtomicLong(0L)

    // Retry automático: falhas transitórias de abertura/configuração (ex.:
    // câmera ainda sendo liberada pela HAL durante a recriação da Activity por
    // rotação) são reabertas com backoff curto em vez de cair direto em ERROR.
    // O contador só zera quando a SESSÃO chega a onConfigured (não em onOpened),
    // para que um laço "abre ok / configura mal" também termine em ERROR.
    private val openAttempts = AtomicInteger(0)
    private val maxOpenAttempts = 5
    private val openRetryDelayMs = 400L

    @Volatile private var currentCameraId: String? = null

    @Volatile private var currentChars: CameraCharacteristics? = null

    // Estados dos controles avançados (torch / estabilização / HDR).
    private val _torchEnabled = MutableStateFlow(false)
    private val _videoStabilizationEnabled = MutableStateFlow(false)
    private val _hdrEnabled = MutableStateFlow(false)

    // Controles manuais
    @Volatile private var manualIso: Int? = null

    @Volatile private var manualShutter: Long? = null

    @Volatile private var manualWb: Int? = null

    @Volatile private var manualFocus: Float? = null

    // FPS (M9)
    @Volatile private var targetFps: Int = 30

    @Volatile private var previewSize: Size? = null
    private val _effectiveFps = MutableStateFlow(0)
    override val effectiveFps: StateFlow<Int> = _effectiveFps.asStateFlow()

    // Telemetria (M12): último CaptureResult
    @Volatile private var lastIso: Int? = null

    @Volatile private var lastExposureNs: Long? = null

    @Volatile private var lastMetadataEmitMs = 0L
    private val _captureMetadata = MutableStateFlow(CaptureMetadata())
    override val captureMetadata: StateFlow<CaptureMetadata> = _captureMetadata.asStateFlow()
    private val _manualLimits = MutableStateFlow(ManualLimits())
    override val manualLimits: StateFlow<ManualLimits> = _manualLimits.asStateFlow()

    private val fallbackScope = CoroutineScope(Dispatchers.Default)

    override val deviceId: String = "Camera2"
    override val deviceName: String = "Primary Camera"
    override val state: StateFlow<CaptureState> = _state

    private var _sensorOrientation = 0
    override val sensorOrientation: Int
        get() = _sensorOrientation

    override val torchEnabled: StateFlow<Boolean> = _torchEnabled.asStateFlow()
    override val videoStabilizationEnabled: StateFlow<Boolean> = _videoStabilizationEnabled.asStateFlow()
    override val hdrEnabled: StateFlow<Boolean> = _hdrEnabled.asStateFlow()

    /**
     * A câmera ATUAL suporta DPR HLG10 (API 33+) — pré-requisito do Caminho A
     * (gravação HDR 10-bit direta câmera→encoder). Lida dinamicamente do
     * CameraCharacteristics para acompanhar o switch de lente.
     */
    override val supportsTrueHdr: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (
                currentChars?.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                    ?.getSupportedProfiles()
                    ?.contains(DynamicRangeProfiles.HLG10) == true
                )

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    /**
     * Idempotente: chamar start() de novo com os MESMOS surfaces enquanto a
     * câmera está iniciando/pronta não faz nada. Com surfaces diferentes (ou
     * após IDLE/ERROR) o ciclo anterior é desmontado por completo antes de reabrir.
     */
    @SuppressLint("MissingPermission")
    override suspend fun start(vararg surfaces: Surface) {
        if (surfaces.isEmpty()) return
        val newSurfaces = surfaces.toList()

        sessionMutex.withLock {
            val st = _state.value
            val alive = st == CaptureState.INITIALIZING || st == CaptureState.READY || st == CaptureState.RECORDING
            if (alive && newSurfaces == currentSurfaces) {
                android.util.Log.i("BDSM-CAMERA", "start() ignorado: câmera já ativa com os mesmos surfaces ($st)")
                return@withLock
            }

            releaseCameraLocked()
            currentSurfaces = newSurfaces
            openAttempts.set(0)
            _state.value = CaptureState.INITIALIZING

            val idToOpen = currentCameraId ?: pickDefaultCameraId() ?: run {
                // Lista vazia: força um scan e tenta de novo.
                runCatching { cameraRepository.refresh() }
                pickDefaultCameraId()
            }
            if (idToOpen == null) {
                _state.value = CaptureState.ERROR
                return@withLock
            }

            try {
                currentCameraId = idToOpen
                _sensorOrientation = cameraManager.getCameraCharacteristics(idToOpen)
                    .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            } catch (e: Exception) {
                android.util.Log.e("BDSM-CAMERA", "Falha ao ler características da câmera $idToOpen", e)
                _state.value = CaptureState.ERROR
                return@withLock
            }

            openCamera(idToOpen)
        }
    }

    private fun pickDefaultCameraId(): String? = cameraRepository.getMainCamera()?.id
        ?: cameraRepository.getRearCameras().firstOrNull()?.id
        ?: cameraRepository.getAvailableCameras().firstOrNull()?.id

    /** Fecha sessão e device atuais e invalida todos os callbacks pendentes. Chamar sob [sessionMutex]. */
    private fun releaseCameraLocked() {
        generation.incrementAndGet()
        val session = captureSession
        val device = cameraDevice
        captureSession = null
        cameraDevice = null
        captureRequestBuilder = null
        runCatching { session?.close() }
        runCatching { device?.close() }
    }

    override suspend fun stop() {
        sessionMutex.withLock {
            releaseCameraLocked()
            hdrRecordSurface = null
            currentSurfaces = emptyList()
            openAttempts.set(0)
            _effectiveFps.value = 0
            _state.value = CaptureState.IDLE
        }
    }

    // Gerenciamento de estado seguro durante a troca de lente.
    override suspend fun switchCamera(cameraId: String) {
        if (currentCameraId == cameraId) return
        sessionMutex.withLock {
            _state.value = CaptureState.INITIALIZING
            currentCameraId = cameraId
            openAttempts.set(0)
            try {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                _sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

                // O switch é serializado com a reconfigureSession (HDR) pela
                // sessionMutex: se o encoder 10-bit está atrelado, o
                // hdrRecordSurface sobrevive ao switch e a startPreviewSession
                // reanexa o HLG10 na nova lente.
                releaseCameraLocked()

                if (currentSurfaces.isNotEmpty()) {
                    openCamera(cameraId)
                } else {
                    _state.value = CaptureState.IDLE
                }
            } catch (e: Exception) {
                android.util.Log.e("BDSM-CAMERA", "Erro ao trocar para a câmera $cameraId", e)
                scheduleRetry(cameraId, generation.get())
            }
        }
    }

    // ------------------------------------------------------------------
    // Abertura da câmera
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun openCamera(id: String) {
        val gen = generation.get()
        try {
            val chars = cameraManager.getCameraCharacteristics(id)
            currentChars = chars
            publishManualLimits(chars)
            android.util.Log.i("BDSM-CAMERA", "Tentando abrir a câmera $id (geração $gen)...")
            cameraManager.openCamera(
                id,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (gen != generation.get()) {
                            // Abertura tardia de um ciclo já descartado: não deixa a câmera aberta.
                            android.util.Log.w("BDSM-CAMERA", "onOpened obsoleto para $id — fechando")
                            runCatching { camera.close() }
                            return
                        }
                        android.util.Log.i("BDSM-CAMERA", "Câmera $id aberta. Iniciando sessão...")
                        cameraDevice = camera
                        startPreviewSession(camera, gen)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        runCatching { camera.close() }
                        if (gen != generation.get()) return
                        android.util.Log.w("BDSM-CAMERA", "Câmera $id desconectada.")
                        cameraDevice = null
                        captureSession = null
                        _state.value = CaptureState.INITIALIZING
                        // Desconexão também pode ser transitória (rotação/background).
                        scheduleRetry(id, gen)
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        runCatching { camera.close() }
                        if (gen != generation.get()) return
                        android.util.Log.e("BDSM-CAMERA", "Erro na câmera $id (código: $error)")
                        cameraDevice = null
                        captureSession = null
                        _state.value = CaptureState.INITIALIZING
                        scheduleRetry(id, gen)
                    }
                },
                cameraHandler,
            )
        } catch (e: Exception) {
            android.util.Log.e("BDSM-CAMERA", "Falha ao abrir a câmera $id", e)
            scheduleRetry(id, gen)
        }
    }

    private fun createBuilder(camera: CameraDevice): CaptureRequest.Builder {
        // TEMPLATE_RECORD é o apropriado para vídeo (a HAL ajusta AE/AF/estabilização
        // para fluxo contínuo); TEMPLATE_PREVIEW fica de reserva para HALs que recusem.
        return try {
            camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        } catch (e: Exception) {
            camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        }
    }

    private fun buildOutputConfigs(targets: List<Surface>): List<OutputConfiguration> = targets.map { surface ->
        OutputConfiguration(surface).also { oc ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && surface == hdrRecordSurface) {
                oc.setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
            }
        }
    }

    private fun startPreviewSession(camera: CameraDevice, gen: Long) {
        try {
            val surfaces = currentSurfaces
            // Quando o encoder HDR (10-bit) está atrelado (Caminho A), ele entra como
            // segundo output: o preview SDR continua no GL e o HLG10 vai direto
            // para o codec; a HAL faz o tone-map do preview.
            val targets = surfaces + (hdrRecordSurface?.let { listOf(it) } ?: emptyList())

            val builder = createBuilder(camera)
            targets.forEach { builder.addTarget(it) }
            captureRequestBuilder = builder

            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (gen != generation.get()) {
                        runCatching { session.close() }
                        return
                    }
                    captureSession = session
                    openAttempts.set(0) // sucesso de verdade: câmera aberta E sessão configurada
                    updateCaptureRequest()
                    _state.value = CaptureState.READY
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    runCatching { session.close() }
                    if (gen != generation.get()) return
                    val currentId = currentCameraId
                    android.util.Log.e("BDSM-CAMERA", "onConfigureFailed para a câmera $currentId")
                    if (currentId == null) {
                        _state.value = CaptureState.ERROR
                        return
                    }

                    try {
                        val chars = cameraManager.getCameraCharacteristics(currentId)
                        val isLogical = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true

                        if (isLogical) {
                            val physicalMain = cameraRepository.getRearCameras().firstOrNull {
                                it.id != currentId && it.lensType == LensType.MAIN
                            }?.id ?: cameraRepository.getRearCameras().firstOrNull { it.id != currentId }?.id

                            if (physicalMain != null) {
                                android.util.Log.e("BDSM-CAMERA", "Fallback: da lógica ($currentId) para a física ($physicalMain)")
                                fallbackScope.launch { switchCamera(physicalMain) }
                                return
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("BDSM-CAMERA", "Erro ao avaliar fallback de câmera física", e)
                    }

                    // Sem fallback físico: reabre a mesma lente com backoff.
                    scheduleRetry(currentId, gen)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    camera.createCaptureSession(
                        SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            buildOutputConfigs(targets),
                            cameraExecutor,
                            callback,
                        ),
                    )
                    return
                } catch (e: Exception) {
                    android.util.Log.w("BDSM-CAMERA", "SessionConfiguration falhou; usando createCaptureSession legado.", e)
                }
            }

            // Fallback legado com alta compatibilidade para Exynos / MediaTek
            @Suppress("DEPRECATION")
            camera.createCaptureSession(targets, callback, cameraHandler)
        } catch (e: Exception) {
            android.util.Log.e("BDSM-CAMERA", "Erro ao criar sessão de captura na câmera $currentCameraId", e)
            currentCameraId?.let { scheduleRetry(it, gen) } ?: run {
                if (gen == generation.get()) _state.value = CaptureState.ERROR
            }
        }
    }

    /**
     * Agenda a reabertura após uma falha transitória (HAL ocupada, desconexão,
     * onConfigureFailed) com backoff curto. Esgotadas as tentativas, fecha de
     * fato session/device e vai para CaptureState.ERROR.
     */
    private fun scheduleRetry(id: String, gen: Long) {
        if (gen != generation.get()) return
        val attempt = openAttempts.incrementAndGet()
        if (attempt > maxOpenAttempts) {
            failPermanently(gen)
            return
        }
        android.util.Log.w(
            "BDSM-CAMERA",
            "Falha transitória na câmera $id (tentativa $attempt/$maxOpenAttempts) — nova tentativa em ${openRetryDelayMs}ms",
        )
        cameraHandler.postDelayed({
            if (gen != generation.get()) return@postDelayed   // stop/switch/start já mudou o contexto
            if (currentCameraId != id || currentSurfaces.isEmpty()) return@postDelayed
            _state.value = CaptureState.INITIALIZING
            val session = captureSession
            val device = cameraDevice
            captureSession = null
            cameraDevice = null
            runCatching { session?.close() }
            runCatching { device?.close() }
            openCamera(id)
        }, openRetryDelayMs)
    }

    private fun failPermanently(gen: Long) {
        if (gen != generation.get()) return
        generation.incrementAndGet() // zumbis ainda pendentes passam a ser descartados
        val session = captureSession
        val device = cameraDevice
        captureSession = null
        cameraDevice = null
        captureRequestBuilder = null
        runCatching { session?.close() }
        runCatching { device?.close() }
        openAttempts.set(0)
        _effectiveFps.value = 0
        _state.value = CaptureState.ERROR
        android.util.Log.e("BDSM-CAMERA", "Tentativas esgotadas — câmera em ERROR")
    }

    // ------------------------------------------------------------------
    // CaptureRequest: exposição / foco / WB / FPS / extras
    // ------------------------------------------------------------------

    private fun publishManualLimits(chars: CameraCharacteristics) {
        val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exp = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        _manualLimits.value = ManualLimits(
            isoMin = iso?.lower,
            isoMax = iso?.upper,
            exposureMinNs = exp?.lower,
            exposureMaxNs = exp?.upper,
            minFocusDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
        )
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            if (iso != null) lastIso = iso
            if (exposure != null) lastExposureNs = exposure

            // Limita a publicação a ~10 Hz para não inundar os coletores do StateFlow.
            val now = SystemClock.elapsedRealtime()
            if (now - lastMetadataEmitMs < 100L) return
            lastMetadataEmitMs = now
            _captureMetadata.value = CaptureMetadata(
                iso = iso,
                exposureTimeNs = exposure,
                frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION),
                focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                aeAuto = manualIso == null && manualShutter == null,
            )
        }
    }

    private fun updateCaptureRequest() {
        synchronized(requestLock) {
            runCatching {
                val builder = captureRequestBuilder ?: return@runCatching
                val session = captureSession ?: return@runCatching
                val chars = currentChars

                builder.set(CaptureRequest.JPEG_ORIENTATION, _sensorOrientation)

                // ---- Faixa de FPS (M9) ----
                val aeRanges = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.map { FpsRange(it.lower, it.upper) }.orEmpty()
                val minFrameDuration = previewSize?.let { size ->
                    runCatching {
                        chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                            ?.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
                    }.getOrNull()
                }?.takeIf { it > 0L }
                val fpsPlan = FpsPlanner.plan(aeRanges, targetFps, minFrameDuration)

                // ---- Exposição (M12) ----
                val limits = _manualLimits.value
                val exposure = ManualControls.resolveExposure(
                    manualIso = manualIso,
                    manualShutterNs = manualShutter,
                    lastIso = lastIso,
                    lastExposureNs = lastExposureNs,
                    isoMin = limits.isoMin,
                    isoMax = limits.isoMax,
                    exposureMinNs = limits.exposureMinNs,
                    exposureMaxNs = limits.exposureMaxNs,
                )
                if (exposure != null) {
                    // Com qualquer controle manual o AE vai a OFF; o valor que o usuário
                    // NÃO fixou vem do último CaptureResult (senão ficaria indefinido).
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, exposure.iso)
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.exposureNs)

                    // AE_MODE_OFF ignora CONTROL_AE_TARGET_FPS_RANGE: o FPS vem de
                    // SENSOR_FRAME_DURATION, nunca menor que o tempo de exposição.
                    var frameDuration = maxOf(FpsPlanner.frameDurationNs(fpsPlan.effectiveFps), exposure.exposureNs)
                    if (minFrameDuration != null) frameDuration = maxOf(frameDuration, minFrameDuration)
                    chars?.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION)?.let {
                        frameDuration = minOf(frameDuration, it)
                    }
                    builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDuration)
                    _effectiveFps.value = FpsPlanner.fpsFromFrameDuration(frameDuration)
                } else {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    fpsPlan.aeRange?.let {
                        builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(it.lower, it.upper))
                    }
                    _effectiveFps.value = fpsPlan.effectiveFps
                }

                // ---- Foco ----
                val afModes = chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                val manualFocusValue = manualFocus?.let { ManualControls.coerceFocus(it, limits.minFocusDiopters) }
                if (manualFocusValue != null) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusValue)
                } else if (afModes == null || afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }

                // ---- Balanço de branco ----
                val awbModes = chars?.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
                val wb = manualWb
                if (wb != null && (awbModes == null || awbModes.contains(wb))) {
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, wb)
                } else {
                    builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                }

                // ---- Controles avançados (torch / estabilização / HDR) ----
                // Perguntamos à HAL o que a câmera ATUAL suporta para nunca pedir
                // um recurso inexistente (algumas HALs rejeitam o request inteiro).
                val hasFlash = chars?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val hasVideoStab = chars?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                    ?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
                val hasOis = chars?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
                val hasHdrScene = chars?.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)
                    ?.contains(CaptureRequest.CONTROL_SCENE_MODE_HDR) == true

                builder.set(
                    CaptureRequest.FLASH_MODE,
                    if (_torchEnabled.value && hasFlash) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
                )

                builder.set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    if (_videoStabilizationEnabled.value && hasVideoStab) {
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    } else {
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    },
                )
                builder.set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    if (_videoStabilizationEnabled.value && hasOis) {
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                    } else {
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                    },
                )

                // HDR real 10-bit (Caminho A): o DPR HLG10 vem do OutputConfiguration;
                // nesse modo NÃO usamos o scene mode HDR (merge 8-bit) — pedir os dois
                // conflita em muitas HALs.
                if (hdrRecordSurface != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                } else if (_hdrEnabled.value && hasHdrScene) {
                    builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                    builder.set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
                } else {
                    builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                }

                session.setRepeatingRequest(builder.build(), captureCallback, cameraHandler)
            }.onFailure { e ->
                android.util.Log.w("BDSM-CAMERA", "Aviso ao atualizar CaptureRequest: ${e.message}")
            }
        }
    }

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

    /**
     * Aplica o FPS alvo no CaptureRequest da sessão ativa (não exige reabrir a
     * câmera). O valor efetivo, após validar faixa de AE e duração mínima de
     * quadro, sai em [effectiveFps]. A resolução depende dos surfaces e é
     * definida pelo chamador via getBestSupportedSize()/start(); aqui só é lida
     * para validar o FPS máximo.
     */
    override fun configure(resolution: String, fps: Int) {
        targetFps = fps.coerceIn(1, 240)
        parseResolution(resolution)?.let { previewSize = it }
        if (captureSession != null) updateCaptureRequest()
    }

    private fun parseResolution(resolution: String): Size? {
        val parts = resolution.lowercase().filter { it.isDigit() || it == 'x' }.split('x')
        val w = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val h = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return if (w > 0 && h > 0) Size(w, h) else null
    }

    // ------------------------------------------------------------------
    // HDR 10-bit (Caminho A)
    // ------------------------------------------------------------------

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
     * ativo, o surface do encoder HDR 10-bit). Suspende até o
     * onConfigured/onConfigureFailed e devolve o resultado.
     */
    private suspend fun reconfigureSession(camera: CameraDevice): Boolean {
        val surfaces = currentSurfaces + (hdrRecordSurface?.let { listOf(it) } ?: emptyList())
        if (surfaces.isEmpty()) return true
        val gen = generation.get()

        return try {
            suspendCancellableCoroutine { cont ->
                val oldSession = captureSession
                captureSession = null

                val callback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (gen != generation.get()) {
                            runCatching { session.close() }
                            if (cont.isActive) cont.resume(false)
                            return
                        }
                        captureSession = session
                        // Builder novo com os alvos da sessão recém-configurada (o
                        // CaptureRequest não expõe os targets do builder antigo).
                        runCatching {
                            val b = createBuilder(camera)
                            surfaces.forEach { s -> b.addTarget(s) }
                            captureRequestBuilder = b
                        }
                        updateCaptureRequest()
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        android.util.Log.e("BDSM-CAMERA", "Reconfiguração de sessão (HDR direto) falhou")
                        runCatching { session.close() }
                        if (cont.isActive) cont.resume(false)
                    }
                }

                // Fecha a sessão antiga antes de criar a nova (em falha, o fallback
                // do Caminho A reabre a sessão só-preview).
                runCatching { oldSession?.close() }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        camera.createCaptureSession(
                            SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                buildOutputConfigs(surfaces),
                                cameraExecutor,
                                callback,
                            ),
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(surfaces, callback, cameraHandler)
                    }
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resume(false)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BDSM-CAMERA", "Erro ao reconfigurar sessão", e)
            false
        }
    }

    // ------------------------------------------------------------------
    // Tamanhos
    // ------------------------------------------------------------------

    override fun getBestSupportedSize(targetWidth: Int, targetHeight: Int): Pair<Int, Int>? {
        val id = currentCameraId ?: cameraRepository.getMainCamera()?.id
            ?: cameraRepository.getRearCameras().firstOrNull()?.id
            ?: cameraRepository.getAvailableCameras().firstOrNull()?.id
            ?: cameraManager.cameraIdList.firstOrNull() ?: return null
        return try {
            val chars = cameraManager.getCameraCharacteristics(id)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
            if (sizes == null || sizes.isEmpty()) return Pair(targetWidth, targetHeight)

            // Tenta achar exatamente o formato solicitado (ex: 1920x1080)
            val exact = sizes.firstOrNull { it.width == targetWidth && it.height == targetHeight }
            val chosen: Size = if (exact != null) {
                exact
            } else {
                // Mais próximo que mantenha a proporção (tolerância de 5%).
                val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()
                val sameRatio = sizes.filter {
                    Math.abs(it.width.toFloat() / it.height.toFloat() - targetRatio) < 0.05f
                }
                (if (sameRatio.isNotEmpty()) sameRatio else sizes.toList())
                    .minByOrNull { Math.abs(it.width - targetWidth) } ?: sizes[0]
            }
            previewSize = chosen // usado para validar o FPS máximo (getOutputMinFrameDuration)
            Pair(chosen.width, chosen.height)
        } catch (e: Exception) {
            Pair(targetWidth, targetHeight)
        }
    }
}
