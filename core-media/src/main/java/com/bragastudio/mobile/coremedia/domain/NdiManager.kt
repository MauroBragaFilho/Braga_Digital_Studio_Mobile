package com.bragastudio.mobile.coremedia.domain

import android.content.Context
import android.graphics.PixelFormat
import android.media.Image
import android.net.wifi.WifiManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

@Singleton
class NdiManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val _isNdiActive = MutableStateFlow(false)
    val isNdiActive: StateFlow<Boolean> = _isNdiActive.asStateFlow()

    private val _ndiBitrateMbps = MutableStateFlow(0)
    val ndiBitrateMbps: StateFlow<Int> = _ndiBitrateMbps.asStateFlow()

    private val _ndiLatencyMs = MutableStateFlow(0)
    val ndiLatencyMs: StateFlow<Int> = _ndiLatencyMs.asStateFlow()

    private val _ndiFrameDropPct = MutableStateFlow(0f)
    val ndiFrameDropPct: StateFlow<Float> = _ndiFrameDropPct.asStateFlow()

    private var ndiName: String = "BDSM - CAM"
    private var multicastLock: WifiManager.MulticastLock? = null
    
    // Metrics tracking
    private val bytesSentInWindow = AtomicLong(0)
    private val framesSentInWindow = AtomicInteger(0)
    private val recentLatencyMs = AtomicLong(0)
    private val metricsScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var metricsJob: Job? = null
    private var targetFps = 30f

    init {
        try {
            System.loadLibrary("ndi")
            System.loadLibrary("bdsm-media")
        } catch (e: Exception) {
            Log.e("NdiManager", "Falha ao carregar bibliotecas NDI: ")
        }
    }

    private external fun initNDI(name: String): Boolean
    private external fun sendFrameRgba(rgbaBuffer: ByteBuffer, width: Int, height: Int, rowStride: Int)
    private external fun sendAudioFrame(pcmData: ByteArray, numSamples: Int, numChannels: Int, sampleRate: Int)
    private external fun stopNDI()

    fun startNdi(cameraName: String = "BDSM - CAM"): Boolean {
        if (_isNdiActive.value) return true
        
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("bdsm_ndi_multicast_lock")
            multicastLock?.setReferenceCounted(true)
            multicastLock?.acquire()
        } catch (e: Exception) {
            Log.e("NdiManager", "Falha ao adquirir MulticastLock")
        }

        ndiName = if (cameraName.isBlank()) "BDSM - CAM" else cameraName
        val success = initNDI(ndiName)
        if (success) {
            _isNdiActive.value = true
            startMetricsTracking()
        } else {
            multicastLock?.release()
        }
        return success
    }

    fun stopNdi() {
        if (!_isNdiActive.value) return
        stopNDI()
        _isNdiActive.value = false
        stopMetricsTracking()
        
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {}
    }

    fun feedImage(image: Image) {
        if (!_isNdiActive.value) return
        
        try {
            val planes = image.planes
            if (planes.isEmpty()) return
            
            val plane = planes[0]
            val buffer = plane.buffer
            
            // Verificamos se o buffer eh direto
            if (!buffer.isDirect) {
                Log.e("NdiManager", "Buffer de imagem nao eh direto (isDirect = false)")
                return
            }

            val startTime = System.currentTimeMillis()
            
            sendFrameRgba(
                buffer,
                image.width, image.height,
                plane.rowStride
            )
            
            val latency = System.currentTimeMillis() - startTime
            recentLatencyMs.set(latency)
            
            // Track metrics
            val bytes = (image.width * image.height * 4).toLong()
            bytesSentInWindow.addAndGet(bytes)
            framesSentInWindow.incrementAndGet()
            
        } catch (e: Exception) {
            Log.e("NdiManager", "Erro ao processar feedImage para o NDI: ", e)
        }
    }

    fun feedAudio(pcmData: ByteArray, numSamples: Int, numChannels: Int, sampleRate: Int) {
        if (!_isNdiActive.value) return
        try {
            sendAudioFrame(pcmData, numSamples, numChannels, sampleRate)
            // Track audio bytes (16-bit PCM)
            val audioBytes = (numSamples * numChannels * 2).toLong()
            bytesSentInWindow.addAndGet(audioBytes)
        } catch (e: Exception) {
            Log.e("NdiManager", "Erro ao enviar audio para o NDI: ", e)
        }
    }
    
    // Configura a taxa alvo de FPS para calcular o frame drop real
    fun setTargetFps(fps: Int) {
        targetFps = fps.toFloat()
    }

    private fun startMetricsTracking() {
        metricsJob?.cancel()
        metricsJob = metricsScope.launch {
            while (_isNdiActive.value) {
                delay(1000)
                
                // Bitrate
                val bytes = bytesSentInWindow.getAndSet(0)
                // bits per second / 1.000.000 = Mbps
                val mbps = (bytes * 8) / 1_000_000
                _ndiBitrateMbps.value = mbps.toInt()
                
                // Latency (smoothed or just last)
                _ndiLatencyMs.value = recentLatencyMs.get().toInt()
                
                // Frame Drop
                val frames = framesSentInWindow.getAndSet(0)
                var dropPct = 0f
                if (targetFps > 0) {
                    val dropRatio = 1f - (frames.toFloat() / targetFps)
                    if (dropRatio > 0) {
                        dropPct = dropRatio * 100f
                    }
                }
                _ndiFrameDropPct.value = dropPct
            }
            
            // Reset after loop
            _ndiBitrateMbps.value = 0
            _ndiLatencyMs.value = 0
            _ndiFrameDropPct.value = 0f
        }
    }
    
    private fun stopMetricsTracking() {
        metricsJob?.cancel()
        metricsJob = null
        _ndiBitrateMbps.value = 0
        _ndiLatencyMs.value = 0
        _ndiFrameDropPct.value = 0f
    }
}
