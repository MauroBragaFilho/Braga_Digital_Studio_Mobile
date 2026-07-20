package com.bragastudio.mobile.corecapture.recording

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer

class MediaMuxerWrapper(private val outputPath: String, private val format: Int = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) {
    private var muxer: MediaMuxer? = null
    
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    
    private var isVideoAdded = false
    private var isAudioAdded = false
    
    private var isStarted = false
    private var isAudioEnabled = true // Configurable if we want silent videos
    
    fun prepare(audioEnabled: Boolean) {
        this.isAudioEnabled = audioEnabled
        muxer = MediaMuxer(outputPath, format)
    }
    
    @Synchronized
    fun addTrack(mediaFormat: MediaFormat, isVideo: Boolean): Int {
        if (isStarted) {
            Log.e("MediaMuxerWrapper", "Cannot add track after muxer started")
            return -1
        }
        
        val trackIndex = muxer?.addTrack(mediaFormat) ?: -1
        if (isVideo) {
            videoTrackIndex = trackIndex
            isVideoAdded = true
        } else {
            audioTrackIndex = trackIndex
            isAudioAdded = true
        }
        
        // Start muxer when all expected tracks are added
        if (isVideoAdded && (!isAudioEnabled || isAudioAdded)) {
            muxer?.start()
            isStarted = true
            Log.d("MediaMuxerWrapper", "Muxer started")
        }
        
        return trackIndex
    }
    
    @Synchronized
    fun writeSampleData(trackIndex: Int, byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo) {
        if (!isStarted) {
            return
        }
        if (trackIndex < 0) {
            return
        }
        
        try {
            muxer?.writeSampleData(trackIndex, byteBuf, bufferInfo)
        } catch (e: Exception) {
            Log.e("MediaMuxerWrapper", "Failed to write sample data", e)
        }
    }
    
    @Synchronized
    fun stop() {
        if (isStarted) {
            try {
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {
                Log.e("MediaMuxerWrapper", "Error stopping muxer", e)
            }
            isStarted = false
        }
        videoTrackIndex = -1
        audioTrackIndex = -1
        isVideoAdded = false
        isAudioAdded = false
    }
}
