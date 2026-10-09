package com.bragastudio.mobile.coremedia.bsp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.bragastudio.mobile.coremedia.domain.PcmMath
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "BspAudioEncoder"

/**
 * Codificador AAC-LC 48 kHz estéreo 96 kbps do BSP (8) pelo codec do sistema. Modo assíncrono: os
 * índices de buffer de entrada que o codec libera ficam numa pilha e [feed] (chamado na thread do
 * AudioRecord) NUNCA bloqueia: sem buffer livre, o bloco é descartado e contado. Cada quadro AAC
 * pronto (1024 amostras) é entregue a [Listener] na thread do codec, com o PTS do instante de captura
 * (o mesmo eixo `System.nanoTime` do vídeo, ver [AudioPts]).
 */
class BspAudioEncoder(private val listener: Listener) {
    interface Listener {
        /** Quadro AAC cru em `data[0 until length]` (buffer reaproveitado), com PTS em µs. */
        fun onAccessUnit(data: ByteArray, length: Int, presentationTimeUs: Long)

        fun onError(message: String)
    }

    @Volatile private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null

    @Volatile private var stopped = true
    private val freeInputs = ArrayDeque<Int>()
    private var auBuffer = ByteArray(2048)
    private val dropped = AtomicLong(0)

    /** Blocos PCM descartados por falta de buffer de entrada. */
    val droppedBlocks: Long get() = dropped.get()

    @Synchronized
    fun start() {
        check(stopped) { "encoder de áudio BSP já iniciado" }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AacConfig.SAMPLE_RATE, AacConfig.CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AacConfig.BITRATE_BPS)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val t = HandlerThread("bsp-aenc").also { it.start() }
        thread = t
        try {
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec = c
            c.setCallback(callback, Handler(t.looper))
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            synchronized(freeInputs) { freeInputs.clear() }
            stopped = false
            c.start()
            Log.i(TAG, "Encoder AAC-LC iniciado (48 kHz estéreo, 96 kbps)")
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    /**
     * Entrega [byteCount] bytes de PCM 16-bit intercalado (48 kHz estéreo) de [pcm], com [ptsUs] do
     * início do bloco. Devolve false se o bloco foi descartado (encoder parado ou sem buffer).
     */
    fun feed(pcm: ByteArray, byteCount: Int, ptsUs: Long): Boolean {
        val c = codec ?: return false
        if (stopped) return false
        var offset = 0
        var pts = ptsUs
        while (offset < byteCount) {
            val index = synchronized(freeInputs) { freeInputs.removeLastOrNull() }
            if (index == null) {
                dropped.incrementAndGet()
                return false
            }
            try {
                val input = c.getInputBuffer(index)
                if (input == null) {
                    c.queueInputBuffer(index, 0, 0, pts, 0)
                    return false
                }
                input.clear()
                val frameBytes = PcmMath.frameBytes(AacConfig.CHANNELS)
                val slice = minOf(byteCount - offset, (input.capacity() / frameBytes) * frameBytes)
                input.put(pcm, offset, slice)
                c.queueInputBuffer(index, 0, slice, pts, 0)
                pts += PcmMath.durationUs(slice, AacConfig.CHANNELS, AacConfig.SAMPLE_RATE)
                offset += slice
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao enfileirar PCM: ${e.javaClass.simpleName}")
                return false
            }
        }
        return true
    }

    @Synchronized
    fun stop() {
        stopped = true
        val c = codec
        codec = null
        if (c != null) {
            try {
                c.stop()
            } catch (_: Exception) {
                // já parado
            }
            try {
                c.release()
            } catch (e: Exception) {
                Log.w(TAG, "Ao liberar o encoder de áudio: ${e.javaClass.simpleName}")
            }
        }
        synchronized(freeInputs) { freeInputs.clear() }
        thread?.quitSafely()
        thread = null
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (stopped) return
            synchronized(freeInputs) { freeInputs.addLast(index) }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                if (stopped) return
                val out = codec.getOutputBuffer(index)
                val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                if (out != null && info.size > 0 && !isConfig) {
                    if (auBuffer.size < info.size) auBuffer = ByteArray(info.size * 2)
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    out.get(auBuffer, 0, info.size)
                    listener.onAccessUnit(auBuffer, info.size, info.presentationTimeUs)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao tratar quadro AAC: ${e.javaClass.simpleName}")
            } finally {
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (_: Exception) {
                    // codec já parado
                }
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (!stopped) listener.onError("Erro no encoder de áudio BSP: ${e.diagnosticInfo.take(80)}")
        }
    }
}
