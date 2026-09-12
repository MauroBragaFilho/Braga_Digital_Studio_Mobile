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

    // Canal de eventos de erro para a UI. SharedFlow (não StateFlow) porque é um
    // evento pontual — não queremos que uma nova tela reabra o mesmo erro antigo
    // ao se inscrever, como aconteceria com StateFlow.
    private val _errorEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errorEvents: kotlinx.coroutines.flow.SharedFlow<String> = _errorEvents

    /** Loga E notifica a UI. Centraliza os dois passos para não esquecer um deles. */
    private fun reportError(tag: String, userMessage: String, throwable: Throwable? = null) {
        Log.e(tag, userMessage, throwable)
        _errorEvents.tryEmit(userMessage)
    }

    private val _recordingTimeMs = MutableStateFlow(0L)
    val recordingTimeMs: StateFlow<Long> = _recordingTimeMs.asStateFlow()

    private var timerJob: Job? = null
    private var videoJob: Job? = null
    private var audioJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var audioStartTimeNs = 0L

    /** Último PTS de áudio emitido (µs) — base do frame de fim de stream. */
    private var lastAudioPtsUs = 0L

    /** Marca que o stop foi pedido, para o drainEncoder drenar a cauda até a EOS. */
    private val isStopping = AtomicBoolean(false)

    // EOS de áudio: tentativas e timeout por tentativa (µs).
    private val AUDIO_EOS_MAX_ATTEMPTS = 20
    private val AUDIO_EOS_DEQUEUE_TIMEOUT_US = 50_000L
    // Watchdog do drain no stop: abandona a cauda após N drenagens vazias seguidas.
    private val MAX_IDLE_AFTER_STOP = 50

    private var currentRecordingId: String? = null
    private var currentFilePath: String? = null
    private var currentDestinationUri: Uri? = null
    private var currentDestinationCopySucceeded = true
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
            currentDestinationUri = directoryUri.toUriOrNull()?.let { treeUri ->
                val tree = DocumentFile.fromTreeUri(context, treeUri)
                    ?: throw IllegalArgumentException("Pasta de gravação inválida")
                if (!tree.canWrite()) throw SecurityException("Pasta de gravação sem permissão de escrita")
                tree.createFile("video/mp4", fileName)?.uri
                    ?: throw IllegalStateException("Não foi possível criar o arquivo de gravação")
            }
                    currentDestinationCopySucceeded = true
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
            reportError("RecordManager", "Não foi possível preparar a gravação (codec indisponível ou configuração inválida).", e)
            return null
        }
    }

    fun startRecording() {
        if (isRecordingInternal.get()) return
        
        try {
            videoCodec?.start()
            audioCodec?.start()
            
            isRecordingInternal.set(true)
            isStopping.set(false)
            _isRecording.value = true
            _recordingTimeMs.value = 0L
            audioStartTimeNs = System.nanoTime()
            lastAudioPtsUs = 0L
            
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
            reportError("RecordManager", "Falha ao iniciar a gravação. Verifique o armazenamento e tente novamente.", e)
            _isRecording.value = false
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
                
                // PTS ancorado no início da gravação (mesmo eixo do vídeo). Antes,
                // cada chunk usava nanoTime diretamente e a primeira amostra nascia
                // centenas de ms "no futuro", dessincronizando áudio e vídeo.
                val pts = (System.nanoTime() - audioStartTimeNs) / 1_000
                lastAudioPtsUs = pts
                codec.queueInputBuffer(inputBufferIndex, 0, pcmData.size, pts, 0)
            }
        } catch (e: Exception) {
            Log.e("RecordManager", "Erro ao alimentar áudio", e)
        }
    }

    private fun drainEncoder(codec: MediaCodec?, isVideo: Boolean) {
        if (codec == null) return
        val bufferInfo = MediaCodec.BufferInfo()
        val timeoutUs = 10_000L
        var isEos = false
        var idleAfterStop = 0

        while (!isEos) {
            try {
                val encoderStatus = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (isStopping.get()) {
                        // Stop pedido: segue drenando até a EOS chegar, mas com
                        // watchdog para não travar se o codec nunca a emitir.
                        if (++idleAfterStop >= MAX_IDLE_AFTER_STOP) isEos = true
                    } else if (!isRecordingInternal.get()) {
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
                        idleAfterStop = 0

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

    suspend fun stopRecording() {
        if (!isRecordingInternal.get()) return
        
        try {
            videoCodec?.signalEndOfInputStream()
        } catch (e: Exception) {
             Log.e("RecordManager", "Erro ao sinalizar fim de stream", e)
        }
           signalAudioEndOfStream()
        
        isStopping.set(true)
        isRecordingInternal.set(false)

        // Drena a cauda dentro do NonCancellable: mesmo que o chamador (UI) seja
        // cancelado, o join() e a finalização do arquivo precisam terminar.
        withContext(NonCancellable) {
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
            reportError("RecordManager", "Erro ao finalizar o arquivo de gravação — o vídeo pode estar corrompido ou incompleto.", e)
        } finally {
            _isRecording.value = false
            timerJob?.cancel()
            _recordingTimeMs.value = 0L
            
            val destinationUri = currentDestinationUri
            val sourcePath = currentFilePath
            if (destinationUri != null && sourcePath != null) {
                try {
                    context.contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
                        java.io.File(sourcePath).inputStream().use { input -> input.copyTo(output) }
                    } ?: throw IllegalStateException("Não foi possível abrir o destino da gravação")
                    currentDestinationCopySucceeded = true
                } catch (e: Exception) {
                    currentDestinationCopySucceeded = false
                    reportError("RecordManager", "A gravação foi finalizada, mas não pôde ser copiada para a pasta escolhida.", e)
                }
            }

            // Finaliza gravação no banco de dados
            val recordingId = currentRecordingId
            val recordingPath = currentFilePath
            val recordingUri = currentDestinationUri?.toString()
            val copySucceeded = currentDestinationCopySucceeded
            scope.launch {
                recordingId?.let { id ->
                    recordingPath?.let { path ->
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
                                    status = if (copySucceeded) "COMPLETED" else "CORRUPTED",
                                    durationMs = duration,
                                    sizeBytes = file.length(),
                                    thumbnailPath = thumbPath,
                                    contentUri = recordingUri
                                )
                                recordingRepository.updateRecording(updated)
                            }
                        }
                    }
                }
                currentRecordingId = null
                currentFilePath = null
                currentDestinationUri = null
                currentSettings = null
            }
        }
    }

    /**
     * Enfileira um frame vazio com [MediaCodec.BUFFER_FLAG_END_OF_STREAM] no codec
     * de áudio para que ele drene a cauda (amostras ainda dentro do encoder) antes
     * do stop(). Repete até conseguir — no momento do stop o codec pode estar
     * processando um buffer e o dequeue falha na primeira tentativa. Usa o último
     * PTS real da trilha ([lastAudioPtsUs]), nunca o relógio atual, para não
     * desalinhar a timeline do arquivo final.
     */
    private fun signalAudioEndOfStream() {
        val codec = audioCodec ?: return
        for (attempt in 1..AUDIO_EOS_MAX_ATTEMPTS) {
            try {
                val inputBufferIndex = codec.dequeueInputBuffer(AUDIO_EOS_DEQUEUE_TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    codec.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        0,
                        lastAudioPtsUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    Log.d("RecordManager", "EOS de áudio enfileirado na tentativa $attempt")
                    return
                }
            } catch (e: IllegalStateException) {
                // Codec já parado/releaseado → não há mais o que sinalizar.
                Log.w("RecordManager", "Codec de áudio indisponível para EOS", e)
                return
            } catch (e: Exception) {
                Log.e("RecordManager", "Erro ao sinalizar fim do áudio", e)
                return
            }
        }
        Log.w("RecordManager", "EOS de áudio não enfileirado após $AUDIO_EOS_MAX_ATTEMPTS tentativas")
    }

    private fun String.toUriOrNull(): Uri? = takeIf { it.isNotBlank() }?.let(Uri::parse)
}
