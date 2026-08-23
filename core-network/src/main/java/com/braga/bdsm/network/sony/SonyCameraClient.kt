package com.braga.bdsm.network.sony

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SonyCameraClient(private var endpointUrl: String = "http://192.168.122.1:8080/sony/camera") {

    companion object {
        private const val TAG = "SonyCameraClient"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var reqId = 1

    fun updateEndpoint(endpoint: String) {
        this.endpointUrl = endpoint
    }

    suspend fun sendJsonRpc(
        method: String,
        params: List<JsonElement> = emptyList(),
        version: String = "1.0",
        endpoint: String = endpointUrl
    ): SonyJsonRpcResponse? = withContext(Dispatchers.IO) {
        try {
            val url = URL(endpoint)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 5000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            val currentId = reqId++
            val request = SonyJsonRpcRequest(
                method = method,
                params = params,
                id = currentId,
                version = version
            )
            val jsonString = json.encodeToString(SonyJsonRpcRequest.serializer(), request)

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonString)
                writer.flush()
            }

            if (connection.responseCode in 200..299) {
                val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                return@withContext json.decodeFromString(SonyJsonRpcResponse.serializer(), responseText)
            } else {
                Log.w(TAG, "HTTP error: ${connection.responseCode} on method $method")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "RPC Call Exception for $method: ${e.message}")
            null
        }
    }

    suspend fun startRecMode(): Boolean {
        val response = sendJsonRpc("startRecMode")
        return response?.result != null || response?.results != null || response?.error == null
    }

    suspend fun stopRecMode(): Boolean {
        val response = sendJsonRpc("stopRecMode")
        return response?.error == null
    }

    suspend fun startLiveview(): String? {
        val response = sendJsonRpc("startLiveview")
        val result = response?.result ?: response?.results
        if (result != null && result.isNotEmpty()) {
            return result[0].jsonPrimitive.contentOrNull
        }
        return null
    }

    suspend fun stopLiveview(): Boolean {
        val response = sendJsonRpc("stopLiveview")
        return response?.error == null
    }

    suspend fun getAvailableApiList(): List<String> {
        val response = sendJsonRpc("getAvailableApiList")
        val result = response?.result ?: response?.results
        if (result != null && result.isNotEmpty()) {
            val list = mutableListOf<String>()
            val firstArray = result[0].jsonArray
            for (item in firstArray) {
                item.jsonPrimitive.contentOrNull?.let { list.add(it) }
            }
            return list
        }
        return emptyList()
    }

    suspend fun actTakePicture(): Boolean {
        val response = sendJsonRpc("actTakePicture")
        return response?.result != null || response?.results != null
    }

    suspend fun startMovieRec(): Boolean {
        val response = sendJsonRpc("startMovieRec")
        return response?.error == null
    }

    suspend fun stopMovieRec(): Boolean {
        val response = sendJsonRpc("stopMovieRec")
        return response?.error == null
    }

    suspend fun setIsoSpeedRate(iso: String): Boolean {
        val response = sendJsonRpc("setIsoSpeedRate", listOf(JsonPrimitive(iso)))
        return response?.error == null
    }

    suspend fun setShutterSpeed(speed: String): Boolean {
        val response = sendJsonRpc("setShutterSpeed", listOf(JsonPrimitive(speed)))
        return response?.error == null
    }

    suspend fun setFNumber(fNumber: String): Boolean {
        val response = sendJsonRpc("setFNumber", listOf(JsonPrimitive(fNumber)))
        return response?.error == null
    }

    suspend fun setExposureCompensation(ev: Int): Boolean {
        val response = sendJsonRpc("setExposureCompensation", listOf(JsonPrimitive(ev)))
        return response?.error == null
    }

    suspend fun actHalfPressShutter(): Boolean {
        val response = sendJsonRpc("actHalfPressShutter")
        return response?.error == null
    }

    suspend fun cancelHalfPressShutter(): Boolean {
        val response = sendJsonRpc("cancelHalfPressShutter")
        return response?.error == null
    }

    suspend fun getEvent(longPolling: Boolean = false): SonyCameraStatus {
        val params = listOf(JsonPrimitive(longPolling))
        val response = sendJsonRpc("getEvent", params, version = "1.0")
        var status = SonyCameraStatus(isConnected = response != null)

        val result = response?.result ?: response?.results
        if (result != null) {
            // No protocolo Sony Camera Remote, getEvent retorna um array de objetos onde cada índice corresponde a um tipo de evento
            // Índice 1: cameraStatus ("IDLE", "MovieRecording", etc.)
            // Índice 2: liveviewStatus
            // Índice 10: storageInformation
            // Índice 11: batteryInfo
            // Índice 18: isoSpeedRate
            // Índice 19: shutterSpeed
            // Índice 20: fNumber
            // Índice 21: exposureCompensation
            // Índice 22: focusStatus
            try {
                for (item in result) {
                    if (item is JsonObject) {
                        val type = item["type"]?.jsonPrimitive?.contentOrNull
                        when (type) {
                            "cameraStatus" -> {
                                val state = item["cameraStatus"]?.jsonPrimitive?.contentOrNull ?: ""
                                status = status.copy(isRecording = state.equals("MovieRecording", ignoreCase = true))
                            }
                            "shootMode" -> {
                                val mode = item["currentShootMode"]?.jsonPrimitive?.contentOrNull ?: ""
                                status = status.copy(shootMode = mode)
                            }
                            "batteryInfo" -> {
                                val level = item["batteryLevel"]?.jsonPrimitive?.contentOrNull ?: ""
                                val isCharging = item["batteryCharging"]?.jsonPrimitive?.contentOrNull.equals("charging", true)
                                status = status.copy(batteryLevel = level, isBatteryCharging = isCharging)
                            }
                            "storageInformation" -> {
                                val count = item["numberOfRecordableImages"]?.jsonPrimitive?.contentOrNull
                                    ?: item["recordableTime"]?.jsonPrimitive?.contentOrNull ?: ""
                                status = status.copy(storageAvailable = count)
                            }
                            "isoSpeedRate" -> {
                                val iso = item["currentIsoSpeedRate"]?.jsonPrimitive?.contentOrNull ?: ""
                                status = status.copy(currentIso = iso)
                            }
                            "shutterSpeed" -> {
                                val speed = item["currentShutterSpeed"]?.jsonPrimitive?.contentOrNull ?: ""
                                status = status.copy(currentShutterSpeed = speed)
                            }
                            "fNumber" -> {
                                val fn = item["currentFNumber"]?.jsonPrimitive?.contentOrNull ?: ""
                                status = status.copy(currentFNumber = fn)
                            }
                            "focusStatus" -> {
                                val focus = item["focusStatus"]?.jsonPrimitive?.contentOrNull ?: ""
                                status = status.copy(focusStatus = focus)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing getEvent: ${e.message}")
            }
        }
        return status
    }
}
