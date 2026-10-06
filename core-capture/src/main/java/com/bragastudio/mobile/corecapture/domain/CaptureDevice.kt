package com.bragastudio.mobile.corecapture.domain

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class CaptureState {
    IDLE,
    INITIALIZING,
    READY,
    RECORDING,
    ERROR,
}

interface CaptureDevice {
    val deviceId: String
    val deviceName: String
    val state: StateFlow<CaptureState>

    val sensorOrientation: Int

    val availableLenses: StateFlow<List<CameraInfoModel>>

    suspend fun start(vararg surfaces: Surface)
    suspend fun stop()
    fun configure(resolution: String, fps: Int)

    suspend fun switchCamera(cameraId: String)

    fun setIso(iso: Int?)
    fun setShutterSpeed(nanoseconds: Long?)
    fun setWhiteBalance(mode: Int?)
    fun setFocusDistance(diopters: Float?)

    fun getBestSupportedSize(targetWidth: Int, targetHeight: Int): Pair<Int, Int>?

// ------------------------------------------------------------------
    // Controles avançados de captura (Camera2): lanterna, estabilização de
    // vídeo (OIS/EIS) e HDR. As implementações desta interface que NÃO são a
    // câmera nativa (UVC, Sony Remote) herdam os defaults no-op/desligado
    // abaixo, então a UI pode chamar/expor tudo sem checagens de tipo.
    // ------------------------------------------------------------------
    val torchEnabled: StateFlow<Boolean> get() = ALWAYS_OFF
    val videoStabilizationEnabled: StateFlow<Boolean> get() = ALWAYS_OFF
    val hdrEnabled: StateFlow<Boolean> get() = ALWAYS_OFF

    fun setTorchEnabled(enabled: Boolean) {}
    fun setVideoStabilizationEnabled(enabled: Boolean) {}
    fun setHdrEnabled(enabled: Boolean) {}

// ------------------------------------------------------------------
    // HDR real 10-bit (Caminho A): gravação direta câmera→encoder, sem GL.
    // Implementações não-Camera2 (UVC / Sony Remote) herdam os defaults no-op.
    // ------------------------------------------------------------------

    /** A câmera ATUAL consegue entregar vídeo HDR real 10-bit (HLG10, API 33+). */
    val supportsTrueHdr: Boolean get() = false

    /**
     * Atrela/destaca o Surface do encoder HDR (10-bit) da sessão de captura.
     * Quando ativo, os frames seguem direto da câmera para o encoder (sem GL);
     * o preview continua SDR via output companion. Retorna false se a HAL não
     * aceitou a reconfiguração (o chamador deve cair para o caminho GL 8-bit).
     */
    suspend fun setCameraHdrSurface(surface: Surface?): Boolean = true

    // ------------------------------------------------------------------
    // Telemetria / FPS efetivo / limites dos controles manuais. Defaults
    // neutros para as fontes que não são a câmera nativa.
    // ------------------------------------------------------------------

    /** FPS que a câmera realmente entrega (após validar faixa de AE e duração mínima de quadro); 0 = desconhecido. */
    val effectiveFps: StateFlow<Int> get() = ALWAYS_ZERO

    /** Metadata do último CaptureResult (ISO, obturador, foco, duração de quadro). */
    val captureMetadata: StateFlow<CaptureMetadata> get() = NO_METADATA

    /** Limites ISO/obturador/foco da câmera ativa; vazio quando não aplicável. */
    val manualLimits: StateFlow<ManualLimits> get() = NO_LIMITS

    companion object {
        private val ALWAYS_OFF = MutableStateFlow(false)
        private val ALWAYS_ZERO = MutableStateFlow(0)
        private val NO_METADATA = MutableStateFlow(CaptureMetadata())
        private val NO_LIMITS = MutableStateFlow(ManualLimits())
    }
}
