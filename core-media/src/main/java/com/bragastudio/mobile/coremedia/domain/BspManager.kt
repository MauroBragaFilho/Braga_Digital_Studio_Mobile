package com.bragastudio.mobile.coremedia.domain

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.bragastudio.mobile.coremedia.bsp.BspConnectionState
import com.bragastudio.mobile.coremedia.bsp.BspControlChannel
import com.bragastudio.mobile.coremedia.bsp.H264RtpPacketizer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gerencia a transmissão via BSP (Braga Stream Protocol): encoda o preview
 * renderizado (mesma Surface pattern usada pelo RecordManager, só que sem
 * MediaMuxer) em H.264 e envia em RTP/UDP para o IP do computador receptor.
 *
 * Espelha a API pública do NdiManager de propósito — o MediaGraph liga/desliga BSP com a
 * mesma forma que liga/desliga NDI (startBsp/stopBsp).
 *
 * Ciclo de vida (M21): [start] cria encoder/socket; [stop] (suspend) para tudo NA ORDEM:
 * canal de controle → jobs (cancelAndJoin) → codec/surface/socket. Nenhum recurso é liberado
 * enquanto uma corrotina ainda o usa. A serialização start/stop entre chamadores é feita pelo
 * MediaGraph (mutex de ciclo de vida do BSP).
 */
@Singleton
class BspManager @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
) : StreamOutput {
    companion object {
        private const val TAG = "BspManager"
        private const val CONTROL_PORT = 7070 // porta TCP fixa do handshake/controle
        private const val RTP_PORT = 7071     // porta UDP fixa do vídeo (MVP: sem negociação dinâmica ainda)
        private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    private val _isBspActive = MutableStateFlow(false)
    val isBspActive: StateFlow<Boolean> = _isBspActive.asStateFlow()
    override val isActive: StateFlow<Boolean> get() = isBspActive

    private val _connectionState = MutableStateFlow(BspConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BspConnectionState> = _connectionState.asStateFlow()

    private val _rttMs = MutableStateFlow(0L)
    val rttMs: StateFlow<Long> = _rttMs.asStateFlow()

    private val _bitrateMbps = MutableStateFlow(0f)
    val bitrateMbps: StateFlow<Float> = _bitrateMbps.asStateFlow()

    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _packetLossPercent = MutableStateFlow(0f)
    val packetLossPercent: StateFlow<Float> = _packetLossPercent.asStateFlow()

    private val _errorEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val errorEvents: kotlinx.coroutines.flow.SharedFlow<String> = _errorEvents

    private fun reportError(userMessage: String, throwable: Throwable? = null) {
        Log.e(TAG, userMessage, throwable)
        _errorEvents.tryEmit(userMessage)
    }

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "Exceção não tratada no BspManager", t) },
    )

    @Volatile private var drainJob: Job? = null

    @Volatile private var statsJob: Job? = null

    // Jobs dos 3 coletores de connectTo() (estado, RTT, porta RTP). Antes ficavam soltos e
    // vazavam a cada reconexão/stop; agora são cancelados em stop()/novo connectTo().
    private val collectorJobs = mutableListOf<Job>()

    @Volatile private var videoCodec: MediaCodec? = null

    @Volatile private var inputSurface: Surface? = null

    @Volatile private var udpSocket: DatagramSocket? = null

    @Volatile private var packetizer: H264RtpPacketizer? = null

    @Volatile private var controlChannel: BspControlChannel? = null

    // Escritas pela thread do handshake/controle, lidas pela thread do drain.
    @Volatile private var forceKeyframe = false

    // Último buffer CODEC_CONFIG (SPS/PPS em Annex-B) entregue pelo encoder. Ele
    // só sai uma vez, no início — guardamos para reenviar a receptores tardios.
    @Volatile private var lastCodecConfig: ByteArray? = null

    // Sinaliza ao drain que o próximo frame deve ser precedido do SPS/PPS
    // (ex.: handshake acabou de concluir e o packetizer foi recém-criado).
    @Volatile private var needConfigResend = false

    /**
     * Prepara o encoder e o canal de controle, e retorna a Surface para o
     * MediaGraph passar ao nativeRenderer (igual setRecordSurface/setNdiSurface
     * hoje). Chame [connectTo] em seguida para efetivamente iniciar a conexão
     * com o receptor.
     */
    fun start(deviceName: String, width: Int, height: Int, fps: Int, bitrateMbps: Int = 12): Surface? {
        if (_isBspActive.value) {
            Log.w(TAG, "start() chamado com BSP já ativo — ignorando")
            return inputSurface
        }

        return try {
            val format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps * 1_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                // Keyframe a cada 2s: recuperação razoável de perda sem inflar
                // bitrate demais mandando IDR toda hora. Ajustável por rede.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_LATENCY, 0) // pede ao encoder pra não bufferizar frames internamente
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                }
                // Sem B-frames: cada frame decodifica na ordem que chega, essencial
                // para latência baixa (B-frames exigem reordenar no receptor).
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    setInteger("max-bframes", 0)
                }
                // Pede ao encoder para repetir SPS/PPS na frente de cada sync frame
                // (IDR): sem isso, um receptor que conecta depois do primeiro buffer
                // CODEC_CONFIG nunca consegue decodificar. Reforçado no drain.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                }
            }

            // Atribui o codec LOGO após criar: se configure()/start() falhar, releaseResources() o libera.
            val codec = MediaCodec.createEncoderByType(VIDEO_MIME)
            videoCodec = codec
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            inputSurface = surface
            codec.start()

            udpSocket = DatagramSocket()
            _fps.value = fps
            _isBspActive.value = true

            drainJob = scope.launch { drainEncoderLoop(codec) }
            statsJob = scope.launch { statsLoop() }

            Log.i(TAG, "BspManager iniciado (${width}x$height@${fps}fps, ${bitrateMbps}Mbps) — Surface pronta")
            surface
        } catch (e: Exception) {
            reportError("Falha ao iniciar encoder BSP: ${e.message}", e)
            // Mesma rotina de liberação do stop(): nada vaza se a falha foi no meio do start.
            _isBspActive.value = false
            drainJob?.cancel()
            statsJob?.cancel()
            releaseResources()
            null
        }
    }

    /** Conecta no receptor (IP digitado nas configurações, ou resolvido via discovery no futuro). */
    fun connectTo(host: String, deviceName: String, width: Int, height: Int, fps: Int) {
        if (!_isBspActive.value) {
            Log.w(TAG, "connectTo() chamado sem encoder ativo — chame start() primeiro")
            return
        }
        // Se já havia um canal (connectTo repetido), encerra o anterior e seus coletores.
        controlChannel?.disconnect()
        cancelCollectors()

        val channel = BspControlChannel(
            scope = scope,
            onKeyframeRequested = { forceKeyframe = true },
            onPacketLossReported = { loss -> _packetLossPercent.value = loss },
        )
        controlChannel = channel

        synchronized(collectorJobs) {
            collectorJobs += scope.launch {
                channel.connectionState.collect { _connectionState.value = it }
            }
            collectorJobs += scope.launch {
                channel.rttMs.collect { _rttMs.value = it }
            }
            collectorJobs += scope.launch {
                channel.negotiatedRtpPort.collect { negotiatedPort ->
                    val port = negotiatedPort ?: return@collect
                    val socket = udpSocket ?: return@collect
                    try {
                        packetizer = H264RtpPacketizer(socket, InetAddress.getByName(host), port)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        reportError("Não foi possível resolver o receptor BSP ($host): ${e.message}", e)
                        return@collect
                    }
                    // Receptor novo: reenvia SPS/PPS antes do próximo frame e força um
                    // IDR agora, para ele não esperar até 2s pelo próximo keyframe.
                    needConfigResend = true
                    forceKeyframe = true
                    Log.i(TAG, "RTP direcionado para $host:$port")
                }
            }
        }

        channel.connect(host, CONTROL_PORT, deviceName, width, height, fps)
    }

    /** Força um IDR no próximo frame — chamado automaticamente em pedidos do receptor, e disponível manualmente pra UI. */
    fun requestKeyframe() {
        forceKeyframe = true
    }

    private suspend fun drainEncoderLoop(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()

        while (currentCoroutineContext().isActive && _isBspActive.value) {
            if (forceKeyframe) {
                val params = android.os.Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                }
                try {
                    codec.setParameters(params)
                } catch (e: Exception) { /* best-effort */ }
                forceKeyframe = false
            }

            val outputStatus = try {
                codec.dequeueOutputBuffer(bufferInfo, 40_000L) // ~1 frame @ 25fps de timeout
            } catch (e: Exception) {
                // Durante o stop o codec é parado só DEPOIS de este job terminar (cancelAndJoin),
                // então uma exceção aqui é falha real do encoder.
                if (_isBspActive.value && currentCoroutineContext().isActive) {
                    reportError("Erro no drain do encoder BSP: ${e.message}", e)
                }
                break
            }

            when {
                outputStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> continue

                outputStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue

                outputStatus >= 0 -> {
                    try {
                        val outputBuffer = codec.getOutputBuffer(outputStatus)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            outputBuffer.get(data)
                            handleEncodedBuffer(data, bufferInfo)
                        }
                    } finally {
                        // Sempre devolve o buffer ao codec, mesmo se o envio lançar.
                        try {
                            codec.releaseOutputBuffer(outputStatus, false)
                        } catch (e: Exception) {
                            Log.w(TAG, "releaseOutputBuffer falhou", e)
                        }
                    }
                }
            }
        }
    }

    private fun handleEncodedBuffer(data: ByteArray, bufferInfo: MediaCodec.BufferInfo) {
        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // SPS/PPS: não é frame de vídeo (PTS 0/inválido). Só guarda;
            // é reenviado junto do próximo frame/IDR com timestamp correto.
            lastCodecConfig = data
            return
        }
        // Só empacota/envia se já sabemos pra onde (handshake concluído).
        // Enquanto isso os frames são descartados (não há buffer/fila —
        // por design, BSP nunca acumula atraso represando frames antigos).
        val rtp = packetizer ?: return
        val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        val config = lastCodecConfig
        // Antes de cada IDR sem SPS próprio (ou logo após o handshake), SPS/PPS vão NA FRENTE do
        // frame no MESMO access unit: um único sendEncodedFrame deixa o marker RTP só no último
        // NAL (se fossem dois envios, o marker cairia no PPS e o receptor fecharia o frame cedo).
        val payload = if (config != null && (needConfigResend || (isKeyFrame && !startsWithSps(data)))) {
            needConfigResend = false
            config + data
        } else {
            data
        }
        rtp.sendEncodedFrame(payload, bufferInfo.presentationTimeUs)
    }

    /** True se o buffer Annex-B começa com um NAL SPS (tipo 7), após o start code de 3 ou 4 bytes. */
    private fun startsWithSps(data: ByteArray): Boolean {
        val nalIndex = when {
            data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
                data[2] == 0.toByte() && data[3] == 1.toByte() -> 4

            data.size > 3 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
                data[2] == 1.toByte() -> 3

            else -> return false
        }
        return (data[nalIndex].toInt() and 0x1F) == 7
    }

    private suspend fun statsLoop() {
        var lastBytes = 0L
        var lastNs = System.nanoTime()
        while (currentCoroutineContext().isActive && _isBspActive.value) {
            delay(1000)
            val now = System.nanoTime()
            val seconds = ((now - lastNs) / 1_000_000_000.0).coerceAtLeast(0.001)
            lastNs = now
            val (_, totalBytes) = packetizer?.stats ?: (0L to 0L)
            // Um packetizer novo (reconexão) recomeça de 0: evita delta negativo.
            val deltaBytes = if (totalBytes >= lastBytes) totalBytes - lastBytes else totalBytes
            lastBytes = totalBytes
            _bitrateMbps.value = (deltaBytes * 8 / seconds / 1_000_000.0).toFloat()
        }
    }

    private fun cancelCollectors() {
        synchronized(collectorJobs) {
            collectorJobs.forEach { it.cancel() }
            collectorJobs.clear()
        }
    }

    /**
     * Para a transmissão. Ordem obrigatória (M21):
     *  1) marca inativo (o laço do drain para de aceitar trabalho);
     *  2) desconecta o canal de controle e espera o laço terminar (fecha o socket TCP);
     *  3) cancela e ESPERA coletores, stats e drain — só então nenhuma corrotina toca no codec;
     *  4) libera codec, surface, socket UDP e estado.
     * Roda em NonCancellable: um cancelamento do chamador não pode deixar o encoder pela metade.
     */
    override suspend fun stop() {
        withContext(NonCancellable) {
            _isBspActive.value = false

            controlChannel?.disconnectAndJoin()
            controlChannel = null

            cancelCollectors()
            statsJob?.cancelAndJoin()
            drainJob?.cancelAndJoin()
            statsJob = null
            drainJob = null

            releaseResources()
            Log.i(TAG, "BspManager parado")
        }
    }

    /**
     * Libera tudo que o start() cria. Compartilhada pelo stop() e pelo catch do start(); cada passo
     * isolado e idempotente (pode ser chamada com recursos parcialmente criados).
     */
    private fun releaseResources() {
        val codec = videoCodec
        videoCodec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (e: Exception) { /* nunca iniciou / já parado */ }
            try {
                codec.release()
            } catch (e: Exception) {
                Log.w(TAG, "Ao liberar codec", e)
            }
        }

        try {
            inputSurface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Ao liberar surface", e)
        }
        inputSurface = null

        try {
            udpSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Ao fechar socket UDP", e)
        }
        udpSocket = null
        packetizer = null
        lastCodecConfig = null
        needConfigResend = false
        forceKeyframe = false

        _connectionState.value = BspConnectionState.DISCONNECTED
        _bitrateMbps.value = 0f
        _packetLossPercent.value = 0f
        _rttMs.value = 0L
    }
}
