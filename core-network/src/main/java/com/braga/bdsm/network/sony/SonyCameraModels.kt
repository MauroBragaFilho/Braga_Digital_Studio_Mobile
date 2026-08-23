package com.braga.bdsm.network.sony

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SonyCameraDevice(
    val friendlyName: String,
    val modelName: String = "",
    val ddUrl: String,
    val endpointUrl: String,
    val liveviewUrl: String = ""
)

@Serializable
data class SonyJsonRpcRequest(
    val method: String,
    val params: List<JsonElement> = emptyList(),
    val id: Int = 1,
    val version: String = "1.0"
)

@Serializable
data class SonyJsonRpcResponse(
    val id: Int = 1,
    val result: List<JsonElement>? = null,
    val results: List<JsonElement>? = null,
    val error: List<JsonElement>? = null
)

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
    val availableApiList: Set<String> = emptySet()
)
