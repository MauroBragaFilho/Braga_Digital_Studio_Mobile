package com.bragastudio.mobile.corecapture.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

class AacEncoder : AudioEncoder {
    private var mediaCodec: MediaCodec? = null
    private var callback: EncoderCallback? = null
    private var isStarted = false

    private class PcmBuffer(val data: ByteArray, val timestampUs: Long)
    private val inputQueue = ConcurrentLinkedQueue<PcmBuffer>()

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (!isStarted) return
            try {
                val inputBuffer = codec.getInputBuffer(index)
                if (inputBuffer != null) {
                    val pcm = inputQueue.poll()
                    if (pcm != null) {
                        inputBuffer.clear()
                        inputBuffer.put(pcm.data)
                        codec.queueInputBuffer(index, 0, pcm.data.size, pcm.timestampUs, 0)
                    } else {
                        // Queue empty buffer if nothing available, but keep trying
                        codec.queueInputBuffer(index, 0, 0, 0, 0)
                    }
                }
            } catch (e: Exception) {
                Log.e("AacEncoder", "Error feeding input buffer", e)
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (!isStarted) return
            try {
                val outputBuffer = codec.getOutputBuffer(index)
                if (outputBuffer != null && info.size > 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0
                    }
                    if (info.size > 0) {
                        callback?.onDataAvailable(outputBuffer, info)
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                Log.e("AacEncoder", "Error processing output buffer", e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e("AacEncoder", "Codec error", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            callback?.onOutputFormatChanged(format)
        }
    }

    override fun prepare(sampleRate: Int, channels: Int, bitrate: Int, callback: EncoderCallback) {
        this.callback = callback
        
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        
        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        mediaCodec?.setCallback(codecCallback)
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    override fun start() {
        inputQueue.clear()
        mediaCodec?.start()
        isStarted = true
    }

    override fun encode(pcmData: ByteArray, length: Int, timestampUs: Long) {
        if (!isStarted) return
        val dataCopy = pcmData.copyOf(length)
        inputQueue.offer(PcmBuffer(dataCopy, timestampUs))
    }

    override fun stop() {
        isStarted = false
    }

    override fun release() {
        isStarted = false
        inputQueue.clear()
        try {
            mediaCodec?.stop()
        } catch (e: Exception) {
            Log.e("AacEncoder", "Error stopping codec", e)
        }
        try {
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e("AacEncoder", "Error releasing codec", e)
        }
        mediaCodec = null
    }
}
