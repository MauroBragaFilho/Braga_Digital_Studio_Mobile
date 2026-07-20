package com.bragastudio.mobile.corecapture.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

interface EncoderCallback {
    fun onOutputFormatChanged(format: MediaFormat)
    fun onDataAvailable(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo)
}

interface VideoEncoder {
    fun prepare(width: Int, height: Int, frameRate: Int, bitrate: Int, callback: EncoderCallback)
    fun getInputSurface(): Surface
    fun start()
    fun stop()
    fun release()
}

interface AudioEncoder {
    fun prepare(sampleRate: Int, channels: Int, bitrate: Int, callback: EncoderCallback)
    fun start()
    fun encode(pcmData: ByteArray, length: Int, timestampUs: Long)
    fun stop()
    fun release()
}
