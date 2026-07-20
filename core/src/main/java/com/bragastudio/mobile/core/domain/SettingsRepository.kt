package com.bragastudio.mobile.core.domain

import kotlinx.coroutines.flow.Flow

data class VideoSettings(
    val resolution: String = "1080p",
    val fps: Int = 30,
    val bitrateMbps: Int = 50,
    val codec: String = "H.264",
    val videoSource: String = "Camera",
    val recordingDirectoryUri: String? = null,
    val saveToGallery: Boolean = true
)

data class MonitorSettings(
    val zebraThreshold: Int = 100,
    val focusPeakingColor: String = "Red",
    val focusPeakingSensitivity: String = "Medium",
    val selectedLut: String = "Nenhum (Desativado)"
)

data class NdiSettings(
    val isEnabled: Boolean = false,
    val cameraName: String = "BSM Monitor"
)

interface SettingsRepository {
    val videoSettings: Flow<VideoSettings>
    val monitorSettings: Flow<MonitorSettings>
    val ndiSettings: Flow<NdiSettings>
    
    suspend fun setVideoResolution(resolution: String)
    suspend fun setVideoFps(fps: Int)
    suspend fun setVideoBitrate(bitrateMbps: Int)
    suspend fun setVideoCodec(codec: String)
    suspend fun setVideoSource(source: String)
    suspend fun setRecordingDirectoryUri(uri: String?)
    suspend fun setSaveToGallery(save: Boolean)
    
    suspend fun setZebraThreshold(threshold: Int)
    suspend fun setFocusPeakingColor(color: String)
    suspend fun setFocusPeakingSensitivity(sensitivity: String)

    suspend fun setNdiEnabled(enabled: Boolean)
    suspend fun setNdiCameraName(name: String)
    
    suspend fun setSelectedLut(lutName: String)
}