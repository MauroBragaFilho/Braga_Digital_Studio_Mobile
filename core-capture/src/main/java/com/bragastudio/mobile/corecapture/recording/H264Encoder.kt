package com.bragastudio.mobile.corecapture.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

class H264Encoder : VideoEncoder {
    private var mediaCodec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var callback: EncoderCallback? = null
    private var isStarted = false

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Ignored for video as we use Surface input
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (!isStarted) return
            try {
                val outputBuffer = codec.getOutputBuffer(index)
                if (outputBuffer != null && info.size > 0) {
                    // Need to check if config flag is set (SPS/PPS), sometimes it's separate
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0
                    }
                    if (info.size > 0) {
                        callback?.onDataAvailable(outputBuffer, info)
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                Log.e("H264Encoder", "Error processing output buffer", e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e("H264Encoder", "Codec error", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            callback?.onOutputFormatChanged(format)
        }
    }

    override fun prepare(width: Int, height: Int, frameRate: Int, bitrate: Int, callback: EncoderCallback) {
        this.callback = callback
        
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second keyframe interval
        
        mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        mediaCodec?.setCallback(codecCallback)
        mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        
        inputSurface = mediaCodec?.createInputSurface()
    }

    override fun getInputSurface(): Surface {
        return inputSurface ?: throw IllegalStateException("Encoder not prepared")
    }

    override fun start() {
        mediaCodec?.start()
        isStarted = true
    }

    override fun stop() {
        isStarted = false
        try {
            // Send end of stream to surface
            mediaCodec?.signalEndOfInputStream()
        } catch (e: Exception) {
            Log.e("H264Encoder", "Error signaling EOS", e)
        }
    }

    override fun release() {
        isStarted = false
        try {
            mediaCodec?.stop()
        } catch (e: Exception) {
            Log.e("H264Encoder", "Error stopping codec", e)
        }
        try {
            mediaCodec?.release()
        } catch (e: Exception) {
            Log.e("H264Encoder", "Error releasing codec", e)
        }
        mediaCodec = null
        inputSurface?.release()
        inputSurface = null
    }
}
