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
}
