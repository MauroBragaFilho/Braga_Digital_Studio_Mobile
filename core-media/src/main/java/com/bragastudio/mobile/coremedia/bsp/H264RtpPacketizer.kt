package com.bragastudio.mobile.coremedia.bsp

import kotlin.random.Random

/**
 * Destino dos pacotes RTP montados. O buffer [packet] é REUTILIZADO a cada chamada (só os primeiros
 * [length] bytes valem): quem precisar guardar o pacote deve copiá-lo. Abstração que deixa os
 * empacotadores testáveis sem rede e sem alocar por pacote.
 */
fun interface RtpPacketSink {
    fun send(packet: ByteArray, length: Int)
}

internal const val RTP_VERSION = 2
internal const val RTP_HEADER_SIZE = 12

/** Escreve o cabeçalho RTP padrão de 12 bytes (V=2, P=0, X=0, CC=0) em [buf] no início. */
internal fun writeRtpHeader(buf: ByteArray, marker: Boolean, payloadType: Int, seq: Int, timestamp: Long, ssrc: Int) {
    buf[0] = (RTP_VERSION shl 6).toByte()
    buf[1] = (((if (marker) 1 else 0) shl 7) or (payloadType and 0x7F)).toByte()
    buf[2] = (seq ushr 8).toByte()
    buf[3] = seq.toByte()
    buf[4] = (timestamp ushr 24).toByte()
    buf[5] = (timestamp ushr 16).toByte()
    buf[6] = (timestamp ushr 8).toByte()
    buf[7] = timestamp.toByte()
    buf[8] = (ssrc ushr 24).toByte()
    buf[9] = (ssrc ushr 16).toByte()
    buf[10] = (ssrc ushr 8).toByte()
    buf[11] = ssrc.toByte()
}

/**
 * Empacota um access unit H.264 (Annex-B, como o MediaCodec entrega com entrada por Surface) em RTP
 * conforme a RFC 6184, modo não entrelaçado (packetization-mode=1):
 *
 * - NAL <= [maxPayload]: um pacote (Single NAL Unit);
 * - NAL maior: fragmentado em FU-A;
 * - SPS+PPS: agregados em UM pacote STAP-A antes de cada IDR (5.2). Se o encoder não os repetiu no
 *   próprio quadro (ou se o quadro é um IDR sem eles), usa os guardados por [setParameterSets];
 * - o bit marker só vai no ÚLTIMO pacote do access unit.
 *
 * Caminho quente sem alocação: um buffer de pacote e as tabelas de NAL são reaproveitados (as tabelas
 * crescem só se um quadro tiver mais NALs que o já visto). O pacing e a criptografia ficam FORA daqui
 * (o [sink] só recebe o RTP em claro). Uso previsto: UMA thread (a de saída do encoder).
 */
class H264RtpPacketizer(
    private val sink: RtpPacketSink,
    private val payloadType: Int = 96,
    private val ssrc: Int = Random.nextInt(1, Int.MAX_VALUE),
    initialSequenceNumber: Int = Random.nextInt(0x10000),
    private val maxPayload: Int = MAX_RTP_PAYLOAD,
) {
    companion object {
        const val CLOCK_RATE_HZ = 90_000L

        /** Payload máximo por pacote (5.1): 12 de RTP + 1200 + 16 da tag AEAD = 1228 B, abaixo do limite de 1252. */
        const val MAX_RTP_PAYLOAD = 1200
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
        private const val NAL_IDR = 5
        private const val STAP_A = 24
        private const val FU_A = 28
    }

    init {
        require(maxPayload in 64..1400) { "payload fora da faixa" }
    }

    private var seq = initialSequenceNumber and 0xFFFF
    private val packet = ByteArray(RTP_HEADER_SIZE + maxPayload)

    private var nalStart = IntArray(32)
    private var nalEnd = IntArray(32)
    private var nalCount = 0

    @Volatile private var cachedSps: ByteArray? = null

    @Volatile private var cachedPps: ByteArray? = null

    @Volatile var packetsSent = 0L
        private set

    @Volatile var bytesSent = 0L
        private set

    val currentSsrc: Int get() = ssrc

    /** Próximo número de sequência (para testes e diagnóstico). */
    val nextSequenceNumber: Int get() = seq

    /** Guarda SPS e PPS crus (sem start code) para repetir antes de cada IDR que chegar sem eles. */
    fun setParameterSets(sps: ByteArray?, pps: ByteArray?) {
        cachedSps = sps?.copyOf()
        cachedPps = pps?.copyOf()
    }

    /** Conveniência: converte o PTS do MediaCodec (µs) para o relógio de 90 kHz e empacota todo o [data]. */
    fun sendEncodedFrame(data: ByteArray, presentationTimeUs: Long) = sendAccessUnit(data, 0, data.size, (presentationTimeUs * CLOCK_RATE_HZ / 1_000_000L) and 0xFFFFFFFFL)

    /**
     * Empacota o access unit em `data[offset until offset+length]`. [rtpTimestamp] (90 kHz, 32 bits) é
     * o mesmo para todos os pacotes do quadro.
     */
    fun sendAccessUnit(data: ByteArray, offset: Int, length: Int, rtpTimestamp: Long) {
        if (length <= 0) return
        scanNals(data, offset, length)
        if (nalCount == 0) return

        var leading = 0
        while (leading < nalCount && isParameterSet(data[nalStart[leading]])) leading++
        var hasIdr = false
        for (i in leading until nalCount) if ((data[nalStart[i]].toInt() and 0x1F) == NAL_IDR) hasIdr = true

        val ts = rtpTimestamp and 0xFFFFFFFFL
        var first = 0
        if (leading > 0) {
            // SPS/PPS no próprio quadro (PREPEND_HEADER_TO_SYNC_FRAMES): agrega num STAP-A
            if (leading == nalCount) {
                sendParameterSets(data, 0, leading, ts, marker = true)
                return
            }
            sendParameterSets(data, 0, leading, ts, marker = false)
            first = leading
        } else if (hasIdr) {
            val sps = cachedSps
            val pps = cachedPps
            if (sps != null && pps != null) sendCachedParameterSets(sps, pps, ts)
        }
        for (i in first until nalCount) {
            sendNal(data, nalStart[i], nalEnd[i], ts, marker = i == nalCount - 1)
        }
    }

    private fun isParameterSet(header: Byte): Boolean {
        val type = header.toInt() and 0x1F
        return type == NAL_SPS || type == NAL_PPS
    }

    /** Preenche a tabela de NALs do buffer Annex-B (3 ou 4 bytes de start code); sem start code vira um NAL só. */
    private fun scanNals(data: ByteArray, offset: Int, length: Int) {
        nalCount = 0
        val end = offset + length
        var i = offset
        while (i + 2 < end) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                if (nalCount > 0) closeNal(data, i)
                addNalStart(i + 3)
                i += 3
            } else {
                i++
            }
        }
        if (nalCount == 0) {
            addNalStart(offset)
            nalEnd[0] = end
            nalCount = 1
            return
        }
        closeNal(data, end)
        // descarta NAL vazio (start codes consecutivos)
        var w = 0
        for (r in 0 until nalCount) {
            if (nalEnd[r] > nalStart[r]) {
                nalStart[w] = nalStart[r]
                nalEnd[w] = nalEnd[r]
                w++
            }
        }
        nalCount = w
    }

    private fun addNalStart(pos: Int) {
        if (nalCount == nalStart.size) {
            nalStart = nalStart.copyOf(nalCount * 2)
            nalEnd = nalEnd.copyOf(nalCount * 2)
        }
        nalStart[nalCount] = pos
        nalEnd[nalCount] = pos
        nalCount++
    }

    /** Fecha o último NAL em [pos] tirando os zeros finais (parte do start code de 4 bytes). */
    private fun closeNal(data: ByteArray, pos: Int) {
        var e = pos
        val s = nalStart[nalCount - 1]
        while (e > s && data[e - 1] == 0.toByte()) e--
        nalEnd[nalCount - 1] = e
    }

    private fun sendParameterSets(data: ByteArray, from: Int, to: Int, ts: Long, marker: Boolean) {
        var total = 1
        for (i in from until to) total += 2 + (nalEnd[i] - nalStart[i])
        if (total > maxPayload) {
            // não cabe junto: envia um a um
            for (i in from until to) sendNal(data, nalStart[i], nalEnd[i], ts, marker = marker && i == to - 1)
            return
        }
        var nri = 0
        for (i in from until to) nri = maxOf(nri, data[nalStart[i]].toInt() and 0x60)
        var p = RTP_HEADER_SIZE
        packet[p++] = (nri or STAP_A).toByte()
        for (i in from until to) {
            val len = nalEnd[i] - nalStart[i]
            packet[p++] = (len ushr 8).toByte()
            packet[p++] = len.toByte()
            System.arraycopy(data, nalStart[i], packet, p, len)
            p += len
        }
        emit(p, ts, marker)
    }

    private fun sendCachedParameterSets(sps: ByteArray, pps: ByteArray, ts: Long) {
        val total = 1 + 2 + sps.size + 2 + pps.size
        if (sps.isEmpty() || pps.isEmpty()) return
        if (total > maxPayload) {
            sendNalBytes(sps, ts)
            sendNalBytes(pps, ts)
            return
        }
        val nri = maxOf(sps[0].toInt() and 0x60, pps[0].toInt() and 0x60)
        var p = RTP_HEADER_SIZE
        packet[p++] = (nri or STAP_A).toByte()
        packet[p++] = (sps.size ushr 8).toByte()
        packet[p++] = sps.size.toByte()
        System.arraycopy(sps, 0, packet, p, sps.size)
        p += sps.size
        packet[p++] = (pps.size ushr 8).toByte()
        packet[p++] = pps.size.toByte()
        System.arraycopy(pps, 0, packet, p, pps.size)
        p += pps.size
        emit(p, ts, false)
    }

    private fun sendNalBytes(nal: ByteArray, ts: Long) = sendNal(nal, 0, nal.size, ts, marker = false)

    private fun sendNal(data: ByteArray, start: Int, end: Int, ts: Long, marker: Boolean) {
        val size = end - start
        if (size <= 0) return
        if (size <= maxPayload) {
            System.arraycopy(data, start, packet, RTP_HEADER_SIZE, size)
            emit(RTP_HEADER_SIZE + size, ts, marker)
            return
        }
        // FU-A: o cabeçalho original do NAL vai dentro do indicador/cabeçalho de fragmento
        val header = data[start].toInt()
        val nalType = header and 0x1F
        val fuIndicator = ((header and 0x60) or FU_A).toByte()
        var offset = start + 1
        var firstFragment = true
        while (offset < end) {
            val chunk = minOf(maxPayload - 2, end - offset)
            val last = offset + chunk >= end
            var fuHeader = nalType
            if (firstFragment) fuHeader = fuHeader or 0x80
            if (last) fuHeader = fuHeader or 0x40
            packet[RTP_HEADER_SIZE] = fuIndicator
            packet[RTP_HEADER_SIZE + 1] = fuHeader.toByte()
            System.arraycopy(data, offset, packet, RTP_HEADER_SIZE + 2, chunk)
            emit(RTP_HEADER_SIZE + 2 + chunk, ts, marker && last)
            offset += chunk
            firstFragment = false
        }
    }

    private fun emit(length: Int, ts: Long, marker: Boolean) {
        writeRtpHeader(packet, marker, payloadType, seq, ts, ssrc)
        seq = (seq + 1) and 0xFFFF
        sink.send(packet, length)
        packetsSent++
        bytesSent += length
    }
}
