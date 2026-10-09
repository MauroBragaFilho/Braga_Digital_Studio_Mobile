package com.bragastudio.mobile.coremedia.bsp

import kotlin.random.Random

/** Configuração AAC-LC do BSP (8): 48 kHz estéreo. */
object AacConfig {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val BITRATE_BPS = 96_000
    const val SAMPLES_PER_FRAME = 1024

    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    /**
     * `AudioSpecificConfig` de 2 bytes do AAC-LC (ISO 14496-3): 5 bits de tipo de objeto (2 = LC), 4 de
     * índice de frequência, 4 de configuração de canais, 3 zeros (`frameLengthFlag`, `dependsOnCoreCoder`,
     * `extensionFlag`). Para 48 kHz estéreo vale `11 90`. null se a taxa não for padrão.
     */
    fun audioSpecificConfig(sampleRate: Int = SAMPLE_RATE, channels: Int = CHANNELS): ByteArray? {
        val index = RATES.indexOf(sampleRate)
        if (index < 0 || channels !in 1..7) return null
        val bits = (2 shl 11) or (index shl 7) or (channels shl 3)
        return byteArrayOf((bits ushr 8).toByte(), bits.toByte())
    }
}

/**
 * Empacota quadros AAC-LC em RTP conforme a RFC 3640, modo `AAC-hbr` (`sizeLength=13; indexLength=3;
 * indexDeltaLength=3`), UM quadro de 1024 amostras por pacote (5.2). Payload:
 *
 * ```
 * AU-headers-length (16 bits) = 16 | AU-header: size (13 bits) + index (3 bits = 0) | quadro AAC
 * ```
 * O marker vai em todo pacote (cada pacote tem um AU completo). O timestamp RTP conta amostras a
 * 48 kHz. Sem alocação por pacote; uma thread só.
 */
class AacRtpPacketizer(
    private val sink: RtpPacketSink,
    private val payloadType: Int = 97,
    private val ssrc: Int = Random.nextInt(1, Int.MAX_VALUE),
    initialSequenceNumber: Int = Random.nextInt(0x10000),
) {
    companion object {
        /** Maior AU que cabe no campo de 13 bits do AU-header. */
        const val MAX_AU_SIZE = 8191
        const val AU_HEADERS_LEN = 4
    }

    private var seq = initialSequenceNumber and 0xFFFF
    private var packet = ByteArray(RTP_HEADER_SIZE + AU_HEADERS_LEN + 512)

    @Volatile var packetsSent = 0L
        private set

    @Volatile var bytesSent = 0L
        private set

    val currentSsrc: Int get() = ssrc

    /** Envia um quadro AAC cru (sem ADTS) em `data[offset until offset+length]`; descarta se vazio ou maior que 8191 B. */
    fun sendAccessUnit(data: ByteArray, offset: Int, length: Int, rtpTimestamp: Long) {
        if (length <= 0 || length > MAX_AU_SIZE) return
        val total = RTP_HEADER_SIZE + AU_HEADERS_LEN + length
        if (packet.size < total) packet = ByteArray(total)
        writeRtpHeader(packet, true, payloadType, seq, rtpTimestamp and 0xFFFFFFFFL, ssrc)
        seq = (seq + 1) and 0xFFFF
        var p = RTP_HEADER_SIZE
        packet[p++] = 0x00 // AU-headers-length = 16 bits (um AU-header)
        packet[p++] = 0x10
        packet[p++] = (length ushr 5).toByte() // size (13 bits)...
        packet[p++] = ((length and 0x1F) shl 3).toByte() // ...e index (3 bits) = 0
        System.arraycopy(data, offset, packet, p, length)
        sink.send(packet, total)
        packetsSent++
        bytesSent += total
    }
}
