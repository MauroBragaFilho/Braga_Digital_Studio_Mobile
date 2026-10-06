package com.bragastudio.mobile.coremedia.domain

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer

/** Como o muxer terminou. */
internal enum class MuxOutcome {
    /** Arquivo finalizado com `muxer.stop()` sem erro. */
    OK,

    /** O muxer rodou, mas `stop()` falhou (ex.: nenhuma amostra gravada): arquivo suspeito. */
    CORRUPT,

    /** O muxer nunca chegou a iniciar: não há arquivo utilizável. */
    NEVER_STARTED,
}

/**
 * Porta única de escrita no [MediaMuxer] (A10). Responsabilidades:
 *  - iniciar o muxer SÓ com vídeo se o áudio não aparecer em [audioTimeoutMs]
 *    (microfone ocupado / AudioRecord falhou), em vez de ficar esperando para sempre;
 *  - guardar numa fila pré-muxer (cópia) tudo que chega antes do start;
 *  - aplicar o rebase de PTS comum às duas trilhas;
 *  - serializar o acesso ao muxer (dois drains, uma única escrita por vez).
 *
 * Toda a lógica de decisão está em [TrackSyncPolicy]/[PreMuxerQueue]/[TimelineRebaser]
 * (puras, testadas); esta classe só liga isso ao MediaMuxer.
 */
internal class MuxerGate(
    private val muxer: MediaMuxer,
    private val audioExpected: Boolean,
    private val rebaser: TimelineRebaser,
    private val queue: PreMuxerQueue,
    private val audioTimeoutMs: Long,
    private val onAudioMissing: () -> Unit,
    private val onKeyFrameNeeded: () -> Unit,
) {
    // Instante em que a gravação realmente começou (arm). Antes disso o timeout do áudio não corre.
    @Volatile private var armedAtNs = 0L
    private val lock = Any()

    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var videoTrack = -1
    private var audioTrack = -1

    @Volatile var started = false
        private set

    @Volatile var failed = false
        private set

    @Volatile var videoOnly = false
        private set

    private var released = false
    private val writeInfo = MediaCodec.BufferInfo()

    /** Marca o início do take: a partir daqui o timeout do áudio passa a contar. */
    fun arm() {
        armedAtNs = System.nanoTime()
    }

    fun onFormat(isVideo: Boolean, format: MediaFormat): Unit = synchronized(lock) {
        if (started || failed) return@synchronized
        if (isVideo) videoFormat = format else audioFormat = format
        evaluateLocked()
    }

    /** Chamado periodicamente pelo drain de vídeo: dispara o timeout do áudio. */
    fun poll(): Unit = synchronized(lock) {
        if (started || failed) return@synchronized
        evaluateLocked()
    }

    /**
     * Entrega uma amostra codificada. [buffer] está posicionado em [info].offset/size e só é
     * válido até o `releaseOutputBuffer` — por isso copiamos quando vamos enfileirar.
     */
    fun onSample(isVideo: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo): Unit = synchronized(lock) {
        if (failed || info.size <= 0) return@synchronized
        if (started) {
            if (!isVideo && audioTrack < 0) return@synchronized // áudio descartado (muxer só-vídeo)
            writeLocked(isVideo, buffer, info.offset, info.size, info.presentationTimeUs, info.flags)
            return@synchronized
        }
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val copy = ByteArray(info.size)
        buffer.get(copy)
        queue.offer(EncodedSample(isVideo, copy, info.presentationTimeUs, info.flags))
        if (queue.consumeKeyFrameRequest()) {
            try {
                onKeyFrameNeeded()
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao pedir keyframe", e)
            }
        }
    }

    private fun evaluateLocked() {
        val elapsedMs = if (armedAtNs == 0L) 0L else (System.nanoTime() - armedAtNs) / 1_000_000L
        when (
            TrackSyncPolicy.decide(
                hasVideoFormat = videoFormat != null,
                hasAudioFormat = audioFormat != null,
                audioExpected = audioExpected,
                msSinceStart = elapsedMs,
                audioTimeoutMs = audioTimeoutMs,
            )
        ) {
            MuxDecision.WAIT -> Unit
            MuxDecision.START_WITH_AUDIO -> startLocked(withAudio = true)
            MuxDecision.START_VIDEO_ONLY -> startLocked(withAudio = false)
        }
    }

    private fun startLocked(withAudio: Boolean) {
        try {
            videoTrack = muxer.addTrack(videoFormat!!)
            if (withAudio) audioTrack = muxer.addTrack(audioFormat!!)
            muxer.start()
            started = true
            videoOnly = !withAudio
            Log.d(TAG, "Muxer iniciado (${if (withAudio) "vídeo+áudio" else "SÓ VÍDEO"}); fila pré-muxer: ${queue.size} amostras")
            if (!withAudio && audioExpected) {
                try {
                    onAudioMissing()
                } catch (e: Exception) {
                    Log.w(TAG, "onAudioMissing falhou", e)
                }
            }
            // Despeja a fila em ordem de PTS.
            for (s in queue.drainOrdered()) {
                if (!s.isVideo && audioTrack < 0) continue
                writeLocked(s.isVideo, ByteBuffer.wrap(s.data), 0, s.data.size, s.ptsUs, s.flags)
            }
        } catch (e: Exception) {
            // Falha ao iniciar OU ao despejar a fila (ex.: disco cheio): propaga para o drain
            // reportar como erro fatal em vez de deixar o take "gravando" sem escrever nada.
            if (!started) failed = true
            Log.e(TAG, "Falha ao iniciar o muxer", e)
            throw e
        }
    }

    private fun writeLocked(isVideo: Boolean, data: ByteBuffer, offset: Int, size: Int, ptsUs: Long, flags: Int) {
        val track = if (isVideo) videoTrack else audioTrack
        if (track < 0) return
        data.position(offset)
        data.limit(offset + size)
        // O offset do BufferInfo é relativo ao início do buffer entregue ao muxer.
        writeInfo.set(offset, size, rebaser.rebase(isVideo, ptsUs), flags and CODEC_CONFIG_MASK.inv())
        muxer.writeSampleData(track, data, writeInfo)
    }

    /**
     * Fecha o arquivo. Idempotente em relação ao muxer (nunca chama stop/release duas vezes).
     * Não lança: erros viram [MuxOutcome].
     */
    fun finish(): MuxOutcome = synchronized(lock) {
        if (released) return@synchronized if (started && !failed) MuxOutcome.OK else MuxOutcome.NEVER_STARTED
        released = true
        val outcome = when {
            failed && !started -> MuxOutcome.NEVER_STARTED

            !started -> MuxOutcome.NEVER_STARTED

            else -> try {
                muxer.stop()
                if (failed) MuxOutcome.CORRUPT else MuxOutcome.OK
            } catch (e: Exception) {
                Log.e(TAG, "muxer.stop() falhou", e)
                MuxOutcome.CORRUPT
            }
        }
        try {
            muxer.release()
        } catch (e: Exception) {
            Log.w(TAG, "muxer.release() falhou", e)
        }
        queue.clear()
        outcome
    }

    /** Libera sem tentar finalizar (prepare abortado). Idempotente. */
    fun abort(): Unit = synchronized(lock) {
        if (released) return@synchronized
        released = true
        try {
            if (started) muxer.stop()
        } catch (e: Exception) {
            // ignora: abort é best-effort
        }
        try {
            muxer.release()
        } catch (e: Exception) {
            // ignora: abort é best-effort
        }
        queue.clear()
    }

    private companion object {
        const val TAG = "MuxerGate"
        const val CODEC_CONFIG_MASK = MediaCodec.BUFFER_FLAG_CODEC_CONFIG
    }
}
