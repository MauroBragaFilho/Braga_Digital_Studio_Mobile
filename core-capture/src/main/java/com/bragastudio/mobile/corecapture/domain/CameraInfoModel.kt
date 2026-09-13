package com.bragastudio.mobile.corecapture.domain

import android.util.Size

data class CameraInfoModel(
    val id: String,
    val name: String,
    val facing: Int, // CameraCharacteristics.LENS_FACING_*
    val hardwareLevel: Int, // CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_*
    val focalLengths: FloatArray,
    val sensorSize: Size,
    val hasFlash: Boolean,
    val stabilization: Boolean,
    val capabilities: IntArray,
    val resolutions: List<Size>,
    val lensType: LensType,

    // ------------------------------------------------------------------
    // Capacidades avançadas (Camera2) descobertas em CameraDiscoveryEngine.
    // Têm default para não quebrar as fontes que não são a câmera nativa
    // (UvcCaptureDevice / SonyRemoteCaptureDevice), que não suportam nenhum
    // destes recursos e portanto ficam com tudo em "false".
    // ------------------------------------------------------------------
    /** Lanterna (torch) disponível. */
    val hasTorch: Boolean = false,
    /** Estabilização Óptica (OIS) nativa da lente. */
    val hasOis: Boolean = false,
    /** Estabilização Eletrônica de vídeo (EIS). */
    val hasEis: Boolean = false,
    /** Modo de cena HDR disponível (CONTROL_SCENE_MODE_HDR). */
    val supportsHdr: Boolean = false,
    /** Saída de vídeo HDR real 10-bit (DynamicRangeProfile HLG10, API 33+). */
    val supportsHdr10: Boolean = false,
    /** Maior taxa de quadros anunciada em CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES. */
    val maxFps: Int = 30,
    /** Configurações de vídeo em alta velocidade (slow motion) suportadas. */
    val highSpeedSizes: List<Size> = emptyList()
) {
    /** Maior resolução suportada, derivada de [resolutions] (sem custo de storage). */
    val maxResolution: Size? get() = resolutions.maxByOrNull { it.width.toLong() * it.height.toLong() }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CameraInfoModel

        if (id != other.id) return false
        if (name != other.name) return false
        if (facing != other.facing) return false
        if (hardwareLevel != other.hardwareLevel) return false
        if (!focalLengths.contentEquals(other.focalLengths)) return false
        if (sensorSize != other.sensorSize) return false
        if (hasFlash != other.hasFlash) return false
        if (stabilization != other.stabilization) return false
        if (hasTorch != other.hasTorch) return false
        if (hasOis != other.hasOis) return false
        if (hasEis != other.hasEis) return false
        if (supportsHdr != other.supportsHdr) return false
        if (supportsHdr10 != other.supportsHdr10) return false
        if (maxFps != other.maxFps) return false
        if (highSpeedSizes != other.highSpeedSizes) return false
        if (!capabilities.contentEquals(other.capabilities)) return false
        if (resolutions != other.resolutions) return false
        if (lensType != other.lensType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + facing
        result = 31 * result + hardwareLevel
        result = 31 * result + focalLengths.contentHashCode()
        result = 31 * result + sensorSize.hashCode()
        result = 31 * result + hasFlash.hashCode()
        result = 31 * result + stabilization.hashCode()
        result = 31 * result + hasTorch.hashCode()
        result = 31 * result + hasOis.hashCode()
        result = 31 * result + hasEis.hashCode()
        result = 31 * result + supportsHdr.hashCode()
        result = 31 * result + supportsHdr10.hashCode()
        result = 31 * result + maxFps
        result = 31 * result + highSpeedSizes.hashCode()
        result = 31 * result + capabilities.contentHashCode()
        result = 31 * result + resolutions.hashCode()
        result = 31 * result + lensType.hashCode()
        return result
    }
}
