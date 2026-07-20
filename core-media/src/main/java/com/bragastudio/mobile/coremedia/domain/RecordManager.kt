package com.bragastudio.mobile.coremedia.domain

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import android.view.Surface
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import com.bragastudio.mobile.core.domain.VideoSettings
import java.util.concurrent.atomic.AtomicBoolean

@Singleton
class RecordManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingRepository: com.bragastudio.mobile.core.repository.RecordingRepository
) {
    private var mediaMuxer: MediaMuxer? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var inputSurface: Surface? = null

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false

    private var isRecordingInternal = AtomicBoolean(false)
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingTimeMs = MutableStateFlow(0L)
    val recordingTimeMs: StateFlow<Long> = _recordingTimeMs.asStateFlow()

    private var timerJob: Job? = null
    private var videoJob: Job? = null
    private var audioJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var audioStartTimeNs = 0L

    private var currentRecordingId: String? = null
    private var currentFilePath: String? = null
    private var currentSettings: VideoSettings? = null

    fun prepareRecording(directoryUri: String, videoSettings: VideoSettings): Surface? {
        try {
            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
            if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                Log.e("RecordManager", "Diretório inválido ou sem permissão")
                return null
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val extension = "mp4"
            val fileName = "BDSM_$timestamp.$extension"
            
            val file = java.io.File(dir, fileName)
            
            currentFilePath = file.absolutePath
            currentSettings = videoSettings

            // Muxer
            mediaMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerStarted = false
            videoTrackIndex = -1
            audioTrackIndex = -1
            
            val width = when(videoSettings.resolution) {
                "4K" -> 3840
                "1440p" -> 2560
                else -> 1920
            }
            val height = when(videoSettings.resolution) {
                "4K" -> 2160
                "1440p" -> 1440
                else -> 1080
            }

            // Video Codec
            val videoMime = if (videoSettings.codec == "H.265") MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val videoFormat = MediaFormat.createVideoFormat(videoMime, width, height)
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, videoSettings.bitrateMbps * 1000000)
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, videoSettings.fps)
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            videoCodec = MediaCodec.createEncoderByType(videoMime)
            videoCodec?.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = videoCodec?.createInputSurface()
            
            // Audio Codec
            val audioMime = MediaFormat.MIMETYPE_AUDIO_AAC
            val audioFormat = MediaFormat.createAudioFormat(audioMime, 48000, 2) // stereo
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            
            audioCodec = MediaCodec.createEncoderByType(audioMime)
            audioCodec?.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            
            return inputSurface
        } catch (e: Exception) {
            Log.e("RecordManager", "Erro ao preparar gravação", e)
            return null
        }
    }

    fun startRecording() {
        if (isRecordingInternal.get()) return
        
        try {
            videoCodec?.start()
            audioCodec?.start()
            
            isRecordingInternal.set(true)
            _isRecording.value = true
            _recordingTimeMs.value = 0L
            audioStartTimeNs = 0L
            
            currentRecordingId = java.util.UUID.randomUUID().toString()
            
            scope.launch {
                currentRecordingId?.let { id ->
                    currentFilePath?.let { path ->
                        currentSettings?.let { settings ->
                            val entity = com.bragastudio.mobile.core.database.RecordingEntity(
                                id = id,
                                fileName = java.io.File(path).name,
                                filePath = path,
                                thumbnailPath = "",
                                durationMs = 0L,
                                sizeBytes = 0L,
                                resolution = settings.resolution,
                                frameRate = settings.fps,
                                codec = settings.codec,
                                bitrate = settings.bitrateMbps,
                                audioCodec = "AAC",
                                audioSampleRate = 48000,
                                createdAt = System.currentTimeMillis(),
                                isFavorite = false,
                                status = "IN_PROGRESS",
                                projectTag = null
                            )
                            recordingRepository.insertRecording(entity)
                        }
                    }
                }
            }
            
            timerJob?.cancel()
            val startTime = System.currentTimeMillis()
            timerJob = scope.launch(Dispatchers.Main) {
                while (isRecordingInternal.get()) {
                    delay(30)
                    _recordingTimeMs.value = System.currentTimeMillis() - startTime
                }
            }
            
            videoJob = scope.launch { drainEncoder(videoCodec, true) }
            audioJob = scope.launch { drainEncoder(audioCodec, false) }
            
        } catch (e: Exception) {
            Log.e("RecordManager", "Erro ao iniciar gravação", e)
        }
    }
    
    @Suppress("UNUSED_PARAMETER")
    fun feedAudio(pcmData: ByteArray, numSamples: Int, numChannels: Int, sampleRate: Int) {
        if (!isRecordingInternal.get()) return
        val codec = audioCodec ?: return
        
        try {
            val inputBufferIndex = codec.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                inputBuffer?.clear()
                inputBuffer?.put(pcmData)
                
                val pts = System.nanoTime() / 1000
                codec.queueInputBuffer(inputBufferIndex, 0, pcmData.size, pts, 0)
            }
        } catch (e: Exception) {
            Log.e("RecordManager", "Erro ao alimentar áudio", e)
        }
    }

    private fun drainEncoder(codec: MediaCodec?, isVideo: Boolean) {
        if (codec == null) return
        val bufferInfo = MediaCodec.BufferInfo()
        val timeoutUs = 10000L
        var isEos = false

        while (!isEos) {
            try {
                val encoderStatus = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!isRecordingInternal.get()) {
                        isEos = true
                    }
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    Log.d("RecordManager", "Format mudou: $newFormat")
                    synchronized(this) {
                        if (isVideo) {
                            videoTrackIndex = mediaMuxer?.addTrack(newFormat) ?: -1
                        } else {
                            audioTrackIndex = mediaMuxer?.addTrack(newFormat) ?: -1
                        }
                        
                        if (!muxerStarted && videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                            mediaMuxer?.start()
                            muxerStarted = true
                            Log.d("RecordManager", "Muxer started")
                        }
                    }
                } else if (encoderStatus >= 0) {
                    val encodedData = codec.getOutputBuffer(encoderStatus)
                    if (encodedData != null) {
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }

                        if (bufferInfo.size != 0 && muxerStarted) {
                            synchronized(this) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                val trackIndex = if (isVideo) videoTrackIndex else audioTrackIndex
                                mediaMuxer?.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                        }
                        codec.releaseOutputBuffer(encoderStatus, false)
                        
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            isEos = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("RecordManager", "Erro no drainEncoder", e)
                break
            }
        }
    }

    fun stopRecording() {
        if (!isRecordingInternal.get()) return
        
        try {
            videoCodec?.signalEndOfInputStream()
        } catch (e: Exception) {
             Log.e("RecordManager", "Erro ao sinalizar fim de stream", e)
        }
        
        isRecordingInternal.set(false)
        
        runBlocking {
            videoJob?.join()
            audioJob?.join()
        }
        
        try {
            videoCodec?.stop()
            videoCodec?.release()
            videoCodec = null
            
            audioCodec?.stop()
            audioCodec?.release()
            audioCodec = null

            if (muxerStarted) {
                mediaMuxer?.stop()
                mediaMuxer?.release()
            }
            mediaMuxer = null
            muxerStarted = false
            
            inputSurface?.release()
            inputSurface = null
        } catch (e: Exception) {
            Log.e("RecordManager", "Erro ao parar gravação", e)
        } finally {
            _isRecording.value = false
            timerJob?.cancel()
            _recordingTimeMs.value = 0L
            
            // Finaliza gravação no banco de dados
            scope.launch {
                currentRecordingId?.let { id ->
                    currentFilePath?.let { path ->
                        val file = java.io.File(path)
                        if (file.exists()) {
                            var duration = 0L
                            var thumbPath = ""
                            try {
                                val retriever = android.media.MediaMetadataRetriever()
                                retriever.setDataSource(path)
                                duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                
                                // Extrair thumbnail (primeiro frame)
                                val bitmap = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                retriever.release()
                                
                                if (bitmap != null) {
                                    val thumbFile = java.io.File(context.getExternalFilesDir(null), "thumbnails")
                                    if (!thumbFile.exists()) thumbFile.mkdirs()
                                    val thumb = java.io.File(thumbFile, "thumb_$id.jpg")
                                    val out = java.io.FileOutputStream(thumb)
                                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                                    out.flush()
                                    out.close()
                                    thumbPath = thumb.absolutePath
                                }
                            } catch (e: Exception) {
                                Log.e("RecordManager", "Erro ao extrair metadados", e)
                            }
                            
                            val dbRecording = recordingRepository.getRecordingById(id)
                            if (dbRecording != null) {
                                val updated = dbRecording.copy(
                                    status = "COMPLETED",
                                    durationMs = duration,
                                    sizeBytes = file.length(),
                                    thumbnailPath = thumbPath
                                )
                                recordingRepository.updateRecording(updated)
                            }
                        }
                    }
                }
                currentRecordingId = null
                currentFilePath = null
                currentSettings = null
            }
        }
    }
}
