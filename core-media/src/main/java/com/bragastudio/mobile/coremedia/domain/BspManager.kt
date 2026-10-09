package com.bragastudio.mobile.coremedia.domain

import android.content.Context
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import androidx.core.content.edit
import com.bragastudio.mobile.core.domain.NdiNaming
import com.bragastudio.mobile.coremedia.bsp.AacConfig
import com.bragastudio.mobile.coremedia.bsp.BspAdvert
import com.bragastudio.mobile.coremedia.bsp.BspAudioParams
import com.bragastudio.mobile.coremedia.bsp.BspAuthority
import com.bragastudio.mobile.coremedia.bsp.BspControlServer
import com.bragastudio.mobile.coremedia.bsp.BspDiscovery
import com.bragastudio.mobile.coremedia.bsp.BspFeedbackListener
import com.bragastudio.mobile.coremedia.bsp.BspFeedbackMessage
import com.bragastudio.mobile.coremedia.bsp.BspFeedbackReceiver
import com.bragastudio.mobile.coremedia.bsp.BspMediaPipeline
import com.bragastudio.mobile.coremedia.bsp.BspMediaTarget
import com.bragastudio.mobile.coremedia.bsp.BspMeta
import com.bragastudio.mobile.coremedia.bsp.BspMetaSource
import com.bragastudio.mobile.coremedia.bsp.BspNetParams
import com.bragastudio.mobile.coremedia.bsp.BspNtp
import com.bragastudio.mobile.coremedia.bsp.BspProtocol
import com.bragastudio.mobile.coremedia.bsp.BspSession
import com.bragastudio.mobile.coremedia.bsp.BspSessionListener
import com.bragastudio.mobile.coremedia.bsp.BspSessionProvider
import com.bragastudio.mobile.coremedia.bsp.BspStreamParams
import com.bragastudio.mobile.coremedia.bsp.BspStreamSealer
import com.bragastudio.mobile.coremedia.bsp.BspTxt
import com.bragastudio.mobile.coremedia.bsp.BspVideoParams
import com.bragastudio.mobile.coremedia.bsp.H264RtpPacketizer
import com.bragastudio.mobile.network.LinkServer
import com.bragastudio.mobile.network.auth.LinkAuthManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.InetAddress
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Receptor conectado, para a UI: SÓ o nome (nunca o IP), o RTT do heartbeat e se está recebendo mídia. */
data class BspReceiver(val name: String, val rttMs: Long, val streaming: Boolean)

/** Estatísticas de 1 s da transmissão BSP. */
data class BspStats(
    val bitrateMbps: Float = 0f,
    val fps: Int = 0,
    /** Perda de vídeo relatada pelo receptor (REPORT), em %; 0 sem relatório recente. */
    val lossPercent: Float = 0f,
    val jitterMs: Int = 0,
    /** Maior RTT entre as sessões prontas. */
    val rttMs: Long = 0L,
)

/**
 * Fonte BSP v2 (`.docs/BSP_ESPECIFICACAO.md`): o celular é SERVIDOR descobrível. Enquanto o BSP está
 * habilitado: anuncia `_bsp._tcp` (mDNS), aceita receptores no canal de controle TCP (autenticados
 * pelo pareamento do BDSM Link), e, para cada receptor que confirma (READY), envia vídeo H.264 e
 * áudio AAC por UDP com AEAD. Ver [BspMediaPipeline] (mídia) e [BspControlServer] (controle).
 *
 * Regra de ciclo de vida inalterada: o BSP NÃO segura câmera nem microfone; só há quadros (e PCM)
 * com o Monitor visível ou durante o REC, como no NDI. [setCaptureFlowing] só informa os receptores
 * (START/STOP) e a UI; não abre nem fecha nada.
 *
 * [start]/[stop] são serializados pelo MediaGraph (mutex de ciclo de vida do BSP).
 */
@Singleton
class BspManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val linkAuth: LinkAuthManager,
) : StreamOutput {
    companion object {
        private const val TAG = "BspManager"
        private const val TICK_MS = 500L
        private const val LATENCY_MS = 80
        private const val KEYFRAME_INTERVAL_MS = 2_000
        private const val PREFS = "bdsm_bsp"
        private const val KEY_DEVICE_ID = "device_id"
        private const val REPORT_STALE_MS = 3_000L
        private const val REASON_MONITOR_CLOSED = "monitor_closed"

        /** Bitrate de vídeo (bps) por área: 10 Mbps em 1080p (1), faixa 4 a 25 Mbps. */
        internal fun bitrateBpsFor(width: Int, height: Int): Int {
            val scaled = 10_000_000L * width * height / (1920L * 1080L)
            return scaled.coerceIn(4_000_000L, 25_000_000L).toInt()
        }
    }

    private val _isBspActive = MutableStateFlow(false)
    val isBspActive: StateFlow<Boolean> = _isBspActive.asStateFlow()
    override val isActive: StateFlow<Boolean> get() = isBspActive

    private val _receivers = MutableStateFlow<List<BspReceiver>>(emptyList())

    /** Receptores conectados (nomes). */
    val receivers: StateFlow<List<BspReceiver>> = _receivers.asStateFlow()

    private val _stats = MutableStateFlow(BspStats())
    val stats: StateFlow<BspStats> = _stats.asStateFlow()

    private val _announcedName = MutableStateFlow("")

    /** Nome com que a fonte aparece na rede (`BDSM (nome)`); vazio com o BSP desligado. */
    val announcedName: StateFlow<String> = _announcedName.asStateFlow()

    private val _captureFlowing = MutableStateFlow(false)

    /** True quando há quadros fluindo da câmera (Monitor visível ou REC): sem isto o BSP não emite nada. */
    val captureFlowing: StateFlow<Boolean> = _captureFlowing.asStateFlow()

    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    override val errorEvents: SharedFlow<String> = _errorEvents

    /** Metadados da fonte (lente, REC, orientação) fornecidos pelo MediaGraph; bateria/térmico saem daqui. */
    @Volatile var metaSource: BspMetaSource = BspMetaSource { BspMeta() }

    /** Mídia sem criptografia (somente depuração; padrão false). Aplicado às próximas sessões. */
    @Volatile var allowPlainMedia: Boolean = false

    private val scope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "Exceção não tratada no BspManager", t) },
    )

    private val discovery by lazy { BspDiscovery(context) }

    @Volatile private var pipeline: BspMediaPipeline? = null

    @Volatile private var server: BspControlServer? = null
    private var feedback: BspFeedbackReceiver? = null
    private var jobs = mutableListOf<Job>()

    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var sourceName = ""

    /** Nome da fonte com que o BSP está ativo (vazio parado); mudou o nome do aparelho/NDI: o MediaGraph reinicia. */
    val activeSourceName: String get() = if (_isBspActive.value) sourceName else ""
    private var frameWidth = 0
    private var frameHeight = 0
    private var frameFps = 30
    private var bitrateKbps = 0

    @Volatile private var lastReport: BspFeedbackMessage.Report? = null

    @Volatile private var lastReportAtMs = 0L

    @Volatile private var advertisedLive = false

    private fun reportError(message: String, t: Throwable? = null) {
        Log.e(TAG, message, t)
        _errorEvents.tryEmit(message)
    }

    private val authority = object : BspAuthority {
        override fun clientKey(clientId: String): ByteArray? = linkAuth.clientKey(clientId)
        override fun clientName(clientId: String): String? = linkAuth.pairedClient(clientId)?.name
    }

    private val provider = object : BspSessionProvider {
        override val deviceName: String get() = sourceName
        override val allowPlainMedia: Boolean get() = this@BspManager.allowPlainMedia

        override fun streamParams(): BspStreamParams? {
            val p = pipeline?.takeIf { it.running } ?: return null
            val video = BspVideoParams(
                width = frameWidth,
                height = frameHeight,
                fps = frameFps,
                bitrateKbps = bitrateKbps,
                ssrc = p.videoSsrc,
                payloadType = 96,
                sps = p.sps,
                pps = p.pps,
                orientation = metaSource.current().orientation,
            )
            val audio = if (p.hasAudio) {
                BspAudioParams(AacConfig.SAMPLE_RATE, AacConfig.CHANNELS, p.audioSsrc, 97, AacConfig.audioSpecificConfig()!!)
            } else {
                null
            }
            val net = BspNetParams(
                mtu = H264RtpPacketizer.MAX_RTP_PAYLOAD,
                latencyMs = LATENCY_MS,
                fecK = 0,
                nack = false, // Fase 2
                feedbackPort = feedback?.port ?: 0,
                aead = true,
            )
            return BspStreamParams(video, audio, net, KEYFRAME_INTERVAL_MS)
        }
    }

    private val sessionListener = object : BspSessionListener {
        override fun onSessionReady(session: BspSession) {
            val p = pipeline ?: return
            val keys = session.keys
            val videoSealer = if (session.aead && keys != null) BspStreamSealer(keys.key, keys.nonceSalt, session.videoSsrc) else null
            val audioSealer = if (session.aead && keys != null && session.audioSsrc != null) {
                BspStreamSealer(keys.key, keys.nonceSalt, session.audioSsrc)
            } else {
                null
            }
            session.wipeKeys() // as chaves vivem só nos cifradores
            val target = BspMediaTarget(session.id, session.mediaTarget(), videoSealer, audioSealer)
            target.paused = session.receiverPaused
            p.addTarget(target)
            acquireWifiLock()
            if (!_captureFlowing.value) session.send(BspProtocol.stop(REASON_MONITOR_CLOSED))
            session.send(BspProtocol.meta(currentMeta()))
        }

        override fun onSessionChanged(session: BspSession) {
            pipeline?.setTargetPaused(session.id, session.receiverPaused)
        }

        override fun onSessionClosed(session: BspSession) {
            pipeline?.removeTarget(session.id)
            if (server?.sessionList()?.none { it.ready } != false) releaseWifiLock()
        }
    }

    private val feedbackListener = object : BspFeedbackListener {
        override fun onReport(from: InetAddress, report: BspFeedbackMessage.Report) {
            lastReport = report
            lastReportAtMs = System.currentTimeMillis()
        }

        override fun onPli(from: InetAddress, pli: BspFeedbackMessage.Pli) {
            pipeline?.requestKeyframeLimited()
        }
    }

    private fun currentMeta(): BspMeta {
        val base = metaSource.current()
        return base.copy(battery = batteryPercent(), thermal = thermalStatus())
    }

    private fun batteryPercent(): Int = try {
        (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (_: Exception) {
        -1
    }

    private fun thermalStatus(): Int = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        } else {
            0
        }
    } catch (_: Exception) {
        0
    }

    /** Informa se há quadros fluindo (Monitor visível ou REC): avisa os receptores; não muda o ciclo de vida. */
    fun setCaptureFlowing(flowing: Boolean) {
        if (_captureFlowing.value == flowing) return
        _captureFlowing.value = flowing
        val s = server ?: return
        s.broadcast(if (flowing) BspProtocol.start() else BspProtocol.stop(REASON_MONITOR_CLOSED))
        if (flowing) pipeline?.resync()
    }

    /**
     * Sobe a fonte BSP: encoders e caminho de mídia, servidor de controle, retorno UDP e anúncio mDNS.
     * Devolve a Surface do encoder para o MediaGraph passar ao renderer, ou null em falha (já reportada,
     * recursos liberados). [sourceName] é o nome da fonte (o mesmo do NDI, sem o prefixo "BDSM").
     */
    fun start(sourceName: String, width: Int, height: Int, fps: Int): Surface? {
        if (_isBspActive.value) {
            Log.w(TAG, "start() com o BSP já ativo; ignorando")
            return null
        }
        this.sourceName = sourceName
        frameWidth = width
        frameHeight = height
        frameFps = fps
        val bitrateBps = bitrateBpsFor(width, height)
        bitrateKbps = bitrateBps / 1000

        val p = BspMediaPipeline(errorSink = { reportError(it) })
        pipeline = p
        return try {
            val surface = p.start(width, height, fps, bitrateBps)

            val srv = BspControlServer(authority, provider, sessionListener)
            val fb = BspFeedbackReceiver(feedbackListener) { from -> srv.sessionList().any { it.ready && it.remoteAddress == from } }
            // o retorno abre ANTES do controle: o WELCOME informa a porta de retorno real
            fb.start()
            feedback = fb
            val port = srv.start()
            server = srv

            _announcedName.value = BspTxt.instanceName(NdiNaming.MACHINE_NAME, sourceName)
            advertise(live = false)
            startJobs(srv)
            _isBspActive.value = true
            Log.i(TAG, "BSP iniciado: ${width}x$height@$fps, $bitrateKbps kbps, controle na porta $port")
            surface
        } catch (e: Exception) {
            reportError("Falha ao iniciar a transmissão BSP: ${e.message}", e)
            teardown()
            null
        }
    }

    private fun startJobs(srv: BspControlServer) {
        jobs += scope.launch {
            // lista de receptores para a UI e anúncio `st=live|idle` conforme há receptor pronto
            srv.sessions.collect { sessions ->
                _receivers.value = sessions.map { BspReceiver(it.name, it.rttMs, it.ready && !it.paused) }
                val live = sessions.any { it.ready }
                if (live != advertisedLive) advertise(live)
            }
        }
        jobs += scope.launch {
            // pareamento revogado no Link derruba as sessões do cliente
            linkAuth.paired.collect { paired ->
                val ids = paired.map { it.clientId }.toSet()
                srv.closeSessionsWhere { it !in ids }
            }
        }
        jobs += scope.launch { tickLoop(srv) }
    }

    private fun advertise(live: Boolean) {
        val srv = server ?: return
        advertisedLive = live
        val txt = BspTxt.build(
            deviceId = deviceId(),
            displayName = _announcedName.value,
            width = frameWidth,
            height = frameHeight,
            fps = frameFps,
            linkPort = LinkServer.PORT,
            live = live,
            hasAudio = pipeline?.hasAudio == true,
        )
        discovery.advertise(BspAdvert(_announcedName.value, srv.port, txt))
    }

    private fun deviceId(): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also { prefs.edit { putString(KEY_DEVICE_ID, it) } }
    }

    /**
     * Uma corrotina só (11): a cada 500 ms confere META (no máximo 2 por segundo, só se mudou); a cada
     * 1 s calcula as estatísticas e envia o SR (NTP <-> RTP) aos receptores prontos. Sem receptores ela
     * desacelera para 1 s.
     */
    private suspend fun tickLoop(srv: BspControlServer) {
        var lastBytes = 0L
        var lastFrames = 0L
        var lastNs = System.nanoTime()
        var lastMeta: BspMeta? = null
        var tick = 0
        while (currentCoroutineContext().isActive) {
            val anyReady = srv.sessionList().any { it.ready }
            delay(if (anyReady) TICK_MS else 2 * TICK_MS)
            tick++
            val p = pipeline ?: continue
            if (anyReady && srv.sessionList().any { it.ready }) {
                val meta = currentMeta()
                if (meta != lastMeta) {
                    lastMeta = meta
                    srv.broadcast(BspProtocol.meta(meta))
                }
            }
            if (!anyReady || tick % 2 == 0) {
                val now = System.nanoTime()
                val seconds = ((now - lastNs) / 1_000_000_000.0).coerceAtLeast(0.001)
                lastNs = now
                val bytes = p.bytesSent
                val frames = p.framesEncoded
                val targets = srv.sessionList().count { it.ready && !it.receiverPaused }.coerceAtLeast(1)
                val mbps = ((bytes - lastBytes).coerceAtLeast(0) * 8 / seconds / 1_000_000.0 / targets).toFloat()
                val fps = ((frames - lastFrames).coerceAtLeast(0) / seconds).toInt()
                lastBytes = bytes
                lastFrames = frames
                val report = lastReport?.takeIf { System.currentTimeMillis() - lastReportAtMs < REPORT_STALE_MS }
                val rtt = srv.sessionList().filter { it.ready }.maxOfOrNull { it.rttMs } ?: 0L
                _stats.value = BspStats(mbps, fps, report?.videoLossPercent ?: 0f, report?.jitterMs ?: 0, rtt)
                if (anyReady) sendSenderReport(srv, p, now)
            }
        }
    }

    private fun sendSenderReport(srv: BspControlServer, p: BspMediaPipeline, nowNs: Long) {
        val reports = p.senderReports(nowNs) ?: return
        val wall = System.currentTimeMillis()
        srv.broadcast(BspProtocol.senderReport(BspNtp.seconds(wall), BspNtp.fraction(wall), reports.first, reports.second))
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "bdsm_bsp_wifi_lock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WifiLock indisponível: ${e.javaClass.simpleName}")
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Ao soltar o WifiLock: ${e.javaClass.simpleName}")
        }
        wifiLock = null
    }

    /** Força um IDR (uso manual/diagnóstico); o limite de 500 ms por pedido se aplica. */
    fun requestKeyframe() {
        pipeline?.requestKeyframeLimited()
    }

    /** PCM do AudioRecord (thread de leitura); só chega com a captura de áudio ativa pela política de ciclo de vida. */
    fun feedAudio(pcmData: ByteArray, numSamples: Int, numChannels: Int, sampleRate: Int) {
        pipeline?.feedAudio(pcmData, numSamples, numChannels, sampleRate)
    }

    /**
     * Para a fonte. Ordem: retira o anúncio, encerra as sessões (BYE) e o servidor, para o retorno,
     * cancela os jobs, e SÓ ENTÃO libera encoders e envio. Roda em NonCancellable.
     */
    override suspend fun stop() {
        withContext(NonCancellable) {
            _isBspActive.value = false
            teardown()
            Log.i(TAG, "BSP parado")
        }
    }

    private fun teardown() {
        _isBspActive.value = false
        discovery.advertise(null)
        advertisedLive = false
        server?.stop()
        server = null
        feedback?.stop()
        feedback = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        pipeline?.stop()
        pipeline = null
        releaseWifiLock()
        lastReport = null
        _receivers.value = emptyList()
        _stats.value = BspStats()
        _announcedName.value = ""
    }
}
