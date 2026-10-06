package com.bragastudio.mobile.coremedia.domain

import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Rebase de PTS com UMA origem comum para vídeo e áudio (A10).
 *
 * Antes cada trilha subtraía o próprio primeiro PTS, o que apagava o offset real
 * entre o primeiro frame de vídeo e o primeiro bloco de áudio (lip-sync deslocado).
 * Agora as duas trilhas (já no eixo System.nanoTime em µs) subtraem a MESMA origem —
 * o instante em que o take começou — e o MPEG4Writer preserva o deslocamento relativo
 * via edit list.
 *
 * Defesa: se o primeiro PTS de uma trilha estiver absurdamente longe da origem
 * (ex.: HDR direto da câmera, cujo timestamp pode vir de outro relógio — realtime vs
 * monotonic), aquela trilha recai no rebase pelo próprio primeiro PTS (comportamento
 * antigo), que ao menos mantém a duração correta.
 */
class TimelineRebaser(
    originUs: Long = 0L,
    private val maxSkewUs: Long = 10_000_000L,
) {
    @Volatile private var originUs: Long = originUs
    private var videoBaseUs: Long? = null
    private var audioBaseUs: Long? = null

    /** Fixa a origem comum (início do take) antes da primeira amostra; zera bases já calculadas. */
    @Synchronized
    fun anchor(newOriginUs: Long) {
        originUs = newOriginUs
        videoBaseUs = null
        audioBaseUs = null
        videoFellBack = false
        audioFellBack = false
    }

    var videoFellBack: Boolean = false
        private set
    var audioFellBack: Boolean = false
        private set

    /** Converte um PTS bruto do encoder em PTS 0-based do arquivo (nunca negativo). */
    @Synchronized
    fun rebase(isVideo: Boolean, ptsUs: Long): Long {
        val base = baseFor(isVideo, ptsUs)
        return (ptsUs - base).coerceAtLeast(0L)
    }

    private fun baseFor(isVideo: Boolean, ptsUs: Long): Long {
        val existing = if (isVideo) videoBaseUs else audioBaseUs
        if (existing != null) return existing
        val fallback = abs(ptsUs - originUs) > maxSkewUs
        val base = if (fallback) ptsUs else originUs
        if (isVideo) {
            videoBaseUs = base
            videoFellBack = fallback
        } else {
            audioBaseUs = base
            audioFellBack = fallback
        }
        return base
    }
}

/** Amostra codificada copiada para a fila pré-muxer. */
class EncodedSample(
    val isVideo: Boolean,
    val data: ByteArray,
    val ptsUs: Long,
    val flags: Int,
) {
    val isKeyFrame: Boolean get() = (flags and FLAG_KEY_FRAME) != 0

    companion object {
        /** Mesmo valor de MediaCodec.BUFFER_FLAG_KEY_FRAME (1); duplicado para testes em JVM. */
        const val FLAG_KEY_FRAME = 1
    }
}

/**
 * Fila de amostras acumuladas enquanto o MediaMuxer ainda não pôde iniciar (A10).
 *
 * Sem ela, as amostras que chegavam antes do muxer (todas, até o AAC emitir formato)
 * eram descartadas — inclusive o primeiro IDR. Tem teto de duração e de bytes; ao
 * estourar, descarta o vídeo mais antigo GOP a GOP (nunca deixa P-frame órfão na
 * frente) e pede novo keyframe.
 */
class PreMuxerQueue(
    private val maxDurationUs: Long = 2_000_000L,
    private val maxBytes: Long = 8L * 1024 * 1024,
) {
    private val video = ArrayDeque<EncodedSample>()
    private val audio = ArrayDeque<EncodedSample>()
    private var bytes = 0L
    private var waitingKeyFrame = false

    /** Marcado quando o descarte exige um novo keyframe do encoder. Lido (e zerado) por [consumeKeyFrameRequest]. */
    private var keyFrameRequested = false

    var droppedSamples: Int = 0
        private set

    val size: Int get() = video.size + audio.size
    val totalBytes: Long get() = bytes

    /** Devolve true (e zera) se o encoder deve receber um pedido de sync frame. */
    fun consumeKeyFrameRequest(): Boolean {
        val r = keyFrameRequested
        keyFrameRequested = false
        return r
    }

    /** @return false se a amostra foi rejeitada (vídeo aguardando keyframe). */
    fun offer(sample: EncodedSample): Boolean {
        if (sample.isVideo && waitingKeyFrame) {
            if (!sample.isKeyFrame) {
                droppedSamples++
                return false
            }
            waitingKeyFrame = false
        }
        (if (sample.isVideo) video else audio).addLast(sample)
        bytes += sample.data.size
        trimIfNeeded()
        return true
    }

    /** Esvazia a fila em ordem de PTS (intercalando vídeo e áudio). */
    fun drainOrdered(): List<EncodedSample> {
        val out = ArrayList<EncodedSample>(video.size + audio.size)
        while (video.isNotEmpty() || audio.isNotEmpty()) {
            val v = video.peekFirst()
            val a = audio.peekFirst()
            val next = when {
                v == null -> audio.pollFirst()
                a == null -> video.pollFirst()
                v.ptsUs <= a.ptsUs -> video.pollFirst()
                else -> audio.pollFirst()
            }
            out.add(next!!)
        }
        bytes = 0L
        return out
    }

    fun clear() {
        video.clear()
        audio.clear()
        bytes = 0L
    }

    private fun spanUs(): Long {
        val first = minOfNullable(video.peekFirst()?.ptsUs, audio.peekFirst()?.ptsUs)
        val last = maxOfNullable(video.peekLast()?.ptsUs, audio.peekLast()?.ptsUs)
        return if (first == null || last == null) 0L else last - first
    }

    private fun minOfNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

    private fun maxOfNullable(a: Long?, b: Long?): Long? = when {
        a == null -> b
        b == null -> a
        else -> maxOf(a, b)
    }

    private fun overLimit(): Boolean = bytes > maxBytes || spanUs() > maxDurationUs

    private fun trimIfNeeded() {
        while (overLimit() && size > 1) {
            // Prioriza descartar áudio quando ele é a trilha que mais atrasa o início; senão vídeo.
            if (audio.isNotEmpty() && (
                    video.isEmpty() ||
                        (audio.peekFirst()!!.ptsUs <= video.peekFirst()!!.ptsUs)
                    )
            ) {
                bytes -= audio.pollFirst()!!.data.size
                droppedSamples++
            } else if (video.isNotEmpty()) {
                dropOldestGop()
            } else {
                break
            }
        }
    }

    /** Remove o vídeo mais antigo até o próximo keyframe (exclusive). */
    private fun dropOldestGop() {
        bytes -= video.pollFirst()!!.data.size
        droppedSamples++
        while (video.isNotEmpty() && !video.peekFirst()!!.isKeyFrame) {
            bytes -= video.pollFirst()!!.data.size
            droppedSamples++
        }
        if (video.isEmpty()) {
            // Nenhum keyframe restante: rejeita vídeo até o próximo IDR e pede um.
            waitingKeyFrame = true
            keyFrameRequested = true
        }
    }
}

enum class MuxDecision { WAIT, START_WITH_AUDIO, START_VIDEO_ONLY }

/**
 * Decide quando iniciar o muxer (A10). Depois de MediaMuxer.start() não dá para
 * adicionar trilha, então a decisão de seguir sem áudio é tomada por timeout, nunca
 * por um atraso fixo curto.
 */
object TrackSyncPolicy {
    const val DEFAULT_AUDIO_TIMEOUT_MS = 2_000L

    fun decide(
        hasVideoFormat: Boolean,
        hasAudioFormat: Boolean,
        audioExpected: Boolean,
        msSinceStart: Long,
        audioTimeoutMs: Long = DEFAULT_AUDIO_TIMEOUT_MS,
    ): MuxDecision = when {
        !hasVideoFormat -> MuxDecision.WAIT
        !audioExpected -> MuxDecision.START_VIDEO_ONLY
        hasAudioFormat -> MuxDecision.START_WITH_AUDIO
        msSinceStart >= audioTimeoutMs -> MuxDecision.START_VIDEO_ONLY
        else -> MuxDecision.WAIT
    }
}

/** Matemática de PCM 16-bit (A10: fatiar pela capacity do input buffer e calcular PTS). */
object PcmMath {
    private const val BYTES_PER_SAMPLE = 2

    fun frameBytes(channels: Int): Int = channels * BYTES_PER_SAMPLE

    fun durationUs(byteCount: Int, channels: Int, sampleRate: Int): Long {
        val frame = frameBytes(channels)
        if (frame <= 0 || sampleRate <= 0) return 0L
        return (byteCount / frame).toLong() * 1_000_000L / sampleRate
    }

    /**
     * Divide [total] bytes em fatias de no máximo [capacity], alinhadas a frames
     * inteiros (nunca corta um frame estéreo ao meio). Capacity menor que um frame
     * devolve lista vazia (nada pode ser enfileirado).
     */
    fun sliceSizes(total: Int, capacity: Int, channels: Int): List<Int> {
        val frame = frameBytes(channels)
        val maxSlice = (capacity / frame) * frame
        if (total <= 0 || maxSlice <= 0) return emptyList()
        val out = ArrayList<Int>()
        var remaining = total
        while (remaining > 0) {
            val n = minOf(maxSlice, remaining)
            out.add(n)
            remaining -= n
        }
        return out
    }
}

/** Política de espaço livre durante o take (M15). */
object SpaceGuard {
    const val MIN_FREE_BYTES = 200L * 1024 * 1024

    fun isLow(availableBytes: Long, floorBytes: Long = MIN_FREE_BYTES): Boolean = availableBytes < floorBytes
}
