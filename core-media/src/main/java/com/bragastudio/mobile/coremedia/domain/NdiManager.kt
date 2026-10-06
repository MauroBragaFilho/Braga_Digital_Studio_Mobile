package com.bragastudio.mobile.coremedia.domain

import android.content.Context
import android.graphics.PixelFormat
import android.media.Image
import android.net.wifi.WifiManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class NdiManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : StreamOutput {

    private val _isNdiActive = MutableStateFlow(false)
    val isNdiActive: StateFlow<Boolean> = _isNdiActive.asStateFlow()
    override val isActive: StateFlow<Boolean> get() = isNdiActive

    /**
     * ESTIMATIVA do bitrate NDI na rede (Mbps), não uma medição: o SDK não expõe os bytes
     * realmente enviados (NDIlib_send_get_performance não existe no SDK embarcado). Calculada
     * como pixels/s reais x [ESTIMATED_WIRE_BITS_PER_PIXEL] (ordem de grandeza do SpeedHQ) +
     * áudio. A UI deve rotular como "~ estimado". O valor cru RGBA entregue ao SDK (~2 Gbps em
     * 1080p30, que antes era exibido como "bitrate") está em [ndiRawInputMbps].
     */
    private val _ndiBitrateMbps = MutableStateFlow(0)
    val ndiBitrateMbps: StateFlow<Int> = _ndiBitrateMbps.asStateFlow()

    /** Sempre true: [ndiBitrateMbps] é estimativa. Existe para a UI não esquecer o rótulo. */
    val ndiBitrateIsEstimate: Boolean = true

    /** Taxa de dados RGBA crua entregue ao SDK (Mbps) - medida, mas NÃO é o que vai para a rede. */
    private val _ndiRawInputMbps = MutableStateFlow(0)
    val ndiRawInputMbps: StateFlow<Int> = _ndiRawInputMbps.asStateFlow()

    /** FPS realmente enviado ao SDK no último segundo (medido). */
    private val _ndiFps = MutableStateFlow(0f)
    val ndiFps: StateFlow<Float> = _ndiFps.asStateFlow()

    private val _ndiLatencyMs = MutableStateFlow(0)
    val ndiLatencyMs: StateFlow<Int> = _ndiLatencyMs.asStateFlow()

    private val _ndiFrameDropPct = MutableStateFlow(0f)
    val ndiFrameDropPct: StateFlow<Float> = _ndiFrameDropPct.asStateFlow()

    // Quantos receptores NDI estão conectados agora (0 = nenhum monitor/app
    // recebendo o stream ainda, mesmo com a transmissão ligada).
    private val _connectionCount = MutableStateFlow(0)
    val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()

    // M18: há receptores? true por padrão; se a consulta ao SDK falhar, continua true (o passe de
    // render do NDI fica ativo em vez de congelar a imagem por engano).
    private val _hasReceivers = MutableStateFlow(true)
    val hasReceivers: StateFlow<Boolean> = _hasReceivers.asStateFlow()

    // Última resolução real que foi de fato enviada em sendFrameRgba (o que
    // está sendo transmitido agora), não a preferência salva no DataStore —
    // evita mostrar um valor que ainda não bateu no encoder.
    private val _lastSentResolution = MutableStateFlow<Pair<Int, Int>?>(null)
    val lastSentResolution: StateFlow<Pair<Int, Int>?> = _lastSentResolution.asStateFlow()

    private var ndiName: String = ""

    /** Nome com que o sender está (ou esteve por último) publicado. */
    val activeName: String get() = ndiName
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var nativeLibrariesLoaded = false

    // Mesmo padrão do RecordManager: evento pontual de erro pra UI mostrar um
    // Snackbar/Toast, sem reabrir o mesmo erro pra quem se inscreve depois.
    private val _errorEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val errorEvents: kotlinx.coroutines.flow.SharedFlow<String> = _errorEvents

    private fun reportError(userMessage: String, throwable: Throwable? = null) {
        Log.e("NdiManager", userMessage, throwable)
        _errorEvents.tryEmit(userMessage)
    }

    // Metrics tracking
    private val bytesSentInWindow = AtomicLong(0)
    private val videoPixelsInWindow = AtomicLong(0)
    private val audioBytesInWindow = AtomicLong(0)
    private val framesSentInWindow = AtomicInteger(0)
    private val recentLatencyMs = AtomicLong(0)
    private val metricsScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var metricsJob: Job? = null

    // FPS alvo da fonte (câmera). O sender NDI é clockado em NDI_SENDER_FPS, então o fps
    // ESPERADO para calcular drop% é min(alvo, clock) - antes usava o fps da gravação, que
    // nada tem a ver com a transmissão (60 fps na gravação gerava "50% de drop" fantasma).
    @Volatile private var targetFps = 30f

    init {
        try {
            System.loadLibrary("ndi")
            System.loadLibrary("bdsm-media")
            nativeLibrariesLoaded = true
        } catch (e: Exception) {
            // Não emitimos pela SharedFlow aqui: este init roda na criação do grafo de
            // injeção de dependências, antes de qualquer tela estar coletando o flow,
            // então o evento se perderia. O startNdi() abaixo re-checa essa flag e
            // notifica a UI no momento em que o operador realmente tenta ligar o NDI.
            Log.e("NdiManager", "Falha ao carregar bibliotecas nativas do NDI", e)
        }
    }

    private external fun nativeSetConfigDir(dir: String)
    private external fun initNDI(name: String): Boolean
    private external fun sendFrameRgba(rgbaBuffer: ByteBuffer, width: Int, height: Int, rowStride: Int)
    private external fun sendAudioFrame(pcmData: ByteArray, numSamples: Int, numChannels: Int, sampleRate: Int)
    private external fun stopNDI()

    // Consulta real ao SDK (NDIlib_send_get_no_connections) — quantos
    // receptores/monitores estão de fato conectados agora neste sender.
    private external fun getConnectionCount(): Int

    private companion object {
        /** O sender NDI nativo é clockado a 30 fps (NdiEngine): é o teto do fps esperado. */
        const val NDI_SENDER_FPS = 30f

        /** Ordem de grandeza do SpeedHQ em Full NDI (~2 bits/pixel, ~125 Mbps em 1080p30). Estimativa. */
        const val ESTIMATED_WIRE_BITS_PER_PIXEL = 2.0
    }

    /**
     * Fixa o nome da máquina NDI em "BDSM" (em vez do "localhost" do Android) escrevendo o
     * ndi-config.v1.json e apontando NDI_CONFIG_DIR para ele antes do NDIlib_initialize.
     */
    private fun applyMachineName() {
        try {
            val dir = java.io.File(context.filesDir, "ndi").apply { mkdirs() }
            java.io.File(dir, "ndi-config.v1.json")
                .writeText(com.bragastudio.mobile.core.domain.NdiNaming.configJson())
            nativeSetConfigDir(dir.absolutePath)
        } catch (t: Throwable) {
            Log.w("NdiManager", "Não foi possível definir o nome da máquina NDI: ${t.message}")
        }
    }

    fun startNdi(cameraName: String = ""): Boolean {
        if (_isNdiActive.value) return true

        if (!nativeLibrariesLoaded) {
            reportError("NDI indisponível: bibliotecas nativas não carregaram neste dispositivo.")
            return false
        }

        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("bdsm_ndi_multicast_lock")
            multicastLock?.setReferenceCounted(true)
            multicastLock?.acquire()

            @Suppress("DEPRECATION")
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "bdsm_ndi_wifi_lock")
            wifiLock?.setReferenceCounted(true)
            wifiLock?.acquire()
        } catch (t: Throwable) {
            Log.e("NdiManager", "Falha ao adquirir MulticastLock/WifiLock: ${t.message}")
        }

        ndiName = com.bragastudio.mobile.core.domain.NdiNaming.sourceName(
            cameraName,
            com.bragastudio.mobile.core.domain.NdiNaming.deviceName(context),
        )
        applyMachineName()
        val success = initNDI(ndiName)
        if (success) {
            _isNdiActive.value = true
            startMetricsTracking()
        } else {
            try {
                if (multicastLock?.isHeld == true) multicastLock?.release()
            } catch (t: Throwable) {}
            try {
                if (wifiLock?.isHeld == true) wifiLock?.release()
            } catch (t: Throwable) {}
            reportError("Falha ao iniciar o streaming NDI. Verifique a conexão de rede e tente novamente.")
        }
        return success
    }

    override suspend fun stop() = stopNdi()

    fun stopNdi() {
        if (!_isNdiActive.value) return
        // Marca inativo ANTES de parar o nativo: feedImage/feedAudio (outras threads) passam a
        // retornar cedo e não chamam o SDK enquanto ele é destruído.
        _isNdiActive.value = false
        try {
            stopNDI()
        } catch (t: Throwable) {
            Log.e("NdiManager", "Erro ao parar o sender NDI", t)
        }
        stopMetricsTracking()
        _lastSentResolution.value = null

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (t: Throwable) {}
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (t: Throwable) {}
    }

    fun feedImage(image: Image) {
        if (!_isNdiActive.value) return

        try {
            val planes = image.planes
            if (planes.isEmpty()) return

            val plane = planes[0]
            val buffer = plane.buffer

            // Verificamos se o buffer eh direto
            if (!buffer.isDirect) {
                Log.e("NdiManager", "Buffer de imagem nao eh direto (isDirect = false)")
                return
            }

            val startTime = System.currentTimeMillis()

            sendFrameRgba(
                buffer,
                image.width, image.height,
                plane.rowStride,
            )

            // Guarda a resolução do frame que acabou de ser enviado de fato —
            // é o dado real, em vez de assumir que bateu com a preferência salva.
            val current = _lastSentResolution.value
            if (current == null || current.first != image.width || current.second != image.height) {
                _lastSentResolution.value = image.width to image.height
            }

            val latency = System.currentTimeMillis() - startTime
            recentLatencyMs.set(latency)

            // Track metrics
            val pixels = image.width.toLong() * image.height.toLong()
            bytesSentInWindow.addAndGet(pixels * 4L) // RGBA cru entregue ao SDK
            videoPixelsInWindow.addAndGet(pixels)
            framesSentInWindow.incrementAndGet()
        } catch (e: Exception) {
            Log.e("NdiManager", "Erro ao processar feedImage para o NDI: ", e)
        }
    }

    fun feedAudio(pcmData: ByteArray, numSamples: Int, numChannels: Int, sampleRate: Int) {
        if (!_isNdiActive.value) return
        try {
            sendAudioFrame(pcmData, numSamples, numChannels, sampleRate)
            // Track audio bytes (16-bit PCM)
            val audioBytes = numSamples.toLong() * numChannels * 2L
            audioBytesInWindow.addAndGet(audioBytes)
        } catch (e: Exception) {
            Log.e("NdiManager", "Erro ao enviar audio para o NDI: ", e)
        }
    }

    // Configura o fps alvo da fonte para calcular o frame drop (limitado ao clock do sender).
    fun setTargetFps(fps: Int) {
        targetFps = fps.coerceAtLeast(1).toFloat()
    }

    private fun startMetricsTracking() {
        metricsJob?.cancel()
        bytesSentInWindow.set(0)
        videoPixelsInWindow.set(0)
        audioBytesInWindow.set(0)
        framesSentInWindow.set(0)
        metricsJob = metricsScope.launch {
            var lastTickNs = System.nanoTime()
            try {
                while (isActive && _isNdiActive.value) {
                    delay(1000)
                    // Janela real (o delay pode atrasar): todas as taxas dividem pelo tempo medido.
                    val nowNs = System.nanoTime()
                    val seconds = ((nowNs - lastTickNs) / 1_000_000_000.0).coerceAtLeast(0.001)
                    lastTickNs = nowNs

                    val rawBytes = bytesSentInWindow.getAndSet(0)
                    val pixels = videoPixelsInWindow.getAndSet(0)
                    val audioBytes = audioBytesInWindow.getAndSet(0)
                    val frames = framesSentInWindow.getAndSet(0)

                    val measuredFps = (frames / seconds).toFloat()
                    _ndiFps.value = measuredFps
                    _ndiRawInputMbps.value = ((rawBytes * 8.0) / seconds / 1_000_000.0).toInt()
                    // Estimativa do que vai para a rede (ver KDoc de ndiBitrateMbps).
                    val videoMbps = pixels / seconds * ESTIMATED_WIRE_BITS_PER_PIXEL / 1_000_000.0
                    val audioMbps = audioBytes * 8.0 / seconds / 1_000_000.0
                    _ndiBitrateMbps.value = (videoMbps + audioMbps).toInt()

                    // Latência de envio (tempo dentro de sendFrameRgba), não latência ponta a ponta.
                    _ndiLatencyMs.value = recentLatencyMs.get().toInt()

                    // Frame drop contra o fps esperado de verdade (min entre fonte e clock do sender).
                    val expectedFps = minOf(targetFps, NDI_SENDER_FPS)
                    val dropRatio = 1f - (measuredFps / expectedFps)
                    _ndiFrameDropPct.value = if (dropRatio > 0f) (dropRatio * 100f).coerceAtMost(100f) else 0f

                    // Conexões reais (consulta ao SDK, não-bloqueante).
                    val count = try {
                        getConnectionCount()
                    } catch (e: Exception) {
                        -1
                    }
                    _connectionCount.value = count.coerceAtLeast(0)
                    _hasReceivers.value = count < 0 || count > 0
                }
            } finally {
                // Zera ao terminar (inclusive quando cancelado pelo stopNdi).
                _ndiBitrateMbps.value = 0
                _ndiRawInputMbps.value = 0
                _ndiFps.value = 0f
                _ndiLatencyMs.value = 0
                _ndiFrameDropPct.value = 0f
                _connectionCount.value = 0
                _hasReceivers.value = true
            }
        }
    }

    private fun stopMetricsTracking() {
        metricsJob?.cancel()
        metricsJob = null
        _ndiBitrateMbps.value = 0
        _ndiRawInputMbps.value = 0
        _ndiFps.value = 0f
        _ndiLatencyMs.value = 0
        _ndiFrameDropPct.value = 0f
        _connectionCount.value = 0
        _hasReceivers.value = true
    }
}
