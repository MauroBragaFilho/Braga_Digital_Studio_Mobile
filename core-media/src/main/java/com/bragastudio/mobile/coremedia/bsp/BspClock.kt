package com.bragastudio.mobile.coremedia.bsp

/**
 * Relógio RTP de um fluxo: converte um instante de CAPTURA (nanossegundos no eixo monotônico
 * `System.nanoTime`, o mesmo do áudio gravado e do vídeo depois de [PtsDomain]) para o timestamp RTP
 * de 32 bits. Vídeo e áudio usam a MESMA origem [originNs]; só mudam a taxa e a base aleatória.
 * Isso mantém a sincronia A/V no receptor: com o SR (NTP <-> RTP, a cada 1 s) cada fluxo vira
 * tempo de parede e os dois se alinham pela captura (8).
 */
class RtpClock(val clockRateHz: Int, val rtpBase: Long, val originNs: Long) {
    /** Timestamp RTP (32 bits) do instante [ns]. Sem estouro: separa segundos e resto. */
    fun rtpAt(ns: Long): Long {
        val delta = ns - originNs
        val seconds = Math.floorDiv(delta, 1_000_000_000L)
        val remainder = Math.floorMod(delta, 1_000_000_000L)
        val ticks = seconds * clockRateHz + remainder * clockRateHz / 1_000_000_000L
        return (rtpBase + ticks) and 0xFFFFFFFFL
    }
}

/**
 * Descobre em que relógio estão os PTS do vídeo e os converte para o eixo de `System.nanoTime`. O PTS
 * que sai do codec é o carimbo do quadro da câmera (eglPresentationTimeANDROID): conforme o sensor ele
 * está no relógio MONOTÔNICO (`nanoTime`) ou no BOOTTIME (`elapsedRealtimeNanos`, que também conta o
 * tempo de suspensão). No primeiro quadro escolhe o relógio MAIS PRÓXIMO do PTS (a latência do
 * pipeline é de dezenas de ms; a diferença entre os relógios é de segundos quando o aparelho já
 * dormiu). Se nenhum chegar perto (mais de [maxSkewNs]), assume o PTS no instante da chegada e fixa o
 * deslocamento (o mesmo critério de reserva do `TimelineRebaser` da gravação).
 */
class PtsDomain(
    private val monotonicNs: () -> Long,
    private val boottimeNs: () -> Long,
    private val maxSkewNs: Long = 10_000_000_000L,
) {
    private var offsetNs = 0L
    private var decided = false

    /** Qual critério foi escolhido (para log/diagnóstico). */
    var mode: Mode = Mode.UNDECIDED
        private set

    enum class Mode { UNDECIDED, MONOTONIC, BOOTTIME, FIRST_FRAME }

    /** Converte [ptsUs] (µs do codec) em ns no eixo monotônico. */
    fun toMonotonicNs(ptsUs: Long): Long {
        val ptsNs = ptsUs * 1000L
        if (!decided) decide(ptsNs)
        return ptsNs + offsetNs
    }

    private fun decide(ptsNs: Long) {
        val mono = monotonicNs()
        val boot = boottimeNs()
        val dMono = Math.abs(ptsNs - mono)
        val dBoot = Math.abs(ptsNs - boot)
        when {
            dMono <= dBoot && dMono <= maxSkewNs -> {
                offsetNs = 0
                mode = Mode.MONOTONIC
            }

            dBoot < dMono && dBoot <= maxSkewNs -> {
                offsetNs = mono - boot
                mode = Mode.BOOTTIME
            }

            else -> {
                offsetNs = mono - ptsNs
                mode = Mode.FIRST_FRAME
            }
        }
        decided = true
    }
}

/**
 * Alisa os timestamps RTP do áudio. O PTS que o encoder devolve oscila alguns ms (blocos de 20 ms do
 * `AudioRecord` contra quadros AAC de 1024 amostras); entregá-lo cru faria o receptor ver saltos. Aqui
 * o timestamp avança EXATAMENTE 1024 por quadro e é puxado devagar (1/8 do erro por quadro) para o PTS
 * real, o que segue a deriva do relógio de áudio sem ruído; uma descontinuidade maior que
 * [resyncThreshold] ticks (perda de amostras, pausa) ressincroniza de uma vez.
 */
class AudioTimestampSmoother(private val samplesPerFrame: Int = AacConfig.SAMPLES_PER_FRAME, private val resyncThreshold: Long = 960) {
    private var expected = 0L
    private var started = false

    /** [ptsRtp] = timestamp RTP (32 bits) calculado do PTS do quadro; devolve o timestamp a usar. */
    fun next(ptsRtp: Long): Long {
        if (!started) {
            started = true
            expected = ptsRtp
        } else {
            val diff = signedDiff32(ptsRtp, expected)
            expected = if (Math.abs(diff) > resyncThreshold) ptsRtp else expected + diff / 8
        }
        val out = expected and 0xFFFFFFFFL
        expected = (expected + samplesPerFrame) and 0xFFFFFFFFL
        return out
    }

    private fun signedDiff32(a: Long, b: Long): Long = ((a - b) shl 32) shr 32
}

/** Contagens de amostras em tempo de captura (PTS de áudio no eixo de `nanoTime`, como na gravação). */
object AudioPts {
    /**
     * PTS (µs) do INÍCIO de um bloco PCM recém-lido: o bloco acabou de chegar, então começou há
     * [blockDurationUs]. Nunca recua antes do fim do bloco anterior ([lastEndUs]): o jitter do
     * `AudioRecord` não pode sobrepor amostras.
     */
    fun blockStartUs(nowUs: Long, blockDurationUs: Long, lastEndUs: Long): Long = maxOf(nowUs - blockDurationUs, lastEndUs)
}
