package com.bragastudio.mobile.core.model

/**
 * Estado da câmera Sony remota (Camera Remote API). Vive em :core para que core-media e
 * feature-preview o usem sem depender de :core-network só por causa do tipo.
 */
data class SonyCameraStatus(
    val isConnected: Boolean = false,
    val isRecording: Boolean = false,
    val shootMode: String = "",
    val batteryLevel: String = "",
    val isBatteryCharging: Boolean = false,
    val storageAvailable: String = "",
    val currentIso: String = "",
    val currentShutterSpeed: String = "",
    val currentFNumber: String = "",
    val currentExposureCompensation: String = "",
    val focusStatus: String = "",
    val availableApiList: Set<String> = emptySet(),
)
