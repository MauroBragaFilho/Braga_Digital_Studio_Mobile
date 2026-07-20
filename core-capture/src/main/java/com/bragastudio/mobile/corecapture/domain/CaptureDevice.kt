package com.bragastudio.mobile.corecapture.domain

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

enum class CaptureState {
    IDLE,
    INITIALIZING,
    READY,
    RECORDING,
    ERROR
}

data class LensInfo(
    val id: String,
    val name: String,
    val isPrimary: Boolean = false
)

interface CaptureDevice {
    val deviceId: String
    val deviceName: String
    val state: StateFlow<CaptureState>

    val sensorOrientation: Int
    
    val availableLenses: StateFlow<List<LensInfo>>

    suspend fun start(vararg surfaces: Surface)
    suspend fun stop()
    fun configure(resolution: String, fps: Int)
    
    suspend fun switchCamera(cameraId: String)
    
    fun setIso(iso: Int?)
    fun setShutterSpeed(nanoseconds: Long?)
    fun setWhiteBalance(mode: Int?)
    fun setFocusDistance(diopters: Float?)
}
