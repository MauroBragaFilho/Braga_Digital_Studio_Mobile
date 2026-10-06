package com.bragastudio.mobile.corecapture.device

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.media.ImageReader
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import com.bragastudio.mobile.core.model.SonyCameraStatus
import com.bragastudio.mobile.corecapture.domain.CameraInfoModel
import com.bragastudio.mobile.corecapture.domain.CaptureDevice
import com.bragastudio.mobile.corecapture.domain.CaptureState
import com.bragastudio.mobile.corecapture.domain.LensType
import com.bragastudio.mobile.network.sony.SonyCameraClient
import com.bragastudio.mobile.network.sony.SonyCameraDiscovery
import com.bragastudio.mobile.network.sony.SonyLiveviewSocketReader
import com.bragastudio.mobile.network.sony.SonyNetwork
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class SonyRemoteCaptureDevice @Inject constructor(
    @ApplicationContext private val context: Context,
) : CaptureDevice {

    companion object {
        private const val TAG = "SonyRemoteCaptureDevice"
    }

    private val _state = MutableStateFlow(CaptureState.IDLE)
    override val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _telemetry = MutableStateFlow(SonyCameraStatus())
    val telemetry: StateFlow<SonyCameraStatus> = _telemetry.asStateFlow()

    private val _availableLenses = MutableStateFlow<List<CameraInfoModel>>(
        listOf(
            CameraInfoModel(
                id = "sony_remote_main",
                name = "Sony Camera (Wi-Fi)",
                facing = android.hardware.camera2.CameraMetadata.LENS_FACING_EXTERNAL,
                hardwareLevel = android.hardware.camera2.CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL,
                focalLengths = floatArrayOf(),
                sensorSize = android.util.Size(1920, 1080),
                hasFlash = false,
                stabilization = false,
                capabilities = intArrayOf(),
                resolutions = listOf(android.util.Size(1920, 1080), android.util.Size(640, 480)),
                lensType = LensType.EXTERNAL,
            ),
        ),
    )
    override val availableLenses: StateFlow<List<CameraInfoModel>> = _availableLenses.asStateFlow()

    override val deviceId: String = "SONY_REMOTE"
    override val deviceName: String = "Sony Camera (Wi-Fi)"
    override val sensorOrientation: Int = 0

    private val discovery = SonyCameraDiscovery()
    private val client = SonyCameraClient()
    private var socketReader: SonyLiveviewSocketReader? = null

    private var activeSurfaces: List<Surface> = emptyList()
    private var streamingJob: Job? = null
    private var telemetryJob: Job? = null
    private var renderJob: Job? = null
    private val latestFrame = AtomicReference<ByteArray?>(null)
    private val scope = CoroutineScope(Dispatchers.IO)

    override suspend fun start(vararg surfaces: Surface) {
        if (surfaces.isEmpty()) return
        activeSurfaces = surfaces.toList()
        _state.value = CaptureState.INITIALIZING

        streamingJob?.cancel()
        telemetryJob?.cancel()
        renderJob?.cancel()
        renderJob = scope.launch { renderLatestFrames() }

        streamingJob = scope.launch {
            connectAndStream()
        }
    }

    private suspend fun connectAndStream() {
        while (scope.isActive && activeSurfaces.isNotEmpty()) {
            try {
                Log.d(TAG, "Discovering Sony Camera...")
                val device = discovery.discoverCamera(timeoutMs = 4000)
                if (device == null) {
                    Log.w(TAG, "Sony Camera not found. Retrying in 3s...")
                    _state.value = CaptureState.INITIALIZING
                    delay(3000)
                    continue
                }

                Log.d(TAG, "Sony Camera found at ${device.endpointUrl}. Starting RecMode...")
                client.updateEndpoint(device.endpointUrl)
                client.startRecMode()

                Log.d(TAG, "Requesting Liveview URL...")
                val liveviewUrl = client.startLiveview()
                if (liveviewUrl.isNullOrEmpty()) {
                    Log.w(TAG, "Failed to start liveview. Retrying...")
                    delay(2000)
                    continue
                }

                _state.value = CaptureState.READY
                startTelemetryPolling()

                val reader = SonyLiveviewSocketReader()
                socketReader = reader

                reader.startStreaming(
                    liveviewUrl,
                    object : SonyLiveviewSocketReader.FrameCallback {
                        override fun onFrameReceived(jpegBytes: ByteArray, sequenceNumber: Int, timestampUs: Long) {
                            latestFrame.set(jpegBytes)
                        }

                        override fun onError(error: Throwable) {
                            Log.e(TAG, "Liveview stream error: ${error.message}")
                            _state.value = CaptureState.ERROR
                        }
                    },
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error in connection loop: ${e.message}")
                _state.value = CaptureState.ERROR
            }

            // Se o stream cair, aguardar antes de reconectar
            delay(2000)
        }
    }

    private suspend fun renderLatestFrames() {
        while (currentCoroutineContext().isActive && activeSurfaces.isNotEmpty()) {
            latestFrame.getAndSet(null)?.let(::renderJpegFrame)
            delay(16)
        }
    }

    private fun renderJpegFrame(jpegBytes: ByteArray) {
        try {
            val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return
            for (surface in activeSurfaces) {
                if (surface.isValid) {
                    try {
                        val canvas = surface.lockCanvas(null)
                        if (canvas != null) {
                            val destRect = Rect(0, 0, canvas.width, canvas.height)
                            val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                            canvas.drawBitmap(bitmap, srcRect, destRect, null)
                            surface.unlockCanvasAndPost(canvas)
                        }
                    } catch (e: Exception) {
                        // Canvas lock exception when surface is changing
                    }
                }
            }
            bitmap.recycle()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode/render frame: ${e.message}")
        }
    }

    private fun startTelemetryPolling() {
        telemetryJob?.cancel()
        telemetryJob = scope.launch {
            while (isActive && _state.value == CaptureState.READY) {
                try {
                    val status = client.getEvent(longPolling = false)
                    _telemetry.value = status
                } catch (e: Exception) {
                    Log.w(TAG, "Telemetry polling error: ${e.message}")
                }
                delay(1000)
            }
        }
    }

    override suspend fun stop() {
        _state.value = CaptureState.IDLE
        streamingJob?.cancel()
        telemetryJob?.cancel()
        renderJob?.cancel()
        streamingJob = null
        telemetryJob = null
        renderJob = null
        latestFrame.set(null)

        withContext(Dispatchers.IO) {
            socketReader?.close()
            socketReader = null
            try {
                client.stopLiveview()
            } catch (e: Exception) {
                // Ignore stop error
            }
        }
        activeSurfaces = emptyList()
        // Encerra a sessão com a câmera: solta o vínculo por socket à rede Wi-Fi dela.
        SonyNetwork.release()
    }

    override fun configure(resolution: String, fps: Int) {
        // Sony a6000 liveview resolution is managed dynamically by camera
    }

    override suspend fun switchCamera(cameraId: String) {
        // No-op for remote single camera
    }

    override fun setIso(iso: Int?) {
        scope.launch {
            if (iso != null) {
                client.setIsoSpeedRate(iso.toString())
            } else {
                client.setIsoSpeedRate("AUTO")
            }
        }
    }

    override fun setShutterSpeed(nanoseconds: Long?) {
        scope.launch {
            if (nanoseconds != null && nanoseconds > 0) {
                val seconds = nanoseconds / 1_000_000_000.0
                val speedStr = if (seconds < 1.0) {
                    "1/${(1.0 / seconds).toInt()}"
                } else {
                    "${seconds.toInt()}\""
                }
                client.setShutterSpeed(speedStr)
            }
        }
    }

    override fun setWhiteBalance(mode: Int?) {
        // WB mode mapping
    }

    override fun setFocusDistance(diopters: Float?) {
        scope.launch {
            if (diopters != null) {
                client.actHalfPressShutter()
            } else {
                client.cancelHalfPressShutter()
            }
        }
    }

    fun takePicture() {
        scope.launch {
            client.actTakePicture()
        }
    }

    /** Devolve a íris ao automático (modo de exposição que decide a abertura, com fallback). */
    fun setIrisAuto() {
        scope.launch {
            try {
                if (!client.setIrisAuto()) Log.w(TAG, "Nenhum modo de exposição com íris automática disponível")
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao ativar íris automática: ${e.message}")
            }
        }
    }

    fun setAperture(fNumber: String) {
        scope.launch {
            client.setFNumber(fNumber)
        }
    }

    override fun getBestSupportedSize(targetWidth: Int, targetHeight: Int): Pair<Int, Int>? = Pair(1920, 1080)
}
