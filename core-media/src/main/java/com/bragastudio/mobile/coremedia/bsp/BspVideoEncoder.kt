package com.bragastudio.mobile.coremedia.bsp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "BspVideoEncoder"

/**
 * Codificador H.264 do BSP: `MediaCodec` com entrada por `Surface` (câmera -> GL -> Surface, sem
 * cópia pela CPU), CBR, sem B-frames, `KEY_LATENCY=0`, prioridade de tempo real. Modo assíncrono
 * (callbacks numa `HandlerThread`): quando nenhum quadro chega (Monitor fechado) a thread dorme, sem
 * nenhum sondador. Cada quadro codificado é copiado para um buffer reaproveitado e entregue a
 * [Listener] na própria thread do encoder.
 *
 * Ponto de extensão da Fase 2: mudar o bitrate em tempo real (`PARAMETER_KEY_VIDEO_BITRATE`) pelo
 * controlador adaptativo; hoje o bitrate é fixo.
 */
class BspVideoEncoder(private val listener: Listener) {
    interface Listener {
        /** SPS/PPS crus (sem start code) vindos do CODEC_CONFIG do encoder. */
        fun onCodecConfig(sps: ByteArray?, pps: ByteArray?)

        /** Um access unit Annex-B em `data[0 until length]` (buffer reaproveitado: copie se precisar guardar). */
        fun onFrame(data: ByteArray, length: Int, presentationTimeUs: Long, keyFrame: Boolean)

        fun onError(message: String)
    }

    @Volatile private var codec: MediaCodec? = null

    @Volatile private var surface: Surface? = null
    private var thread: HandlerThread? = null

    @Volatile private var stopped = true
    private var frameBuffer = ByteArray(256 * 1024)
    private val frames = AtomicLong(0)

    /** Quadros codificados desde o início (o gerente deriva o fps real). */
    val framesEncoded: Long get() = frames.get()

    /** Cria e inicia o encoder; devolve a Surface de entrada. Lança em falha (o chamador libera com [stop]). */
    @Synchronized
    fun start(width: Int, height: Int, fps: Int, bitrateBps: Int, keyframeIntervalSec: Int): Surface {
        check(stopped) { "encoder BSP já iniciado" }
        val t = HandlerThread("bsp-venc").also { it.start() }
        thread = t
        try {
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = c
            c.setCallback(callback, Handler(t.looper))
            try {
                c.configure(buildFormat(width, height, fps, bitrateBps, keyframeIntervalSec, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                // alguns encoders recusam CBR no configure: recria o codec e tenta VBR (o bitrate médio segue valendo)
                Log.w(TAG, "CBR recusado (${e.javaClass.simpleName}); tentando VBR")
                c.release()
                val retry = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec = retry
                retry.setCallback(callback, Handler(t.looper))
                retry.configure(buildFormat(width, height, fps, bitrateBps, keyframeIntervalSec, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val c2 = codec ?: error("encoder indisponível")
            val s = c2.createInputSurface()
            surface = s
            stopped = false
            c2.start()
            Log.i(TAG, "Encoder H.264 iniciado: ${width}x$height@$fps, ${bitrateBps / 1000} kbps")
            return s
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    private fun buildFormat(width: Int, height: Int, fps: Int, bitrateBps: Int, keyframeIntervalSec: Int, bitrateMode: Int): MediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalSec)
        setInteger(MediaFormat.KEY_PRIORITY, 0) // tempo real
        setInteger(MediaFormat.KEY_LATENCY, 0) // sem bufferização interna
        setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode)
        setInteger("max-bframes", 0) // sem B-frames: nada de reordenar no receptor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // SPS/PPS na frente de cada IDR; abaixo disso o empacotador os repete via STAP-A (5.2)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
    }

    /** Pede um IDR no próximo quadro (PLI do receptor, receptor novo). Barato e seguro entre threads. */
    fun requestKeyframe() {
        val c = codec ?: return
        try {
            c.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (e: Exception) {
            Log.w(TAG, "Pedido de IDR falhou: ${e.javaClass.simpleName}")
        }
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
                // nunca iniciou ou já parado
            }
            try {
                c.release()
            } catch (e: Exception) {
                Log.w(TAG, "Ao liberar o encoder: ${e.javaClass.simpleName}")
            }
        }
        try {
            surface?.release()
        } catch (_: Exception) {
            // ignora
        }
        surface = null
        thread?.quitSafely()
        thread = null
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // entrada por Surface

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                if (stopped) return
                val out = codec.getOutputBuffer(index)
                if (out != null && info.size > 0) {
                    if (frameBuffer.size < info.size) frameBuffer = ByteArray(info.size * 2)
                    out.position(info.offset)
                    out.limit(info.offset + info.size)
                    out.get(frameBuffer, 0, info.size)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        val (sps, pps) = AnnexB.parameterSets(frameBuffer, 0, info.size)
                        listener.onCodecConfig(sps, pps)
                    } else {
                        frames.incrementAndGet()
                        listener.onFrame(frameBuffer, info.size, info.presentationTimeUs, (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao tratar quadro codificado: ${e.javaClass.simpleName}")
            } finally {
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (_: Exception) {
                    // codec já parado
                }
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // csd-0 (SPS) e csd-1 (PPS) em Annex-B: o jeito mais cedo de conhecê-los (antes do 1º quadro).
            val sps = format.getByteBuffer("csd-0")?.let { bufferBytes(it) }
            val pps = format.getByteBuffer("csd-1")?.let { bufferBytes(it) }
            if (sps != null || pps != null) {
                val parsed = AnnexB.parameterSets((sps ?: ByteArray(0)) + (pps ?: ByteArray(0)))
                listener.onCodecConfig(parsed.first, parsed.second)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            if (!stopped) listener.onError("Erro no encoder de vídeo BSP: ${e.diagnosticInfo.take(80)}")
        }
    }

    private fun bufferBytes(buffer: java.nio.ByteBuffer): ByteArray {
        val dup = buffer.duplicate()
        val out = ByteArray(dup.remaining())
        dup.get(out)
        return out
    }
}
