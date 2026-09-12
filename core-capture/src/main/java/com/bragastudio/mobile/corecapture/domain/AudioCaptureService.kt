package com.bragastudio.mobile.corecapture.domain

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AudioLevels(
    val leftLevel: Float = 0f,
    val rightLevel: Float = 0f
)

@Singleton
class AudioCaptureService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _audioLevels = MutableStateFlow(AudioLevels())
    val audioLevels: StateFlow<AudioLevels> = _audioLevels.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sampleRate = 48000
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    // Callback para enviar o buffer bruto para quem precisar (ex: NDI)
    var onAudioBufferAvailable: ((pcmData: ByteArray, numSamplesPerChannel: Int, numChannels: Int, sampleRate: Int) -> Unit)? = null

    private val captureMutex = kotlinx.coroutines.sync.Mutex()

    fun startCapture(deviceInfo: AudioDeviceInfo?) {
        coroutineScope.launch {
            captureMutex.withLock {
                stopCaptureInternal()

                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    return@launch
                }

                try {
                    val audioSource = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        MediaRecorder.AudioSource.CAMCORDER 
                    } else {
                        MediaRecorder.AudioSource.MIC 
                    }

                    audioRecord = AudioRecord(
                        audioSource,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )

                    if (deviceInfo != null) {
                        val supports48k = deviceInfo.sampleRates.contains(sampleRate)
                        val supportsStereo = deviceInfo.channelCounts.contains(2) 

                        if (!supports48k || !supportsStereo) {
                            android.util.Log.w("AudioCapture", "Dispositivo externo não suporta 48kHz Stereo. Pode haver fallback ou falha.")
                        }
                        
                        audioRecord?.setPreferredDevice(deviceInfo)
                    }

                    audioRecord?.startRecording()

                    captureJob = launch {
                        val audioBuffer = ShortArray(bufferSize / 2)
                        val currentRecord = audioRecord

                        while (isActive && currentRecord != null) {
                            val readResult = currentRecord.read(audioBuffer, 0, audioBuffer.size)
                            if (readResult > 0) {
                                processAudioBuffer(audioBuffer, readResult)
                                
                                val numChannels = 2 
                                val numSamplesPerChannel = readResult / numChannels
                                
                                val byteBuffer = ByteBuffer.allocate(readResult * 2).order(ByteOrder.LITTLE_ENDIAN)
                                for (i in 0 until readResult) {
                                    byteBuffer.putShort(audioBuffer[i])
                                }
                                val byteArray = byteBuffer.array()

                                onAudioBufferAvailable?.invoke(byteArray, numSamplesPerChannel, numChannels, sampleRate)
                            } else {
                                delay(10)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    stopCaptureInternal()
                }
            }
        }
    }

    fun stopCapture() {
        coroutineScope.launch {
            captureMutex.withLock {
                stopCaptureInternal()
            }
        }
    }

    private suspend fun stopCaptureInternal() {
        val job = captureJob
        val record = audioRecord

        captureJob = null
        audioRecord = null

        // Unblock read() by stopping the record first
        record?.apply {
            try {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Now wait for the read loop to cleanly exit
        job?.cancelAndJoin()

        // Safely release the native resources
        record?.release()
    }

    private fun processAudioBuffer(buffer: ShortArray, size: Int) {
        var sumLeft = 0.0
        var sumRight = 0.0

        for (i in 0 until size step 2) {
            val leftSample = buffer[i].toDouble() / Short.MAX_VALUE
            sumLeft += leftSample * leftSample

            if (i + 1 < size) {
                val rightSample = buffer[i + 1].toDouble() / Short.MAX_VALUE
                sumRight += rightSample * rightSample
            }
        }

        val rmsLeft = sqrt(sumLeft / (size / 2))
        val rmsRight = sqrt(sumRight / (size / 2))

        val levelLeft = (20 * log10(rmsLeft)).toFloat().coerceIn(-60f, 0f)
        val levelRight = (20 * log10(rmsRight)).toFloat().coerceIn(-60f, 0f)

        val normalizedLeft = (levelLeft + 60f) / 60f
        val normalizedRight = (levelRight + 60f) / 60f

        _audioLevels.value = AudioLevels(normalizedLeft, normalizedRight)
    }
}
