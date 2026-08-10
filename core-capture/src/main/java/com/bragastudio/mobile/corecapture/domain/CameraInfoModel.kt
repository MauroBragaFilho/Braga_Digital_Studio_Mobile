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
    val lensType: LensType
) {
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
        result = 31 * result + capabilities.contentHashCode()
        result = 31 * result + resolutions.hashCode()
        result = 31 * result + lensType.hashCode()
        return result
    }
}
