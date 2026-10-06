package com.bragastudio.mobile.coremedia.domain

import android.Manifest
import android.annotation.TargetApi
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import com.bragastudio.mobile.core.domain.VideoSettings
import com.bragastudio.mobile.corecapture.domain.AudioCaptureService
import com.bragastudio.mobile.corecapture.domain.AudioSink
import com.bragastudio.mobile.corecapture.domain.CameraInfoModel
import com.bragastudio.mobile.corecapture.domain.CaptureDevice
import com.bragastudio.mobile.corecapture.domain.CaptureMetadata
import com.bragastudio.mobile.corecapture.domain.CaptureState
import com.bragastudio.mobile.corecapture.domain.ManualLimits
import com.bragastudio.mobile.coremedia.service.CaptureForegroundService
import com.bragastudio.mobile.network.LinkTelemetry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Hub de captura. Dono ÚNICO da câmera, do GL ([nativeRenderer]), de REC/NDI/BSP e do áudio.
 *
 * SESSÃO DESACOPLADA DA TELA (A3 / item 1.9) — contrato:
 *  - A TextureView do Preview é só um consumidor opcional: [attachPreviewSurface] /
 *    [detachPreviewSurface] são idempotentes e NUNCA reiniciam a câmera de uma sessão viva.
 *  - Com REC/NDI/BSP ativos ([sessionConsumers]), soltar o preview solta apenas a surface; a câmera, o
 *    renderer GL, as saídas e o áudio continuam. Sem nenhum consumidor, o desligamento é completo.
 *  - O [CaptureForegroundService] (camera|microphone) ancora o processo enquanto [foregroundWanted];
 *    é pedido no INÍCIO do consumidor, com a Activity visível. Sem permissão/permitido pelo sistema, o
 *    FGS não sobe e a sessão sobrevive só enquanto o app estiver no primeiro plano.
 *  - Quando o último consumidor termina e não há preview, a sessão é desligada aqui (não na UI).
 *  - O sink de áudio (REC antes de NDI, L6) pertence ao grafo e sobrevive ao ViewModel.
 */
@Singleton
@TargetApi(Build.VERSION_CODES.P)
class MediaGraph @Inject constructor(
    private val camera2Device: com.bragastudio.mobile.corecapture.device.Camera2Device,
    private val uvcCaptureDevice: com.bragastudio.mobile.corecapture.device.UvcCaptureDevice,
    private val sonyRemoteCaptureDevice: com.bragastudio.mobile.corecapture.device.SonyRemoteCaptureDevice,
    val recordManager: RecordManager,
    private val ndiManager: NdiManager,
    private val bspManager: BspManager,
    private val nativeRenderer: com.bragastudio.mobile.coremedia.graphics.NativeRenderer,
    val settingsRepository: com.bragastudio.mobile.core.domain.SettingsRepository,
    val lutRepository: com.bragastudio.mobile.core.repository.LutRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext val context: android.content.Context,
    private val linkTelemetry: LinkTelemetry,
    private val audioManagerService: AudioManagerService,
    private val audioCaptureService: AudioCaptureService,
) {
    private companion object {
        const val TAG = "MediaGraph"

        /** Quanto tempo a fonte pode ficar em ERROR/IDLE durante o REC antes de ser dada como perdida. */
        const val SOURCE_LOSS_GRACE_MS = 1_500L

        /** Espera ao renomear o NDI antes de reiniciar o sender (evita reiniciar a cada tecla). */
        const val NDI_RENAME_DEBOUNCE_MS = 500L
    }

    /** Saídas de transmissão tratadas pela interface comum (erros/estado); métodos específicos ficam em cada manager. */
    private val streamOutputs: List<StreamOutput> get() = listOf(ndiManager, bspManager)

    // Fonte ativa como StateFlow: os fluxos derivados (lentes, fps efetivo, metadata, limites)
    // seguem a troca de fonte via flatMapLatest. StateFlow é thread-safe (lido por vários coletores).
    private val _captureDevice = MutableStateFlow<CaptureDevice>(camera2Device)

    // Interno: a UI usa os métodos/fluxos do MediaGraph (switchLens, setIso, sensorOrientation...).
    private var captureDevice: CaptureDevice
        get() = _captureDevice.value
        set(value) {
            _captureDevice.value = value
        }

    /** Orientação do sensor da fonte ativa (graus). */
    val sensorOrientation: Int get() = captureDevice.sensorOrientation

    /** Troca a lente/câmera da fonte ativa. */
    suspend fun switchLens(lensId: String) = captureDevice.switchCamera(lensId).also { refreshSourceGeometry() }

    // Controles manuais da fonte ativa (a UI limita os valores à faixa real antes de chamar).
    fun setManualIso(iso: Int?) = captureDevice.setIso(iso)
    fun setManualShutter(nanoseconds: Long?) = captureDevice.setShutterSpeed(nanoseconds)
    fun setManualWhiteBalance(mode: Int?) = captureDevice.setWhiteBalance(mode)
    fun setManualFocus(diopters: Float?) = captureDevice.setFocusDistance(diopters)

    /** Rotação do dispositivo repassada ao renderer nativo (preview/gravação). */
    fun updateRotationDegrees(degrees: Float) = nativeRenderer.updateRotationDegrees(degrees)
    private var previewSurface: Surface? = null

    // True entre o primeiro attach (câmera + renderer prontos) e o desligamento completo da sessão.
    // Só é lido/escrito sob previewLifecycleMutex (ou em coletores que o adquirem).
    @Volatile private var sessionLive = false
    private val _previewAttached = MutableStateFlow(false)
    private val _ndiAudioEnabled = MutableStateFlow(true)

    private var ndiImageReader: ImageReader? = null
    private var ndiHandlerThread: HandlerThread? = null
    private var ndiHandler: Handler? = null

    @Volatile private var activeNdiResolution: String? = null

    // SupervisorJob: a falha de um coletor não cancela os irmãos; o handler registra no log em
    // vez de derrubar o processo (M16). Mantém Dispatchers.IO.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "Exceção não tratada no MediaGraph", t) },
    )

    @Volatile private var isScopeRunning = false
    private var scopePollingJob: Job? = null
    private val previewLifecycleMutex = Mutex()

    // Serializam o ciclo de vida de NDI/BSP (start/stop/restart). NÃO são usados dentro de
    // restartSession() (mutex não reentrante → deadlock).
    private val ndiLifecycleMutex = Mutex()
    private val bspLifecycleMutex = Mutex()

    // Avisos do próprio grafo (ex.: mudança de RES/FPS ignorada durante o REC), mesclados em errorEvents.
    private val graphMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    // Mescla os eventos de erro do RecordManager, NdiManager e BspManager num único flow para
    // a UI (PreviewViewModel/PreviewScreen) consumir com um único collector.
    val errorEvents: SharedFlow<String> =
        merge(recordManager.errorEvents, *streamOutputs.map { it.errorEvents }.toTypedArray(), graphMessages)
            .shareIn(scope, SharingStarted.Eagerly, replay = 0)

    // ---------------------------------------------------------------------------------------
    // Sessão desacoplada: consumidores ativos e foreground service
    // ---------------------------------------------------------------------------------------

    /** Saídas ativas (REC/NDI/BSP). Contagem de referência que decide se a sessão sobrevive à tela. */
    val sessionConsumers: StateFlow<SessionConsumers> =
        combine(recordManager.recState, ndiManager.isNdiActive, bspManager.isBspActive) { rec, ndi, bsp ->
            SessionConsumers.from(rec, ndi, bsp)
        }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, SessionConsumers())

    /**
     * True enquanto o foreground service deve existir: há consumidor ativo; ao ficar inativo espera
     * [CaptureSessionPolicy.INACTIVE_GRACE_MS] (reinícios de NDI/BSP passam por "inativo" por instantes).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val foregroundWanted: StateFlow<Boolean> = sessionConsumers
        .transformLatest { c ->
            if (!c.anyActive) delay(CaptureSessionPolicy.INACTIVE_GRACE_MS)
            emit(c.anyActive)
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    private fun currentConsumers(): SessionConsumers = SessionConsumers.from(recordManager.recState.value, ndiManager.isNdiActive.value, bspManager.isBspActive.value)

    private fun hasPermission(permission: String): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Pede o FGS. [expected] é o que está para ficar ativo (o consumidor ainda não marcou o estado).
     * Deve ser chamado com a Activity visível; recusas do sistema são tratadas em [CaptureForegroundService.start].
     */
    private fun requestCaptureService(expected: SessionConsumers, force: Boolean = false) {
        if (!force && CaptureForegroundService.isRunning) return
        val plan = CaptureSessionPolicy.planForeground(
            expected, hasPermission(Manifest.permission.CAMERA), hasPermission(Manifest.permission.RECORD_AUDIO),
        )
        if (!plan.start) {
            Log.i(TAG, "Serviço de captura não iniciado: ${plan.reason}")
            return
        }
        CaptureForegroundService.start(context, plan.camera, plan.microphone)
    }

    /** Ação "Parar" da notificação: encerra o take e desliga NDI/BSP; a sessão cai pela política normal. */
    suspend fun stopAllOutputs() {
        stopRecording(StopReason.USER)
        settingsRepository.setNdiEnabled(false)
        settingsRepository.setBspEnabled(false)
    }

    // Áudio: o grafo é dono do sink (sobrevive ao ViewModel). Gravação PRIMEIRO (L6): o envio de áudio
    // NDI divide mutex com o vídeo clockado e pode bloquear; se viesse antes, uma rede lenta atrasaria
    // o loop do AudioRecord e jitteraria o PTS do áudio do MP4. O toggle "Mudo" da tela de NDI só
    // afeta o stream NDI; a gravação recebe áudio sempre.
    private val audioSink = AudioSink { pcmData, numSamples, numChannels, sampleRate ->
        recordManager.feedAudio(pcmData, numSamples, numChannels, sampleRate)
        if (_ndiAudioEnabled.value) {
            ndiManager.feedAudio(pcmData, numSamples, numChannels, sampleRate)
        }
    }

    private data class AudioPlan(val needed: Boolean, val device: android.media.AudioDeviceInfo?)

    // fps configurado (VideoSettings/configureCapture): fallback do fps efetivo para fontes que não o informam.
    private val _configuredFps = MutableStateFlow(com.bragastudio.mobile.core.domain.VideoSettings().fps)

    // Tamanho do buffer pedido à câmera na última (re)abertura de sessão; com a sensorOrientation
    // da fonte ativa define o aspecto do conteúdo (OutputOrientation.sourceAspect).
    @Volatile private var sourceBufferSize: Pair<Int, Int>? = null

    /**
     * Repassa ao renderer a geometria da fonte ativa para a orientação das saídas. Camera2 (montada
     * no aparelho) acompanha o display; UVC/Sony são fontes externas e não giram com o aparelho.
     */
    private fun refreshSourceGeometry() {
        val size = sourceBufferSize ?: return
        nativeRenderer.setSourceGeometry(size.first, size.second, captureDevice.sensorOrientation, captureDevice === camera2Device)
    }

    /** Lentes da fonte ATIVA (Camera2/UVC/Sony); atualiza ao trocar de fonte (M36). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeLenses: StateFlow<List<CameraInfoModel>> =
        _captureDevice.flatMapLatest { it.availableLenses }
            .stateIn(scope, SharingStarted.Eagerly, camera2Device.availableLenses.value)

    /** FPS que a fonte realmente entrega; cai no fps configurado quando a fonte não informa. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val effectiveFps: StateFlow<Int> =
        combine(_captureDevice.flatMapLatest { it.effectiveFps }, _configuredFps) { eff, cfg ->
            FpsResolver.effective(eff, cfg)
        }.stateIn(scope, SharingStarted.Eagerly, _configuredFps.value)

    /** Metadata do último CaptureResult (Camera2); valor neutro nas demais fontes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val captureMetadata: StateFlow<CaptureMetadata> =
        _captureDevice.flatMapLatest { it.captureMetadata }
            .stateIn(scope, SharingStarted.Eagerly, CaptureMetadata())

    /** Limites ISO/obturador/foco da câmera ativa; vazio fora do Camera2. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val manualLimits: StateFlow<ManualLimits> =
        _captureDevice.flatMapLatest { it.manualLimits }
            .stateIn(scope, SharingStarted.Eagerly, ManualLimits())

    // Eventos estruturados da gravação (SourceLost, DiskFull, EncoderError, Stopped) para a UI
    // avisar o operador. Sem replay: tela nova não reabre aviso antigo.
    private val _recordingEvents = MutableSharedFlow<RecordingEvent>(extraBufferCapacity = 16)
    val recordingEvents: SharedFlow<RecordingEvent> = _recordingEvents

    /** Estado do take (Idle/Preparing/Recording/Stopping): a UI desabilita o REC em Preparing/Stopping. */
    val recState: StateFlow<RecState> = recordManager.recState

    // ✅ Variáveis de estado locais para acessar configurações de forma síncrona
    @Volatile private var currentVideoSettings = com.bragastudio.mobile.core.domain.VideoSettings()

    @Volatile private var currentMonitorSettings = com.bragastudio.mobile.core.domain.MonitorSettings()

    private val _videoScopes = MutableStateFlow(com.bragastudio.mobile.core.domain.VideoScopes())
    val videoScopes: StateFlow<com.bragastudio.mobile.core.domain.VideoScopes> = _videoScopes.asStateFlow()

    private val _captureState = MutableStateFlow(CaptureState.IDLE)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    @Volatile private var currentActiveLut: com.bragastudio.mobile.core.model.Lut? = null

    private var stateObserverJob: Job? = null
    private var sourceLossJob: Job? = null

    // Rótulo da lente ativa para a telemetria do Link (informado pela UI; padrão = nome do device).
    @Volatile private var activeLensLabel: String? = null

    // Telemetria da Sony (bateria, storage, ISO/shutter/abertura atuais, foco) —
    // só é relevante quando captureDevice == sonyRemoteCaptureDevice, mas expor
    // sempre é inofensivo: fica com valores default (desconectado) quando ociosa.
    val sonyTelemetry: StateFlow<com.bragastudio.mobile.core.model.SonyCameraStatus> = sonyRemoteCaptureDevice.telemetry

    // Comandos exclusivos da Sony (não fazem parte da interface genérica CaptureDevice
    // porque nenhuma outra fonte tem "disparar obturador" ou "abertura em f-stop").
    // Guardados por isSonyActive para não disparar rede à toa quando outra fonte
    // está ativa (ex: usuário troca pra Camera mas o singleton continua injetado).
    fun sonyTakePicture() {
        if (captureDevice === sonyRemoteCaptureDevice) sonyRemoteCaptureDevice.takePicture()
    }

    /** Devolve a íris da Sony ao automático (modo de exposição que decide a abertura). */
    fun sonySetIrisAuto() {
        if (captureDevice === sonyRemoteCaptureDevice) sonyRemoteCaptureDevice.setIrisAuto()
    }
    fun sonySetAperture(fNumber: String) {
        if (captureDevice === sonyRemoteCaptureDevice) sonyRemoteCaptureDevice.setAperture(fNumber)
    }

    private val _activeDeviceId = MutableStateFlow(camera2Device.deviceId)

    // Lanterna (torch): transitória — espelha e controla o CaptureDevice ativo.
    // Ao trocar de fonte o valor sempre volta a zero (ver onVideoSettings), para
    // não deixar a lanterna presa num dispositivo que acabou de ser desligado.
    private val _torchEnabled = MutableStateFlow(false)
    val torchEnabled: StateFlow<Boolean> = _torchEnabled.asStateFlow()

    fun setTorchEnabled(enabled: Boolean) {
        captureDevice.setTorchEnabled(enabled)
        _torchEnabled.value = enabled
    }
    val activeDeviceId: StateFlow<String> = _activeDeviceId.asStateFlow()

    // ---------------------------------------------------------------------------------------
    // Helpers de corrotina (M16): try/catch(Throwable) com rethrow de CancellationException.
    // Sequenciais de propósito (NÃO usar collectLatest nos coletores de settings/NDI/BSP: o
    // cancelamento no meio de uma troca deixaria o recurso pela metade).
    // ---------------------------------------------------------------------------------------

    private fun <T> collectGuarded(name: String, flow: Flow<T>, onEach: suspend (T) -> Unit): Job = scope.launch {
        try {
            flow.collect { value ->
                try {
                    onEach(value)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Coletor '$name' falhou ao processar um valor (segue coletando)", t)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Coletor '$name' terminou por erro", t)
        }
    }

    private fun launchGuarded(name: String, block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Tarefa '$name' falhou", t)
        }
    }

    // ---------------------------------------------------------------------------------------
    // BSP
    // ---------------------------------------------------------------------------------------

    suspend fun startBsp(deviceName: String, targetHost: String, resolution: String = "FHD", fps: Int = 30) {
        bspLifecycleMutex.withLock {
            // Âncora de processo ANTES de começar (a Activity ainda está visível).
            requestCaptureService(SessionConsumers(bsp = true))
            // Mesma tabela do NDI/gravação; quadro retrato/paisagem escolhido AGORA e fixo até parar.
            val (baseWidth, baseHeight) = ResolutionTable.stream(resolution)
            val (width, height) = nativeRenderer.chooseOutputFrameSize(baseWidth, baseHeight)

            // Criar o encoder bloqueia (HAL): fora da Main.
            val surface = withContext(Dispatchers.IO) { bspManager.start(deviceName, width, height, fps) }
            if (surface != null) {
                nativeRenderer.setBspSurface(surface, width, height)
                bspManager.connectTo(targetHost, deviceName, width, height, fps)
                Log.i(TAG, "BSP iniciado para '$deviceName' em ${width}x$height, conectando a $targetHost")
            }
        }
    }

    suspend fun stopBsp() {
        bspLifecycleMutex.withLock {
            nativeRenderer.setBspSurface(null)
            delay(100) // mesmo motivo do stopNdi: dar tempo da thread nativa soltar a EGLSurface
            bspManager.stop()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Estado da fonte / perda da fonte durante REC (M15 / L1)
    // ---------------------------------------------------------------------------------------

    private fun observeCurrentDeviceState() {
        stateObserverJob?.cancel()
        val device = captureDevice
        stateObserverJob = collectGuarded("captureState", device.state) { st ->
            _captureState.value = st
            onCaptureStateChanged(st)
        }
    }

    /**
     * Reage a ERROR/IDLE com gravação ativa. Há uma tolerância ([SOURCE_LOSS_GRACE_MS]) porque a
     * reabertura normal da sessão (rotação, troca de lente, retry do Camera2) passa por IDLE/ERROR
     * por instantes; só um estado ruim que PERSISTE é tratado como perda da fonte.
     */
    private fun onCaptureStateChanged(st: CaptureState) {
        // A sensorOrientation só é lida pelo Camera2Device ao abrir a câmera: relê ao ficar pronta.
        if (st == CaptureState.READY) refreshSourceGeometry()
        if (SourceHealth.isHealthy(st)) {
            sourceLossJob?.cancel()
            sourceLossJob = null
            return
        }
        if (recordManager.recState.value !== RecState.Recording) return
        sourceLossJob?.cancel()
        sourceLossJob = launchGuarded("sourceLoss") {
            delay(SOURCE_LOSS_GRACE_MS)
            val recording = recordManager.recState.value === RecState.Recording
            if (SourceHealth.shouldFinalizeAfterGrace(recording, _captureState.value)) {
                handleSourceLost("A fonte de vídeo foi perdida (${_captureState.value}). O arquivo foi finalizado.")
            }
        }
    }

    private suspend fun handleSourceLost(message: String) {
        Log.w(TAG, "Fonte perdida durante a gravação: $message")
        _recordingEvents.tryEmit(RecordingEvent.SourceLost(message))
        stopRecording(StopReason.SOURCE_LOST)
    }

    // ---------------------------------------------------------------------------------------
    // Preview
    // ---------------------------------------------------------------------------------------

    /**
     * Anexa (ou reanexa) a surface de preview. Idempotente:
     *  - sessão nova: abre renderer + câmera;
     *  - sessão viva (REC/NDI/BSP sobreviveram à tela): só encaixa a nova surface no renderer vivo,
     *    SEM reiniciar a câmera; com a mesma surface já anexada, não faz nada.
     */
    suspend fun attachPreviewSurface(surface: Surface) {
        previewLifecycleMutex.withLock {
            Log.i(TAG, "attachPreviewSurface chamado com surface válida: ${surface.isValid}")
            if (!surface.isValid) return

            val wasLive = sessionLive
            val unchanged = previewSurface === surface
            previewSurface = surface
            _previewAttached.value = true

            if (wasLive) {
                if (!unchanged) {
                    val (width, height) = ResolutionTable.recording(currentVideoSettings.resolution)
                    // prepareRenderer com engine vivo só troca a surface de preview e devolve a
                    // surface da câmera existente (nada é recriado).
                    nativeRenderer.prepareRenderer(surface, width, height)
                    Log.i(TAG, "Preview reanexado a uma sessão viva (sem reiniciar a câmera)")
                }
            } else {
                restartSession()
                sessionLive = true
            }
            startScopePolling()

            // Garante a âncora de processo se já há saída ativa (ex.: NDI ligado antes do serviço).
            val consumers = currentConsumers()
            if (consumers.anyActive) requestCaptureService(consumers)

            if (!wasLive) {
                launchGuarded("attachPreviewSurface.apply") {
                    val activeLut = currentActiveLut
                    if (activeLut != null) {
                        val lutData = loadLut(activeLut)
                        if (lutData != null) {
                            nativeRenderer.setLutDataFloat(lutData.floatData, lutData.size)
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
                        focusPeakingSensitivity = currentMonitorSettings.focusPeakingSensitivity,
                    )
                }
            }
        }
    }

    private fun loadLut(lut: com.bragastudio.mobile.core.model.Lut): LutParser.LutData? = if (lut.isBuiltIn) {
        LutParser.parseFromAssets(context, lut.filePath)
    } else {
        LutParser.parseFromFile(java.io.File(lut.filePath))
    }

    /**
     * Solta a surface de preview. Com REC/NDI/BSP ativos só a surface é solta (a sessão segue);
     * sem nenhum consumidor faz o desligamento completo (câmera, renderer; o áudio cai pelo coletor).
     *
     * NonCancellable: se o chamador for cancelado no meio (ex.: PreviewViewModel.onCleared ao sair da
     * tela), a surface PRECISA ficar solta de qualquer forma — senão o GL continuaria desenhando numa
     * surface abandonada — e, no desligamento completo, a câmera não pode ficar presa aberta.
     */
    suspend fun detachPreviewSurface() {
        withContext(NonCancellable) {
            previewLifecycleMutex.withLock {
                previewSurface = null
                _previewAttached.value = false
                nativeRenderer.clearPreviewSurface()
                stopScopePolling()
                delay(100) // dá tempo à thread nativa de parar de usar a surface antes de ela ser liberada
                when (CaptureSessionPolicy.detachAction(currentConsumers())) {
                    DetachAction.RELEASE_PREVIEW_ONLY ->
                        Log.i(TAG, "Preview solto; sessão mantida (${currentConsumers().count} saída(s) ativa(s))")

                    DetachAction.FULL_SHUTDOWN -> shutdownSessionLocked()
                }
            }
        }
    }

    /**
     * Versão para a UI: roda no escopo do grafo (processo), não no do ViewModel — o detach não pode
     * ser perdido porque o ViewModel foi limpo junto com a navegação.
     */
    fun detachPreviewSurfaceAsync(): Job = launchGuarded("detachPreviewSurface") { detachPreviewSurface() }

    /**
     * Desligamento completo da sessão (chamar com o previewLifecycleMutex em mãos).
     * Finaliza o take ANTES de parar a câmera: o encoder recebe os últimos frames e a parada da
     * câmera (estado IDLE) não é confundida com perda de fonte.
     */
    private suspend fun shutdownSessionLocked() {
        withContext(NonCancellable) {
            sessionLive = false
            stopScopePolling()
            if (recordManager.recState.value === RecState.Recording ||
                recordManager.recState.value === RecState.Preparing
            ) {
                stopRecording()
            }
            captureDevice.stop()
            // Destrói o engine nativo de render (GlesEngine) e a thread de render.
            nativeRenderer.release()
            Log.i(TAG, "Sessão de captura encerrada (câmera e renderer liberados)")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Gravação
    // ---------------------------------------------------------------------------------------

    // True enquanto o take atual usa o Surface HDR da câmera (precisa ser destacado no stop).
    @Volatile private var hdrSurfaceAttached = false

    /**
     * Inicia um take. Toda a preparação (SAF, MediaMuxer, MediaCodec) roda em Dispatchers.IO
     * (A6). Chamadas com o RecState fora de Idle (toque duplo) são ignoradas sem tocar em codec.
     */
    suspend fun startRecording(directoryUri: String, videoSettings: VideoSettings) {
        withContext(Dispatchers.IO) {
            if (recordManager.recState.value !== RecState.Idle) {
                Log.w(TAG, "startRecording ignorado: estado = ${recordManager.recState.value}")
                return@withContext
            }
            // Âncora de processo ANTES de preparar o take: a câmera precisa continuar aberta se a
            // Activity for para background (o FGS camera|microphone é o que garante isso).
            requestCaptureService(SessionConsumers(recording = true))
            try {
                startRecordingInternal(directoryUri, videoSettings)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { abortRecordingStart() }
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Falha ao iniciar a gravação", t)
                withContext(NonCancellable) { abortRecordingStart() }
            }
        }
    }

    private suspend fun startRecordingInternal(directoryUri: String, requested: VideoSettings) {
        // O encoder usa o fps que a câmera realmente entrega (quando conhecido), não o pedido.
        val effective = FpsResolver.effective(captureDevice.effectiveFps.value, requested.fps)
        val videoSettings = if (effective != requested.fps) requested.copy(fps = effective) else requested

        // Caminho A (HDR real 10-bit): quando a câmera suporta HLG10 (DPR), o
        // codec é HEVC (Main10) e o HDR está ligado, a câmera grava limpo direto
        // no encoder — sem LUT/False Color/Zebra no arquivo (esses continuam só
        // no preview/monitor). Se o aparelho não tem encoder Main10 ou a HAL recusa a
        // sessão HLG10+SDR, caímos no fallback do caminho GL 8-bit atual.
        var hdrMode = videoSettings.hdrEnabled &&
            videoSettings.codec == "H.265" &&
            captureDevice.supportsTrueHdr

        // Quadro do take (SDR/GL): retrato ou paisagem conforme o conteúdo na orientação do aparelho
        // AGORA; fica fixo até o fim do arquivo (girar depois só gira o conteúdo, com barras).
        val (baseWidth, baseHeight) = ResolutionTable.recording(videoSettings.resolution)
        val (frameWidth, frameHeight) = nativeRenderer.chooseOutputFrameSize(baseWidth, baseHeight)
        val portraitFrame = frameHeight > frameWidth

        var surface = if (hdrMode) {
            recordManager.prepareHdrRecording(directoryUri, videoSettings)
        } else {
            recordManager.prepareRecording(directoryUri, videoSettings, portraitFrame)
        }
        if (surface == null && hdrMode) {
            Log.w(TAG, "Sem encoder Main10 para HDR — gravando SDR via GL (fallback)")
            hdrMode = false
            surface = recordManager.prepareRecording(directoryUri, videoSettings, portraitFrame)
        }
        if (surface == null) return // o RecordManager já reportou o motivo (ou ignorou o toque duplo)

        if (hdrMode) {
            hdrSurfaceAttached = true
            val attached = captureDevice.setCameraHdrSurface(surface)
            if (!attached) {
                Log.w(TAG, "HDR10 recusado pela HAL — gravando SDR via GL (fallback)")
                captureDevice.setCameraHdrSurface(null)
                hdrSurfaceAttached = false
                recordManager.cancelPreparation()
                val sdrSurface = recordManager.prepareRecording(directoryUri, videoSettings, portraitFrame) ?: return
                nativeRenderer.setRecordSurface(sdrSurface, frameWidth, frameHeight)
            }
        } else {
            nativeRenderer.setRecordSurface(surface, frameWidth, frameHeight)
        }

        delay(200) // dá tempo da superfície ser atrelada antes do primeiro frame
        if (!recordManager.startRecording()) {
            // O RecordManager já liberou codecs/arquivo e reportou; só desfazemos o encaixe.
            detachRecordSurfaces()
        }
    }

    /** Desfaz o encaixe das surfaces de gravação (HDR da câmera e do GL). */
    private suspend fun detachRecordSurfaces() {
        if (hdrSurfaceAttached) {
            try {
                captureDevice.setCameraHdrSurface(null)
            } catch (t: Throwable) {
                Log.w(TAG, "Falha ao destacar surface HDR", t)
            }
            hdrSurfaceAttached = false
        }
        nativeRenderer.setRecordSurface(null)
    }

    /** Limpeza após falha/cancelamento do start: surfaces fora e preparação cancelada. */
    private suspend fun abortRecordingStart() {
        detachRecordSurfaces()
        delay(100)
        recordManager.cancelPreparation()
    }

    /**
     * Finaliza o take. Destaca o surface HDR da câmera primeiro (reconfigura a sessão para
     * só-preview), depois o do GL — ambos precisam estar soltos antes do codec parar para o
     * input surface ser liberado com segurança. Roda em IO + NonCancellable e NÃO espera a cópia
     * SAF (que corre em segundo plano no RecordManager).
     */
    suspend fun stopRecording(reason: StopReason = StopReason.USER) {
        withContext(Dispatchers.IO + NonCancellable) {
            when (recordManager.recState.value) {
                RecState.Recording -> {
                    detachRecordSurfaces()
                    delay(100) // Small delay to prevent crashing if the C++ thread is drawing
                    recordManager.stopRecording(reason)
                }

                RecState.Preparing -> abortRecordingStart()

                else -> Unit // Idle ou Stopping: nada a fazer (toque duplo)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // NDI
    // ---------------------------------------------------------------------------------------

    suspend fun startNdi(cameraName: String, resolution: String = "FHD") {
        ndiLifecycleMutex.withLock { startNdiLocked(cameraName, resolution) }
    }

    suspend fun stopNdi() {
        ndiLifecycleMutex.withLock { stopNdiLocked() }
    }

    /** Reinício serializado do sender (mudança de nome/resolução com o NDI ligado). */
    private suspend fun restartNdi(cameraName: String, resolution: String) {
        ndiLifecycleMutex.withLock {
            stopNdiLocked()
            startNdiLocked(cameraName, resolution)
        }
    }

    private suspend fun startNdiLocked(cameraName: String, resolution: String) {
        if (ndiManager.isNdiActive.value) return
        // Âncora de processo ANTES de começar (a Activity ainda está visível).
        requestCaptureService(SessionConsumers(ndi = true))
        if (!ndiManager.startNdi(cameraName)) return

        try {
            ndiHandlerThread = HandlerThread("BDSM-NDI-Thread").apply { start() }
            ndiHandler = Handler(ndiHandlerThread!!.looper)

            // Resolução da transmissão NDI (separada de VideoSettings.resolution, que é só da gravação).
            // O quadro (retrato/paisagem) é escolhido na partida e fica fixo: o ImageReader não é
            // recriado ao girar o aparelho; o conteúdo gira e é encaixado com barras (compatível
            // com OBS/DistroAV, sem mudança de resolução em tempo real).
            val (baseNdiWidth, baseNdiHeight) = ResolutionTable.stream(resolution)
            val (ndiWidth, ndiHeight) = nativeRenderer.chooseOutputFrameSize(baseNdiWidth, baseNdiHeight)

            // A sobrecarga com 'usage' só existe a partir da API 29; no Android 8-9
            // (minSdk 26) usamos a sobrecarga clássica, sem NoSuchMethodError.
            ndiImageReader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ImageReader.newInstance(
                    ndiWidth, ndiHeight,
                    PixelFormat.RGBA_8888,
                    3,
                    android.hardware.HardwareBuffer.USAGE_CPU_READ_OFTEN or android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
                )
            } else {
                ImageReader.newInstance(ndiWidth, ndiHeight, PixelFormat.RGBA_8888, 3)
            }
            ndiImageReader?.setOnImageAvailableListener({ reader ->
                // acquireLatestImage: se o envio atrasou, descarta frames velhos em vez de
                // enfileirá-los (menos latência e sem backpressure no GL).
                val image = try {
                    reader.acquireLatestImage()
                } catch (e: Exception) {
                    null
                }
                if (image != null) {
                    try {
                        ndiManager.feedImage(image)
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro ao processar imagem NDI: ${e.message}")
                    } finally {
                        try {
                            image.close()
                        } catch (e: Exception) {
                            // Ignora se a imagem já foi fechada
                        }
                    }
                }
            }, ndiHandler)

            val surf = ndiImageReader?.surface
            if (surf != null) {
                nativeRenderer.setNdiSurface(surf, ndiWidth, ndiHeight)
                activeNdiResolution = resolution
                Log.i(TAG, "NDI Surface atrelado com sucesso para a câmera '$cameraName' em ${ndiWidth}x$ndiHeight")
            }
            linkTelemetry.setNdi(ndiManager.activeName)
        } catch (t: Throwable) {
            Log.e(TAG, "Falha ao montar o pipeline NDI", t)
            withContext(NonCancellable) { stopNdiLocked() }
            if (t is CancellationException) throw t
        }
    }

    private suspend fun stopNdiLocked() {
        nativeRenderer.setNdiSurface(null)
        delay(100) // Delay de segurança para a thread nativa soltar a EGLSurface
        ndiManager.stopNdi()
        linkTelemetry.setNdi(null)
        activeNdiResolution = null

        try {
            ndiImageReader?.setOnImageAvailableListener(null, null)
            ndiImageReader?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Ao fechar o ImageReader do NDI", e)
        }
        ndiImageReader = null

        ndiHandlerThread?.quitSafely()
        try {
            ndiHandlerThread?.join()
        } catch (e: Exception) {
            // ignora
        }
        ndiHandlerThread = null
        ndiHandler = null
    }

    // ---------------------------------------------------------------------------------------
    // Sessão de captura
    // ---------------------------------------------------------------------------------------

    // NÃO usar withLock aqui: chamado de dentro de attachPreviewSurface/restartPreview (que já
    // seguram o previewLifecycleMutex, não reentrante) e do coletor de videoSettings.
    private suspend fun restartSession() {
        // Pode ser nulo: sessão viva sem tela (REC/NDI/BSP em segundo plano) após troca de fonte.
        val surface = previewSurface

        val (width, height) = ResolutionTable.recording(currentVideoSettings.resolution)

        val bestSize = captureDevice.getBestSupportedSize(width, height) ?: Pair(width, height)
        sourceBufferSize = bestSize
        refreshSourceGeometry()

        val camSurface = nativeRenderer.prepareRenderer(surface, bestSize.first, bestSize.second)

        if (camSurface != null) {
            captureDevice.start(camSurface)
        } else if (surface != null) {
            captureDevice.start(surface)
        }
        refreshSourceGeometry()
    }

    /** Reabre a sessão de preview com a surface atual (se ainda válida). Usado
     *  pelo overlay de erro — botão "Tentar novamente" — como defesa quando o
     *  retry automático do Camera2Device esgota as tentativas (ex.: falha
     *  transitória durante rotação/recriação da Activity). */
    suspend fun restartPreview() {
        previewLifecycleMutex.withLock {
            if (previewSurface == null && !sessionLive) return
            restartSession()
        }
    }

    private fun startScopePolling() {
        if (isScopeRunning) return
        isScopeRunning = true
        scopePollingJob = launchGuarded("scopePolling") {
            while (isScopeRunning && currentCoroutineContext().isActive) {
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
        scopePollingJob?.cancel()
        scopePollingJob = null
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

    @Volatile private var isLutLoaded = false

    // A LUT só é ligada sozinha na PRIMEIRA carga bem-sucedida; depois disso o estado é do usuário
    // (religar após ele desligar seria uma surpresa). Reinicia quando a LUT ativa é removida.
    @Volatile private var lutAutoEnabled = false

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

        Log.d(TAG, "🎯 Focus Peaking: ${_isFocusPeakingEnabled.value} | Cor: ${currentMonitorSettings.focusPeakingColor} | Sens: ${currentMonitorSettings.focusPeakingSensitivity}")

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
            focusPeakingSensitivity = currentMonitorSettings.focusPeakingSensitivity,
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
        Log.d("LUT_TRACE", "5. MediaGraph.applyLut chamado. Size: ${lutData.size}")
        launchGuarded("applyLut") {
            nativeRenderer.setLutDataFloat(lutData.floatData, lutData.size)
            isLutLoaded = true
            if (!lutAutoEnabled) {
                lutAutoEnabled = true
                _isLutEnabled.value = true
            }
            nativeRenderer.updateLutEnabled(_isLutEnabled.value)
            Log.d("LUT_TRACE", "6. NativeRenderer atualizado: LUT Enabled = ${_isLutEnabled.value}")
        }
    }

    fun disableLut() {
        lutAutoEnabled = false
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

    suspend fun takeSnapshot(): android.graphics.Bitmap? {
        nativeRenderer.requestSnapshot()
        var attempts = 0
        while (attempts < 50) {
            val bmp = nativeRenderer.getSnapshot()
            if (bmp != null) return bmp
            delay(16)
            attempts++
        }
        return null
    }

    // ---------------------------------------------------------------------------------------
    // Configuração de captura (RES/FPS)
    // ---------------------------------------------------------------------------------------

    /**
     * Aplica resolução/FPS ao dispositivo ativo (FPS vale ao vivo; a resolução é validada pela fonte e
     * o tamanho da sessão segue o VideoSettings persistido). Durante o REC é ignorado, com aviso.
     */
    fun configureCapture(resolution: String, fps: Int) {
        if (recordManager.recState.value !== RecState.Idle) {
            Log.w(TAG, "configureCapture ignorado durante a gravação ($resolution @ $fps)")
            graphMessages.tryEmit("Não é possível alterar resolução/FPS durante a gravação.")
            return
        }
        applyCaptureConfig(resolution, fps)
    }

    private fun applyCaptureConfig(resolution: String, fps: Int) {
        _configuredFps.value = fps
        val explicit = Regex("\\d+\\s*[xX]\\s*\\d+").matches(resolution.trim())
        val sizeLabel = if (explicit) {
            resolution.trim()
        } else {
            ResolutionTable.recording(resolution).let { "${it.first}x${it.second}" }
        }
        captureDevice.configure(sizeLabel, fps)
    }

    // ---------------------------------------------------------------------------------------
    // Telemetria do Link
    // ---------------------------------------------------------------------------------------

    /** A UI informa a lente ativa (ex.: "Principal 1x") para o dashboard do Link; null = nome do device. */
    fun reportActiveLens(label: String?) {
        activeLensLabel = label
        publishCaptureInfo()
    }

    private fun publishCaptureInfo() {
        try {
            val settings = currentVideoSettings
            val source = when (settings.videoSource) {
                "USB" -> "USB"
                "SONY" -> "Sony"
                else -> "Câmera"
            }
            val lens = activeLensLabel
                ?: activeLenses.value.singleOrNull()?.name
                ?: captureDevice.deviceName
            linkTelemetry.setCaptureInfo(source, lens, effectiveFps.value)
        } catch (t: Throwable) {
            Log.w(TAG, "Falha ao publicar telemetria de captura", t)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Inicialização dos coletores. DEVE ficar no FIM da classe: os coletores tocam nas
    // propriedades declaradas acima (StateFlows, mutexes), que precisam estar inicializadas (M16).
    // ---------------------------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun startCollectors() {
        observeCurrentDeviceState()

        // Eventos do RecordManager: repassa à UI e finaliza o take em erro fatal (disco/encoder).
        collectGuarded("recordEvents", recordManager.events) { event ->
            _recordingEvents.tryEmit(event)
            when (event) {
                is RecordingEvent.DiskFull -> stopRecording(StopReason.DISK_FULL)
                is RecordingEvent.EncoderError -> stopRecording(StopReason.ENCODER_ERROR)
                else -> Unit
            }
        }

        // Sessão desacoplada: sobe o FGS quando o primeiro consumidor fica ativo; quando o último
        // termina (e não há preview), desliga a sessão inteira (câmera, renderer).
        collectGuarded("captureSession", foregroundWanted) { wanted ->
            if (wanted) {
                requestCaptureService(currentConsumers(), force = true)
            } else {
                previewLifecycleMutex.withLock {
                    if (CaptureSessionPolicy.shouldShutdownSession(currentConsumers(), previewSurface != null, sessionLive)) {
                        Log.i(TAG, "Último consumidor terminou sem preview: desligando a sessão")
                        shutdownSessionLocked()
                    }
                }
            }
        }

        // Áudio: capturado enquanto REC, NDI com áudio ou o VU do Preview precisarem (contagem de referência).
        collectGuarded("ndiAudioFlag", settingsRepository.ndiSettings.map { it.isAudioEnabled }.distinctUntilChanged()) {
            _ndiAudioEnabled.value = it
        }
        collectGuarded(
            "audioDemand",
            combine(sessionConsumers, _previewAttached, _ndiAudioEnabled, audioManagerService.selectedDevice) { c, preview, ndiAudio, device ->
                val needed = CaptureSessionPolicy.audioNeeded(c, preview, ndiAudio)
                AudioPlan(needed, if (needed) device else null)
            }.distinctUntilChanged(),
        ) { plan ->
            if (plan.needed) {
                audioCaptureService.addAudioSink(audioSink)
                audioCaptureService.startCapture(plan.device)
            } else {
                audioCaptureService.removeAudioSink(audioSink)
                audioCaptureService.stopCapture()
            }
        }

        // Telemetria: REC real e microfone selecionado.
        collectGuarded("telemetryRecording", recordManager.isRecording) { linkTelemetry.setRecording(it) }
        collectGuarded("telemetryMic", audioManagerService.selectedDevice) { device ->
            linkTelemetry.setMicrophone(device?.let { audioManagerService.getDeviceName(it) } ?: "--")
        }
        publishCaptureInfo()

        // Lente/fps efetivos mudam (troca de lente/fonte, HAL limita o fps): atualiza o dashboard do Link.
        collectGuarded("telemetryLenses", activeLenses) { publishCaptureInfo() }
        collectGuarded("telemetryFps", effectiveFps) { eff ->
            ndiManager.setTargetFps(eff)
            val (num, den) = FpsResolver.ndiFrameRate(eff)
            nativeRenderer.setNdiFrameRate(num, den)
            publishCaptureInfo()
        }

        // M18: passe de render do NDI só roda com receptores (NdiManager mantém true se a consulta falhar).
        collectGuarded("ndiReceivers", ndiManager.hasReceivers) {
            nativeRenderer.setNdiHasReceivers(it)
        }

        // M45: intensidade da LUT vinda das configurações.
        collectGuarded("lutIntensity", settingsRepository.lutIntensity.distinctUntilChanged()) {
            nativeRenderer.setLutIntensity(it)
        }

        // Coleta Video Settings
        collectGuarded("videoSettings", settingsRepository.videoSettings.distinctUntilChanged()) { settings ->
            val previousSource = currentVideoSettings.videoSource
            currentVideoSettings = settings
            ndiManager.setTargetFps(settings.fps) // fps de fonte para o drop% do NDI
            // Aplica RES/FPS persistidos ao dispositivo (fora do REC; durante o REC mantém o take intacto).
            if (recordManager.recState.value === RecState.Idle) {
                applyCaptureConfig(settings.resolution, settings.fps)
            }

            // Aplica os controles avançados persistidos (estabilização/HDR)
            // ao dispositivo ativo. Idempotente e barato: a cada emissão o
            // CaptureDevice re-aplica e atualiza o CaptureRequest.
            captureDevice.setVideoStabilizationEnabled(settings.stabilizationEnabled)
            captureDevice.setHdrEnabled(settings.hdrEnabled)

            if (previousSource != settings.videoSource) {
                // Trocar de fonte com um take em andamento: finaliza o arquivo com segurança antes.
                if (recordManager.recState.value === RecState.Recording) {
                    handleSourceLost("A fonte de vídeo foi alterada durante a gravação. O arquivo foi finalizado.")
                }
                captureDevice.stop()
                captureDevice = when (settings.videoSource) {
                    "USB" -> uvcCaptureDevice
                    "SONY" -> sonyRemoteCaptureDevice
                    else -> camera2Device
                }
                activeLensLabel = null
                _activeDeviceId.value = captureDevice.deviceId
                // Ao trocar de fonte: torch sempre desligado no sensor novo e
                // os estados avançados persistidos reaplicados imediatamente.
                captureDevice.setTorchEnabled(false)
                _torchEnabled.value = false
                captureDevice.setVideoStabilizationEnabled(settings.stabilizationEnabled)
                captureDevice.setHdrEnabled(settings.hdrEnabled)
                observeCurrentDeviceState()
                if (recordManager.recState.value === RecState.Idle) {
                    applyCaptureConfig(settings.resolution, settings.fps)
                }
                if (sessionLive) {
                    restartSession()
                }
            }
            publishCaptureInfo()
        }

        // ✅ Coleta Monitor Settings: uso síncrono + repasse ao GL em tempo real (M19).
        collectGuarded("monitorSettings", settingsRepository.monitorSettings.distinctUntilChanged()) { settings ->
            currentMonitorSettings = settings
            nativeRenderer.updateMonitorParams(
                settings.zebraThreshold,
                settings.focusPeakingColor,
                settings.focusPeakingSensitivity,
            )
        }

        // ✅ Coleta LUT ativa e aplica no motor nativo automaticamente
        collectGuarded("activeLut", lutRepository.getActiveLut()) { activeLut ->
            currentActiveLut = activeLut
            if (activeLut != null) {
                val lutData = loadLut(activeLut)
                if (lutData != null) {
                    applyLut(lutData)
                } else {
                    disableLut()
                }
            } else {
                disableLut()
            }
        }

        // ✅ Coleta NDI Settings e controla inicialização/finalização do NDI centralmente.
        // Mudança de nome/resolução com o NDI ligado reinicia o sender, serializado e com debounce
        // (o transformLatest só atrasa/descarta valores intermediários de digitação; o start/stop
        // acontece no coletor sequencial abaixo, nunca cancelado no meio).
        collectGuarded(
            "ndiSettings",
            settingsRepository.ndiSettings
                .distinctUntilChanged()
                .transformLatest { settings ->
                    val renaming = settings.isEnabled && ndiManager.isNdiActive.value &&
                        (normalizedNdiName(settings.cameraName) != ndiManager.activeName)
                    if (renaming) delay(NDI_RENAME_DEBOUNCE_MS)
                    emit(settings)
                },
        ) { settings ->
            val isNdiActive = ndiManager.isNdiActive.value
            val name = normalizedNdiName(settings.cameraName)
            when {
                settings.isEnabled && !isNdiActive -> startNdi(name, settings.resolution)

                !settings.isEnabled && isNdiActive -> stopNdi()

                settings.isEnabled && isNdiActive &&
                    (name != ndiManager.activeName || settings.resolution != activeNdiResolution) ->
                    restartNdi(name, settings.resolution)
            }
        }

        // ✅ Coleta BSP Settings — mesmo padrão do NDI acima. Os dois convivem
        // (nenhuma exclusividade forçada aqui); quem decide "só um de cada vez"
        // é a tela de dev (DiagnosticsScreen), que desliga um antes de ligar o
        // outro via setBspEnabled/setNdiEnabled.
        collectGuarded("bspSettings", settingsRepository.bspSettings.distinctUntilChanged()) { settings ->
            val isBspActive = bspManager.isBspActive.value
            if (settings.isEnabled && !isBspActive) {
                val cameraName = settings.cameraName.takeIf { it.isNotBlank() } ?: "BDSM - CAM"
                if (settings.targetHost.isBlank()) {
                    Log.w(TAG, "BSP habilitado mas targetHost está vazio — não iniciando")
                } else {
                    startBsp(cameraName, settings.targetHost, settings.resolution, settings.fps)
                }
            } else if (!settings.isEnabled && isBspActive) {
                stopBsp()
            }
        }
    }

    private fun normalizedNdiName(raw: String): String = com.bragastudio.mobile.core.domain.NdiNaming.sourceName(
        raw,
        com.bragastudio.mobile.core.domain.NdiNaming.deviceName(context),
    )

    init {
        Log.d(TAG, "MediaGraph inicializado (Hub OpenGL)")
        startCollectors()
    }
}
