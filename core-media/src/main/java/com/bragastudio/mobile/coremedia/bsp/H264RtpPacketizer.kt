package com.bragastudio.mobile.coremedia.bsp

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.locks.LockSupport
import kotlin.random.Random

/** Destino dos pacotes RTP montados. Abstração para o empacotador ser testável sem rede. */
fun interface RtpPacketSink {
    fun send(packet: ByteArray)
}

/**
 * Empacota NAL units H.264 (formato Annex-B, como o MediaCodec entrega em
 * modo Surface input) em pacotes RTP conforme RFC 6184.
 *
 * - NAL <= MTU útil: um pacote RTP por NAL (Single NAL Unit).
 * - NAL > MTU útil: fragmentado em FU-A (Fragmentation Unit type A).
 * - O bit marker só vai no ÚLTIMO pacote do último NAL do frame (access unit).
 *
 * Pacing (M21): um IDR de dezenas de KB vira dezenas de datagramas emitidos em rajada, o que
 * estoura buffers de Wi-Fi/receptor e gera perda justamente no keyframe. Depois de cada
 * [pacingBurstPackets] pacotes de um mesmo frame há uma pausa curta ([pacingPauseNanos]).
 * Frames pequenos (P-frames) nunca chegam ao limite e não sofrem pausa.
 *
 * Não faz retransmissão nem controle de congestionamento — isso é responsabilidade da camada de
 * controle (BspControlChannel) e do BspManager. Uso previsto: UMA thread (o drain do encoder).
 */
class H264RtpPacketizer(
    private val sink: RtpPacketSink,
    private val payloadType: Int = 96, // dynamic PT, padrão pra H.264 em RTP
    private val ssrc: Int = Random.nextInt(),
    initialSequenceNumber: Int = Random.nextInt(0xFFFF) and 0xFFFF,
    private val pacingBurstPackets: Int = DEFAULT_PACING_BURST,
    private val pacingPauseNanos: Long = DEFAULT_PACING_PAUSE_NANOS,
    private val pauser: (Long) -> Unit = { LockSupport.parkNanos(it) },
) {
    /** Construtor de produção: envia por UDP para [targetAddress]:[targetPort]. */
    constructor(
        socket: DatagramSocket,
        targetAddress: InetAddress,
        targetPort: Int,
        payloadType: Int = 96,
    ) : this(
        sink = RtpPacketSink { packet ->
            try {
                socket.send(DatagramPacket(packet, packet.size, targetAddress, targetPort))
            } catch (e: Exception) {
                // Perda de pacote de vídeo é esperada e não deve derrubar o stream — sem
                // retransmissão por design. O receptor pede keyframe pelo canal de controle.
                throw SendFailed(e)
            }
        },
        payloadType = payloadType,
    )

    /** Falha de envio engolida pelo [send] (contagem de pacotes não avança). */
    private class SendFailed(cause: Throwable) : RuntimeException(cause)

    companion object {
        private const val RTP_VERSION = 2
        const val CLOCK_RATE_HZ = 90_000L // clock padrão de vídeo em RTP

        // MTU típico de LAN (1500) menos IP(20)+UDP(8)+RTP(12) e uma margem de
        // segurança para redes com overhead extra (VPN, VLAN tag, etc).
        const val MAX_RTP_PAYLOAD = 1400
        const val DEFAULT_PACING_BURST = 12
        const val DEFAULT_PACING_PAUSE_NANOS = 400_000L // 0,4 ms
    }

    private var sequenceNumber = initialSequenceNumber and 0xFFFF

    @Volatile private var packetsSent = 0L

    @Volatile private var bytesSent = 0L

    // Pacotes já emitidos no frame corrente (para o pacing).
    private var packetsInFrame = 0

    val stats: Pair<Long, Long> get() = packetsSent to bytesSent

    /**
     * Envia um frame codificado (pode conter mais de um NAL, ex: SPS+PPS+IDR juntos). O marker
     * RTP vai só no último pacote do último NAL. [presentationTimeUs] vem do
     * MediaCodec.BufferInfo e é convertido para o clock de 90 kHz do RTP.
     */
    @Synchronized
    fun sendEncodedFrame(data: ByteArray, presentationTimeUs: Long) {
        val rtpTimestamp = toRtpTimestamp(presentationTimeUs)
        val nalUnits = splitAnnexBIntoNalUnits(data)
        packetsInFrame = 0
        for ((index, nal) in nalUnits.withIndex()) {
            val isLastNal = index == nalUnits.size - 1
            sendNalUnit(nal, rtpTimestamp, markerOnLast = isLastNal)
        }
    }

    private fun toRtpTimestamp(presentationTimeUs: Long): Long = (presentationTimeUs * CLOCK_RATE_HZ / 1_000_000L) and 0xFFFFFFFFL

    private fun sendNalUnit(nal: ByteArray, rtpTimestamp: Long, markerOnLast: Boolean) {
        if (nal.isEmpty()) return

        if (nal.size <= MAX_RTP_PAYLOAD) {
            // Single NAL Unit packet — o próprio NAL vira o payload do RTP.
            send(buildRtpPacket(nal, rtpTimestamp, marker = markerOnLast))
            return
        }

        // FU-A: fragmenta um NAL grande (ex: keyframe IDR) em vários pacotes RTP.
        val nalHeader = nal[0]
        val nalType = nalHeader.toInt() and 0x1F
        val nalRefIdc = nalHeader.toInt() and 0x60
        val fuIndicator = (nalRefIdc or 28).toByte() // tipo 28 = FU-A

        var offset = 1 // pula o header original do NAL (já vai dentro do FU header)
        var first = true
        while (offset < nal.size) {
            val remaining = nal.size - offset
            val chunkSize = minOf(MAX_RTP_PAYLOAD - 2, remaining) // -2: FU indicator + FU header
            val isLastFragment = offset + chunkSize >= nal.size

            var fuHeader = nalType.toByte()
            if (first) fuHeader = (fuHeader.toInt() or 0x80).toByte() // S bit
            if (isLastFragment) fuHeader = (fuHeader.toInt() or 0x40).toByte() // E bit

            val payload = ByteArray(2 + chunkSize)
            payload[0] = fuIndicator
            payload[1] = fuHeader
            System.arraycopy(nal, offset, payload, 2, chunkSize)

            val marker = isLastFragment && markerOnLast
            send(buildRtpPacket(payload, rtpTimestamp, marker = marker))

            offset += chunkSize
            first = false
        }
    }

    private fun buildRtpPacket(payload: ByteArray, timestamp: Long, marker: Boolean): ByteArray {
        val packet = ByteArray(12 + payload.size)
        packet[0] = (RTP_VERSION shl 6).toByte() // V=2, P=0, X=0, CC=0
        packet[1] = (((if (marker) 1 else 0) shl 7) or (payloadType and 0x7F)).toByte()
        packet[2] = ((sequenceNumber shr 8) and 0xFF).toByte()
        packet[3] = (sequenceNumber and 0xFF).toByte()
        packet[4] = ((timestamp shr 24) and 0xFF).toByte()
        packet[5] = ((timestamp shr 16) and 0xFF).toByte()
        packet[6] = ((timestamp shr 8) and 0xFF).toByte()
        packet[7] = (timestamp and 0xFF).toByte()
        packet[8] = ((ssrc shr 24) and 0xFF).toByte()
        packet[9] = ((ssrc shr 16) and 0xFF).toByte()
        packet[10] = ((ssrc shr 8) and 0xFF).toByte()
        packet[11] = (ssrc and 0xFF).toByte()
        System.arraycopy(payload, 0, packet, 12, payload.size)

        sequenceNumber = (sequenceNumber + 1) and 0xFFFF
        return packet
    }

    private fun send(packet: ByteArray) {
        try {
            sink.send(packet)
            packetsSent++
            bytesSent += packet.size
        } catch (e: SendFailed) {
            // perda esperada; ver construtor de produção
        }
        packetsInFrame++
        if (pacingBurstPackets > 0 && packetsInFrame % pacingBurstPackets == 0) {
            pauser(pacingPauseNanos)
        }
    }

    /** Separa um buffer Annex-B (0x00000001 ou 0x000001 como start code) em NAL units cruas. */
    internal fun splitAnnexBIntoNalUnits(data: ByteArray): List<ByteArray> {
        val startCodes = mutableListOf<Int>()
        var i = 0
        while (i < data.size - 3) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                startCodes.add(i + 3)
                i += 3
            } else if (i < data.size - 4 && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
            ) {
                startCodes.add(i + 4)
                i += 4
            } else {
                i++
            }
        }
        if (startCodes.isEmpty()) return listOf(data)

        val result = mutableListOf<ByteArray>()
        for (index in startCodes.indices) {
            val start = startCodes[index]
            val end = if (index + 1 < startCodes.size) {
                // volta até o início do próximo start code (3 ou 4 bytes antes)
                var nextStart = startCodes[index + 1]
                nextStart -= if (data.getOrNull(nextStart - 4) == 0.toByte()) 4 else 3
                nextStart
            } else {
                data.size
            }
            if (end > start) result.add(data.copyOfRange(start, end))
        }
        return result
    }

    fun close() {
        // O socket é de posse do BspManager — não fechamos aqui.
    }
}
