package com.bragastudio.mobile.coremedia.bsp

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONException
import org.json.JSONObject

enum class BspConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

/**
 * Mensagens de controle do BSP (JSON de uma linha). Usa org.json da plataforma Android:
 * monta e lê com escape correto (o parser manual por regex anterior quebrava com aspas e
 * barras no nome do dispositivo e aceitava campos de qualquer lugar da linha).
 */
internal object BspJson {

    fun helloMessage(deviceName: String, width: Int, height: Int, fps: Int): String = JSONObject()
        .put("type", "HELLO")
        .put("protocolVersion", 1)
        .put("deviceName", deviceName)
        .put("codecsSupported", org.json.JSONArray().put("H264"))
        .put("width", width)
        .put("height", height)
        .put("fps", fps)
        .toString()

    /** [id] permite casar o ACK com o envio e medir o RTT real. */
    fun heartbeatMessage(id: Long, timestampMs: Long): String = JSONObject().put("type", "HEARTBEAT").put("id", id).put("ts", timestampMs).toString()

    fun byeMessage(): String = JSONObject().put("type", "BYE").toString()

    /** Faz o parse de uma linha; null se não for um objeto JSON válido. */
    fun parse(line: String): JSONObject? = try {
        JSONObject(line)
    } catch (e: JSONException) {
        null
    }
}

private const val TAG = "BspControlChannel"
private const val HANDSHAKE_TIMEOUT_MS = 4000
private const val HEARTBEAT_INTERVAL_MS = 1000L
private const val HEARTBEAT_TIMEOUT_MS = 5000L
private const val RECONNECT_DELAY_MS = 2000L

/**
 * Canal TCP persistente com o receptor BSP no PC — abre em um host/porta fixos (sem discovery
 * automático por enquanto), faz o handshake inicial (negocia porta RTP e codec) e mantém
 * heartbeats para saber se a conexão está viva. Também recebe pedidos de keyframe e relatos de
 * perda de pacote vindos do receptor.
 *
 * Reconecta automaticamente com backoff simples se a conexão cair. Cada tentativa fecha o seu
 * socket em `finally` (antes o FD vazava a cada reconexão).
 */
class BspControlChannel(
    private val scope: CoroutineScope,
    private val onKeyframeRequested: () -> Unit,
    private val onPacketLossReported: (Float) -> Unit,
) {
    private val _connectionState = MutableStateFlow(BspConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BspConnectionState> = _connectionState.asStateFlow()

    private val _rttMs = MutableStateFlow(0L)
    val rttMs: StateFlow<Long> = _rttMs.asStateFlow()

    private val _negotiatedRtpPort = MutableStateFlow<Int?>(null)
    val negotiatedRtpPort: StateFlow<Int?> = _negotiatedRtpPort.asStateFlow()

    @Volatile private var socket: Socket? = null

    @Volatile private var writer: PrintWriter? = null

    @Volatile private var connectionJob: Job? = null

    @Volatile private var shouldRun = false

    @Volatile private var lastHeartbeatAckAt = 0L

    // Heartbeats enviados e ainda sem ACK: id -> instante de envio (nanoTime).
    private val pendingHeartbeats = ConcurrentHashMap<Long, Long>()
    private val heartbeatSeq = AtomicLong(0)

    fun connect(host: String, controlPort: Int, deviceName: String, width: Int, height: Int, fps: Int) {
        shouldRun = true
        connectionJob?.cancel()
        connectionJob = scope.launch(Dispatchers.IO) {
            while (shouldRun && isActive) {
                try {
                    runConnection(host, controlPort, deviceName, width, height, fps)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Conexão BSP caiu: ${e.message}")
                }
                if (!shouldRun) break
                _connectionState.value = BspConnectionState.RECONNECTING
                _negotiatedRtpPort.value = null
                delay(RECONNECT_DELAY_MS)
            }
        }
    }

    private suspend fun runConnection(host: String, controlPort: Int, deviceName: String, width: Int, height: Int, fps: Int) {
        _connectionState.value = BspConnectionState.CONNECTING
        pendingHeartbeats.clear()

        val sock = Socket()
        socket = sock
        try {
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress(host, controlPort), HANDSHAKE_TIMEOUT_MS)
            sock.soTimeout = HANDSHAKE_TIMEOUT_MS

            val out = PrintWriter(sock.getOutputStream(), true)
            writer = out
            val inReader = BufferedReader(InputStreamReader(sock.getInputStream()))

            out.println(BspJson.helloMessage(deviceName, width, height, fps))

            val acceptLine = inReader.readLine()
                ?: throw java.io.IOException("Receptor BSP fechou a conexão durante o handshake")
            val accept = BspJson.parse(acceptLine)
                ?: throw java.io.IOException("Resposta de handshake inválida")

            if (accept.optString("type") != "ACCEPT" || !accept.optBoolean("accepted", false)) {
                _connectionState.value = BspConnectionState.ERROR
                val reason = accept.optString("reason").ifBlank { "recusado pelo receptor" }
                throw java.io.IOException("Handshake BSP falhou: $reason")
            }

            val rtpPort = accept.optInt("rtpPort", -1).takeIf { it in 1..65535 }
                ?: throw java.io.IOException("ACCEPT sem rtpPort válido")

            _negotiatedRtpPort.value = rtpPort
            _connectionState.value = BspConnectionState.CONNECTED
            lastHeartbeatAckAt = System.currentTimeMillis()
            Log.i(TAG, "BSP conectado a $host:$controlPort — RTP em $rtpPort")

            sock.soTimeout = HEARTBEAT_INTERVAL_MS.toInt()
            coroutineScope {
                val heartbeatJob = launch {
                    while (isActive) {
                        val id = heartbeatSeq.incrementAndGet()
                        pendingHeartbeats[id] = System.nanoTime()
                        out.println(BspJson.heartbeatMessage(id, System.currentTimeMillis()))
                        if (out.checkError()) throw java.io.IOException("Falha ao enviar heartbeat")
                        delay(HEARTBEAT_INTERVAL_MS)
                        if (System.currentTimeMillis() - lastHeartbeatAckAt > HEARTBEAT_TIMEOUT_MS) {
                            throw java.io.IOException("Heartbeat sem resposta — conexão considerada morta")
                        }
                    }
                }
                val readJob = launch {
                    while (isActive) {
                        val line = try {
                            inReader.readLine()
                        } catch (e: SocketTimeoutException) {
                            continue
                        } ?: throw java.io.IOException("Receptor BSP encerrou a conexão")
                        handleLine(line)
                    }
                }
                // Qualquer um dos dois que falhar derruba o outro (coroutineScope propaga) e a
                // conexão é refeita pelo laço de connect().
                heartbeatJob.join()
                readJob.cancel()
            }
        } finally {
            // Fecha SEMPRE o socket desta tentativa (sem vazar FD a cada reconexão).
            try {
                sock.close()
            } catch (e: Exception) {
                // ignora
            }
            if (socket === sock) socket = null
            writer = null
        }
    }

    private fun handleLine(line: String) {
        val msg = BspJson.parse(line) ?: return
        when (msg.optString("type")) {
            "HEARTBEAT_ACK" -> {
                val now = System.currentTimeMillis()
                lastHeartbeatAckAt = now
                _rttMs.value = measureRtt(msg)
            }

            "KEYFRAME_REQUEST" -> onKeyframeRequested()

            "PACKET_LOSS_REPORT" -> {
                if (msg.has("lossPercent")) onPacketLossReported(msg.optDouble("lossPercent", 0.0).toFloat())
            }
        }
    }

    /**
     * RTT real: casa o ACK com o heartbeat enviado. Usa o `id` ecoado pelo receptor; se ele não
     * ecoar, assume ACK em ordem e usa o heartbeat pendente mais antigo. Mede entre o envio e a
     * chegada do ACK (antes media só o tempo do println local, sempre ~0 ms).
     */
    private fun measureRtt(ack: JSONObject): Long {
        val id: Long? = if (ack.has("id")) ack.optLong("id", -1L).takeIf { it >= 0 } else null
        val key = id?.takeIf { pendingHeartbeats.containsKey(it) } ?: pendingHeartbeats.keys.minOrNull()
        val sentAtNs = key?.let { pendingHeartbeats.remove(it) } ?: return _rttMs.value
        // Descarta heartbeats mais antigos que o confirmado (ACK perdido): evita acumular.
        if (key != null) pendingHeartbeats.keys.filter { it < key }.forEach { pendingHeartbeats.remove(it) }
        return ((System.nanoTime() - sentAtNs) / 1_000_000L).coerceAtLeast(0L)
    }

    /** Desconecta sem esperar o fim da corrotina (compatibilidade). Prefira [disconnectAndJoin]. */
    fun disconnect() {
        shouldRun = false
        sendBye()
        connectionJob?.cancel()
        closeSocketQuietly()
        _connectionState.value = BspConnectionState.DISCONNECTED
        _negotiatedRtpPort.value = null
    }

    /**
     * Desconecta e ESPERA o laço de conexão terminar. Fechar o socket destrava o readLine()
     * bloqueante, então o join é rápido. Use no stop() do BspManager antes de liberar o resto.
     */
    suspend fun disconnectAndJoin() {
        shouldRun = false
        sendBye()
        val job = connectionJob
        closeSocketQuietly()
        job?.cancelAndJoin()
        connectionJob = null
        _connectionState.value = BspConnectionState.DISCONNECTED
        _negotiatedRtpPort.value = null
    }

    private fun sendBye() {
        try {
            writer?.println(BspJson.byeMessage())
        } catch (e: Exception) {
            // melhor esforço
        }
    }

    private fun closeSocketQuietly() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // ignora
        }
    }
}
