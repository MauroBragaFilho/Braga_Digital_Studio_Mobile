package com.bragastudio.mobile.coremedia.bsp

import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.bragastudio.mobile.coremedia.domain.PcmMath
import kotlin.random.Random

private const val TAG = "BspPipeline"

/**
 * Caminho de mídia do BSP na fonte: câmera -> `Surface` do encoder H.264 -> RTP (STAP-A/FU-A) ->
 * fila com pacing -> cifra AEAD por sessão -> UDP; e PCM do AudioRecord -> AAC-LC -> RTP (RFC 3640)
 * -> o mesmo caminho. Vídeo e áudio usam o MESMO relógio de captura (`System.nanoTime`) para os
 * timestamps RTP (8). Não conhece sessões nem rede de controle: recebe destinos por [addTarget].
 *
 * Só empacota enquanto há destino ativo: sem receptor (ou com o Monitor fechado, quando nenhum
 * quadro chega) o encoder roda sem produzir trabalho de rede. Um receptor novo só recebe a partir de
 * um IDR (pedido na entrada).
 */
class BspMediaPipeline(
    private val errorSink: (String) -> Unit,
    private val nanoClock: () -> Long = System::nanoTime,
    private val boottimeClock: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val keyframeLimiter: KeyframeLimiter = KeyframeLimiter(),
) : BspVideoEncoder.Listener,
    BspAudioEncoder.Listener {

    companion object {
        const val SLOT_SIZE = RTP_HEADER_SIZE + H264RtpPacketizer.MAX_RTP_PAYLOAD
        const val VIDEO_SLOTS = 1024
        const val AUDIO_SLOTS = 128
        const val KEYFRAME_INTERVAL_SEC = 2

        /** Sem quadros por mais disso (Monitor fechado e reaberto): pede IDR ao retomar. */
        private const val RESUME_GAP_NS = 1_000_000_000L
        private val RTP_RATE_VIDEO = H264RtpPacketizer.CLOCK_RATE_HZ.toInt()
    }

    val videoSsrc: Int = Random.nextInt(1, Int.MAX_VALUE)
    val audioSsrc: Int = Random.nextInt(1, Int.MAX_VALUE)

    private var frameIntervalNs = 33_333_333L
    private lateinit var videoQueue: PacedPacketQueue
    private lateinit var audioQueue: PacedPacketQueue

    @Volatile private var sender: BspSender? = null

    @Volatile private var videoPacketizer: H264RtpPacketizer? = null

    @Volatile private var audioPacketizer: AacRtpPacketizer? = null
    private var videoEncoder: BspVideoEncoder? = null
    private var audioEncoder: BspAudioEncoder? = null

    @Volatile private var videoClock: RtpClock? = null

    @Volatile private var audioClock: RtpClock? = null
    private var ptsDomain: PtsDomain? = null
    private var audioSmoother = AudioTimestampSmoother()

    @Volatile var sps: ByteArray? = null
        private set

    @Volatile var pps: ByteArray? = null
        private set

    @Volatile private var awaitingKeyFrame = true

    private var lastFrameNs = 0L
    private var lastAudioEndUs = 0L

    @Volatile private var loggedAudioMismatch = false

    @Volatile var running = false
        private set

    val framesEncoded: Long get() = videoEncoder?.framesEncoded ?: 0L
    val bytesSent: Long get() = sender?.bytesSent ?: 0L
    val sendErrors: Long get() = sender?.sendErrors ?: 0L
    val droppedPackets: Long get() = if (running) videoQueue.droppedPackets + audioQueue.droppedPackets else 0L
    val hasActiveTargets: Boolean get() = sender?.hasTargets == true

    /** Prepara tudo e devolve a Surface do encoder. Lança em falha; o chamador deve chamar [stop]. */
    @Synchronized
    fun start(width: Int, height: Int, fps: Int, bitrateBps: Int): Surface {
        check(!running) { "pipeline BSP já iniciado" }
        frameIntervalNs = 1_000_000_000L / fps.coerceIn(1, 120)
        videoQueue = PacedPacketQueue(VIDEO_SLOTS, SLOT_SIZE, maxLagNs = frameIntervalNs * 2)
        audioQueue = PacedPacketQueue(AUDIO_SLOTS, SLOT_SIZE, maxLagNs = Long.MAX_VALUE / 4)
        val origin = nanoClock()
        videoClock = RtpClock(RTP_RATE_VIDEO, Random.nextLong(0, 0x100000000L), origin)
        audioClock = RtpClock(AacConfig.SAMPLE_RATE, Random.nextLong(0, 0x100000000L), origin)
        ptsDomain = PtsDomain(nanoClock, boottimeClock)
        audioSmoother = AudioTimestampSmoother()
        lastAudioEndUs = 0L
        lastFrameNs = 0L
        awaitingKeyFrame = true
        sps = null
        pps = null
        videoPacketizer = H264RtpPacketizer(videoQueue, payloadType = 96, ssrc = videoSsrc)
        audioPacketizer = AacRtpPacketizer(audioQueue, payloadType = 97, ssrc = audioSsrc)
        val s = BspSender(videoQueue, audioQueue, SLOT_SIZE, nanoClock)
        sender = s
        s.start()
        running = true
        try {
            val encoder = BspVideoEncoder(this)
            videoEncoder = encoder
            val surface = encoder.start(width, height, fps, bitrateBps, KEYFRAME_INTERVAL_SEC)
            try {
                val audio = BspAudioEncoder(this)
                audioEncoder = audio
                audio.start()
            } catch (e: Exception) {
                // Sem AAC o vídeo segue (WELCOME sem o bloco de áudio não é possível: o áudio é parte do fluxo)
                Log.w(TAG, "Encoder AAC indisponível: ${e.javaClass.simpleName}; BSP segue só com vídeo")
                audioEncoder?.stop()
                audioEncoder = null
            }
            return surface
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    /** True se o áudio AAC está disponível (vai no WELCOME). */
    val hasAudio: Boolean get() = audioEncoder != null

    @Synchronized
    fun stop() {
        running = false
        audioEncoder?.stop()
        audioEncoder = null
        videoEncoder?.stop()
        videoEncoder = null
        sender?.stop()
        sender = null
        videoPacketizer = null
        audioPacketizer = null
    }

    fun addTarget(target: BspMediaTarget) {
        val s = sender ?: return
        s.addTarget(target)
        resync()
    }

    fun removeTarget(sessionId: Int) {
        sender?.removeTarget(sessionId)
    }

    fun setTargetPaused(sessionId: Int, paused: Boolean) {
        val target = sender?.target(sessionId) ?: return
        val wasPaused = target.paused
        target.paused = paused
        if (wasPaused && !paused) resync()
    }

    /** Receptor novo / retomada: só envia a partir do próximo IDR e o pede agora (respeita o limite de 500 ms por pedido externo). */
    fun resync() {
        awaitingKeyFrame = true
        videoEncoder?.requestKeyframe()
    }

    /** Pedido externo de IDR (PLI do receptor, descarte de fila): no máximo 1 por 500 ms (6.4). */
    fun requestKeyframeLimited(nowMs: Long = wallClockMs()) {
        if (keyframeLimiter.tryAcquire(nowMs)) videoEncoder?.requestKeyframe()
    }

    /** Mapeamento NTP <-> RTP do instante [nowNs] (SR a cada 1 s). */
    fun senderReports(nowNs: Long): Pair<BspStreamReport, BspStreamReport?>? {
        val vc = videoClock ?: return null
        val vp = videoPacketizer ?: return null
        val video = BspStreamReport(videoSsrc, vc.rtpAt(nowNs), vp.packetsSent, vp.bytesSent)
        val ac = audioClock
        val ap = audioPacketizer
        val audio = if (ac != null && ap != null && audioEncoder != null) BspStreamReport(audioSsrc, ac.rtpAt(nowNs), ap.packetsSent, ap.bytesSent) else null
        return video to audio
    }

    // ---- Vídeo (thread do encoder) -----------------------------------------------------

    override fun onCodecConfig(sps: ByteArray?, pps: ByteArray?) {
        if (sps != null) this.sps = sps
        if (pps != null) this.pps = pps
        videoPacketizer?.setParameterSets(this.sps, this.pps)
    }

    override fun onFrame(data: ByteArray, length: Int, presentationTimeUs: Long, keyFrame: Boolean) {
        val packetizer = videoPacketizer ?: return
        val clock = videoClock ?: return
        val domain = ptsDomain ?: return
        val s = sender ?: return
        val nowNs = nanoClock()
        // quadros depois de um silêncio (Monitor fechado e reaberto): o receptor precisa de um IDR novo
        val gap = lastFrameNs != 0L && nowNs - lastFrameNs > RESUME_GAP_NS
        lastFrameNs = nowNs
        if (gap && s.hasTargets) resync()
        if (!s.hasTargets) return
        if (awaitingKeyFrame) {
            if (!keyFrame) return
            awaitingKeyFrame = false
        }
        val rtpTs = clock.rtpAt(domain.toMonotonicNs(presentationTimeUs))
        videoQueue.beginFrame(keyFrame)
        packetizer.sendAccessUnit(data, 0, length, rtpTs)
        videoQueue.endFrame(nowNs, frameIntervalNs)
        if (videoQueue.consumeOverflow()) {
            // fila travada: descartou quadros não-IDR; pede IDR (limitado)
            awaitingKeyFrame = true
            requestKeyframeLimited()
        }
        s.wake()
    }

    // ---- Áudio ----------------------------------------------------------------------------

    /**
     * PCM 16-bit LE intercalado do AudioRecord (thread de leitura): chamado só enquanto a captura de
     * áudio existe (CaptureLifecyclePolicy). Não bloqueia: sem destino ou sem buffer, descarta.
     */
    fun feedAudio(pcm: ByteArray, numSamples: Int, numChannels: Int, sampleRate: Int) {
        val encoder = audioEncoder ?: return
        val s = sender ?: return
        if (!s.hasTargets) return
        if (sampleRate != AacConfig.SAMPLE_RATE || numChannels != AacConfig.CHANNELS) {
            if (!loggedAudioMismatch) {
                loggedAudioMismatch = true
                Log.w(TAG, "Áudio em ${sampleRate}Hz/${numChannels}ch; o BSP exige 48000Hz/2ch: áudio BSP desligado")
            }
            return
        }
        val bytes = numSamples * numChannels * 2
        val nowUs = nanoClock() / 1_000L
        val duration = PcmMath.durationUs(bytes, numChannels, sampleRate)
        val pts = AudioPts.blockStartUs(nowUs, duration, lastAudioEndUs)
        lastAudioEndUs = pts + duration
        encoder.feed(pcm, bytes, pts)
    }

    override fun onAccessUnit(data: ByteArray, length: Int, presentationTimeUs: Long) {
        val packetizer = audioPacketizer ?: return
        val clock = audioClock ?: return
        val s = sender ?: return
        if (!s.hasTargets) return
        val rtpTs = audioSmoother.next(clock.rtpAt(presentationTimeUs * 1_000L))
        audioQueue.beginFrame(true) // áudio nunca entra no descarte de quadros "não-IDR"
        packetizer.sendAccessUnit(data, 0, length, rtpTs)
        audioQueue.endFrame(nanoClock(), 0L)
        s.wake()
    }

    override fun onError(message: String) = errorSink(message)
}
