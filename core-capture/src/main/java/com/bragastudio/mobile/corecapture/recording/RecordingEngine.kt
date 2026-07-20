package com.bragastudio.mobile.corecapture.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingEngine @Inject constructor() {
    
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var muxer: MediaMuxerWrapper? = null
    
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    
    var isRecording = false
        private set

    fun prepare(outputPath: String, width: Int, height: Int, fps: Int, videoBitrate: Int, audioSampleRate: Int, audioChannels: Int, audioBitrate: Int): Surface {
        muxer = MediaMuxerWrapper(outputPath)
        
        videoEncoder = H264Encoder()
        audioEncoder = AacEncoder()
        
        videoEncoder?.prepare(width, height, fps, videoBitrate, object : EncoderCallback {
            override fun onOutputFormatChanged(format: MediaFormat) {
                videoTrackIndex = muxer?.addTrack(format, true) ?: -1
            }

            override fun onDataAvailable(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
                muxer?.writeSampleData(videoTrackIndex, buffer, bufferInfo)
            }
        })
        
        audioEncoder?.prepare(audioSampleRate, audioChannels, audioBitrate, object : EncoderCallback {
            override fun onOutputFormatChanged(format: MediaFormat) {
                audioTrackIndex = muxer?.addTrack(format, false) ?: -1
            }

            override fun onDataAvailable(buffer: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
                muxer?.writeSampleData(audioTrackIndex, buffer, bufferInfo)
            }
        })
        
        muxer?.prepare(audioEnabled = true)
        
        return videoEncoder?.getInputSurface() ?: throw IllegalStateException("Failed to get Video Surface")
    }
    
    fun start() {
        if (isRecording) return
        videoEncoder?.start()
        audioEncoder?.start()
        isRecording = true
    }
    
    fun encodeAudio(pcmData: ByteArray, length: Int, timestampUs: Long) {
        if (isRecording) {
            audioEncoder?.encode(pcmData, length, timestampUs)
        }
    }
    
    fun stop() {
        if (!isRecording) return
        isRecording = false
        
        videoEncoder?.stop()
        audioEncoder?.stop()
        
        // Wait a bit for encoders to flush? (Usually handled internally or synchronously)
        
        muxer?.stop()
        
        videoEncoder?.release()
        audioEncoder?.release()
        
        videoEncoder = null
        audioEncoder = null
        muxer = null
    }
}
