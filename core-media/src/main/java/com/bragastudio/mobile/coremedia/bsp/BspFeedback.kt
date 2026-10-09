package com.bragastudio.mobile.coremedia.bsp

/** Mensagens do canal de retorno UDP (receptor -> fonte, `feedbackPort`), 6.1. */
sealed class BspFeedbackMessage {
    /** Relatório do receptor (a cada 250 ms). Na Fase 1 só alimenta as estatísticas. */
    data class Report(
        val tsMs: Long,
        val highestSeqVideo: Int,
        /** Fração de perda do vídeo, 0..255 (255 = 100%). */
        val lossFracVideo: Int,
        val jitterMs: Int,
        val delayGradientUs: Int,
        val bufferMs: Int,
        val recvKbps: Int,
        val lossFracAudio: Int,
    ) : BspFeedbackMessage() {
        val videoLossPercent: Float get() = lossFracVideo * 100f / 255f
    }

    /** Retransmissão seletiva (RTCP Generic NACK). FASE 2: é lido e validado, mas ainda ignorado. */
    data class Nack(val ssrc: Long, val baseSeq: Int, val bitmask: Int) : BspFeedbackMessage()

    /** Pedido de quadro-chave (IDR). */
    data class Pli(val ssrc: Long) : BspFeedbackMessage()

    /** Eco do relatório de remetente (RTT/sincronismo). FASE 2: ignorado. */
    data object SrEcho : BspFeedbackMessage()
}

/**
 * Leitura do retorno binário little-endian com limites: cabeçalho comum de 6 bytes — `magic 'B','F'`,
 * `version 2`, `type`, `len` (u16, tamanho do CORPO em bytes) — e corpo de tamanho exato por tipo.
 * Qualquer desvio (magic, versão, tamanho, tipo desconhecido) devolve null: o pacote é descartado
 * sem efeito. Nunca lança.
 *
 * Observação sobre a especificação (6.1): ela não fixa a largura nem o significado de `len`; aqui é
 * `u16` com o número de bytes do corpo (sem os 6 do cabeçalho). Ver `.docs/BSP_ESPECIFICACAO.md`, 0.
 */
object BspFeedbackParser {
    const val HEADER_SIZE = 6
    const val VERSION = 2
    const val TYPE_REPORT = 1
    const val TYPE_NACK = 2
    const val TYPE_PLI = 3
    const val TYPE_SR_ECHO = 4
    const val REPORT_BODY = 16
    const val NACK_BODY = 8
    const val PLI_BODY = 4
    private const val MAX_SR_ECHO_BODY = 64

    fun parse(buf: ByteArray, length: Int): BspFeedbackMessage? {
        if (length < HEADER_SIZE || length > buf.size) return null
        if (buf[0] != 'B'.code.toByte() || buf[1] != 'F'.code.toByte()) return null
        if (u8(buf, 2) != VERSION) return null
        val type = u8(buf, 3)
        val bodyLen = u16(buf, 4)
        if (length != HEADER_SIZE + bodyLen) return null
        val b = HEADER_SIZE
        return when (type) {
            TYPE_REPORT -> if (bodyLen != REPORT_BODY) {
                null
            } else {
                BspFeedbackMessage.Report(
                    tsMs = u32(buf, b),
                    highestSeqVideo = u16(buf, b + 4),
                    lossFracVideo = u8(buf, b + 6),
                    jitterMs = u16(buf, b + 7),
                    delayGradientUs = u16(buf, b + 9).toShort().toInt(),
                    bufferMs = u16(buf, b + 11),
                    recvKbps = u16(buf, b + 13),
                    lossFracAudio = u8(buf, b + 15),
                )
            }

            TYPE_NACK -> if (bodyLen != NACK_BODY) null else BspFeedbackMessage.Nack(u32(buf, b), u16(buf, b + 4), u16(buf, b + 6))

            TYPE_PLI -> if (bodyLen != PLI_BODY) null else BspFeedbackMessage.Pli(u32(buf, b))

            TYPE_SR_ECHO -> if (bodyLen > MAX_SR_ECHO_BODY) null else BspFeedbackMessage.SrEcho

            else -> null
        }
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun u32(b: ByteArray, i: Int): Long = (u8(b, i).toLong()) or (u8(b, i + 1).toLong() shl 8) or (u8(b, i + 2).toLong() shl 16) or (u8(b, i + 3).toLong() shl 24)
}
