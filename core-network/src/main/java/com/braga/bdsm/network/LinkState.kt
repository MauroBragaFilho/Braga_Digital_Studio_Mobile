package com.braga.bdsm.network

import kotlinx.serialization.Serializable

@Serializable
data class LinkState(
    val deviceName: String = "BDSM Device",
    val batteryLevel: Int = 100,
    val isCharging: Boolean = false,
    val captureSource: String = "Camera",
    val cameraLens: String = "Wide",
    val fps: Int = 60,
    val microphone: String = "Built-in"
)
