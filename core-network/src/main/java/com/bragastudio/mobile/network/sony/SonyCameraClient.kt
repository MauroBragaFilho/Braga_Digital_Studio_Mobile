package com.bragastudio.mobile.network.sony

import android.util.Log
import com.bragastudio.mobile.core.domain.VideoSources
import com.bragastudio.mobile.core.model.SonyCameraStatus
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class SonyCameraClient(@Volatile private var endpointUrl: String = "http://192.168.122.1:8080/sony/camera") {

    companion object {
        private const val TAG = "SonyCameraClient"
        private const val MAX_RESPONSE_BYTES = 256 * 1024

        /** "Already running": a câmera já está no estado pedido (não é falha). */
        private const val ERROR_ALREADY_RUNNING = 40402
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Ids de JSON-RPC únicos, mesmo com chamadas concorrentes. */
    private val reqId = AtomicInteger(1)

    /** A câmera atende uma requisição por vez; comandos concorrentes são enfileirados. */
    private val requestMutex = Mutex()

    @Volatile
    private var lastStatus = SonyCameraStatus()

    fun updateEndpoint(endpoint: String) {
        this.endpointUrl = endpoint
    }

    suspend fun sendJsonRpc(
        method: String,
        params: List<JsonElement> = emptyList(),
        version: String = "1.0",
        endpoint: String = endpointUrl,
    ): SonyJsonRpcResponse? = requestMutex.withLock { performRpc(method, params, version, endpoint) }

    private suspend fun performRpc(
        method: String,
        params: List<JsonElement>,
        version: String,
        endpoint: String,
    ): SonyJsonRpcResponse? = withContext(Dispatchers.IO) {
        // Recurso desligado: nenhuma requisição HTTP à câmera (único ponto de saída dos comandos).
        if (!VideoSources.sonyWifiEnabled()) return@withContext null
        var connection: HttpURLConnection? = null
        try {
            connection = SonyNetwork.openConnection(URL(endpoint)) as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 5000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            val request = SonyJsonRpcRequest(
                method = method,
                params = params,
                id = reqId.getAndIncrement(),
                version = version,
            )
            val jsonString = json.encodeToString(SonyJsonRpcRequest.serializer(), request)

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonString)
                writer.flush()
            }

            if (connection.responseCode in 200..299) {
                val bytes = connection.inputStream.use { SonyProtocolRules.readLimited(it, MAX_RESPONSE_BYTES) }
                json.decodeFromString(SonyJsonRpcResponse.serializer(), String(bytes, Charsets.UTF_8))
            } else {
                Log.w(TAG, "HTTP error: ${connection.responseCode} on method $method")
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "RPC Call Exception for $method: ${e.message}")
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (e: Exception) { }
        }
    }

    private fun SonyJsonRpcResponse?.resultList(): List<JsonElement>? = this?.result ?: this?.results

    suspend fun startRecMode(): Boolean {
        val response = sendJsonRpc("startRecMode")
        return response.isOk() || response.errorCode() == ERROR_ALREADY_RUNNING
    }

    suspend fun stopRecMode(): Boolean = sendJsonRpc("stopRecMode").isOk()

    suspend fun startLiveview(): String? {
        val response = sendJsonRpc("startLiveview")
        if (!response.isOk()) return null
        val first = response.resultList()?.firstOrNull()
        return (first as? JsonPrimitive)?.contentOrNull
    }

    suspend fun stopLiveview(): Boolean = sendJsonRpc("stopLiveview").isOk()

    suspend fun getAvailableApiList(): List<String> {
        val response = sendJsonRpc("getAvailableApiList")
        if (!response.isOk()) return emptyList()
        val firstArray = response.resultList()?.firstOrNull() as? JsonArray ?: return emptyList()
        return firstArray.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    suspend fun actTakePicture(): Boolean {
        val response = sendJsonRpc("actTakePicture")
        return response.isOk() && response.resultList() != null
    }

    suspend fun startMovieRec(): Boolean = sendJsonRpc("startMovieRec").isOk()

    suspend fun stopMovieRec(): Boolean = sendJsonRpc("stopMovieRec").isOk()

    suspend fun setIsoSpeedRate(iso: String): Boolean = sendJsonRpc("setIsoSpeedRate", listOf(JsonPrimitive(iso))).isOk()

    suspend fun setShutterSpeed(speed: String): Boolean = sendJsonRpc("setShutterSpeed", listOf(JsonPrimitive(speed))).isOk()

    suspend fun setFNumber(fNumber: String): Boolean = sendJsonRpc("setFNumber", listOf(JsonPrimitive(fNumber))).isOk()

    suspend fun setExposureCompensation(ev: Int): Boolean = sendJsonRpc("setExposureCompensation", listOf(JsonPrimitive(ev))).isOk()

    suspend fun getAvailableExposureMode(): List<String> {
        val response = sendJsonRpc("getAvailableExposureMode")
        if (!response.isOk()) return emptyList()
        // Resultado: [atual, [modos disponíveis]]
        val list = response.resultList() ?: return emptyList()
        val candidates = list.filterIsInstance<JsonArray>().firstOrNull() ?: return emptyList()
        return candidates.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    suspend fun setExposureMode(mode: String): Boolean = sendJsonRpc("setExposureMode", listOf(JsonPrimitive(mode))).isOk()

    /**
     * Devolve a íris ao controle automático da câmera: escolhe, entre os modos suportados, o
     * primeiro que decide a abertura sozinho (Program Auto, depois Intelligent Auto/Superior Auto,
     * depois Shutter Priority). Sem a lista de modos, tenta "Program Auto" direto.
     */
    suspend fun setIrisAuto(): Boolean {
        val available = getAvailableExposureMode()
        val preferred = listOf("Program Auto", "Intelligent Auto", "Superior Auto", "Shutter")
        val mode = if (available.isEmpty()) "Program Auto" else preferred.firstOrNull { it in available } ?: return false
        return setExposureMode(mode)
    }

    suspend fun actHalfPressShutter(): Boolean = sendJsonRpc("actHalfPressShutter").isOk()

    suspend fun cancelHalfPressShutter(): Boolean = sendJsonRpc("cancelHalfPressShutter").isOk()

    /**
     * Estado da câmera via `getEvent`. O resultado é MESCLADO com o último estado conhecido:
     * o getEvent traz só os blocos que mudaram (ou "nulos"), então recriar o status a cada
     * poll zerava valores como bateria e cartão. Se a requisição falhar, devolve o último
     * estado com `isConnected = false`.
     */
    suspend fun getEvent(longPolling: Boolean = false): SonyCameraStatus {
        val response = sendJsonRpc("getEvent", listOf(JsonPrimitive(longPolling)), version = "1.0")
        if (response == null) {
            lastStatus = lastStatus.copy(isConnected = false)
            return lastStatus
        }
        var status = lastStatus.copy(isConnected = true)

        val result = response.resultList()
        if (response.error == null && result != null) {
            try {
                for (element in result) {
                    // Alguns blocos vêm embrulhados em arrays de objetos.
                    val items: List<JsonElement> = if (element is JsonArray) element else listOf(element)
                    for (item in items) {
                        if (item is JsonObject) status = applyEventItem(status, item)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing getEvent: ${e.message}")
            }
        }
        lastStatus = status
        return status
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun applyEventItem(status: SonyCameraStatus, item: JsonObject): SonyCameraStatus {
        return when (item.str("type")) {
            "availableApiList" -> {
                val names = (item["names"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if (names != null) status.copy(availableApiList = names.toSet()) else status
            }

            "cameraStatus" -> {
                val state = item.str("cameraStatus") ?: return status
                status.copy(isRecording = state.equals("MovieRecording", ignoreCase = true))
            }

            "shootMode" -> item.str("currentShootMode")?.let { status.copy(shootMode = it) } ?: status

            "batteryInfo" -> {
                // Formato real: {"type":"batteryInfo","batteryInfo":[{"levelNumer":3,"levelDenom":4,"batteryStatus":"Active"}]}
                val entry = (item["batteryInfo"] as? JsonArray)?.firstOrNull() as? JsonObject
                val numer = entry?.str("levelNumer")?.toIntOrNull()
                val denom = entry?.str("levelDenom")?.toIntOrNull()
                val level = if (numer != null && denom != null && denom > 0) {
                    (numer * 100 / denom).toString()
                } else {
                    item.str("batteryLevel")
                }
                val charging = entry?.str("batteryStatus") ?: item.str("batteryCharging")
                status.copy(
                    batteryLevel = level ?: status.batteryLevel,
                    isBatteryCharging = charging?.equals("charging", ignoreCase = true) ?: status.isBatteryCharging,
                )
            }

            "storageInformation" -> {
                val entry = (item["storageInformation"] as? JsonArray)?.firstOrNull() as? JsonObject ?: item
                val count = entry.str("numberOfRecordableImages") ?: entry.str("recordableTime")
                if (count != null) status.copy(storageAvailable = count) else status
            }

            "isoSpeedRate" -> item.str("currentIsoSpeedRate")?.let { status.copy(currentIso = it) } ?: status

            "shutterSpeed" -> item.str("currentShutterSpeed")?.let { status.copy(currentShutterSpeed = it) } ?: status

            "fNumber" -> item.str("currentFNumber")?.let { status.copy(currentFNumber = it) } ?: status

            "exposureCompensation" ->
                item.str("currentExposureCompensation")?.let { status.copy(currentExposureCompensation = it) } ?: status

            "focusStatus" -> item.str("focusStatus")?.let { status.copy(focusStatus = it) } ?: status

            else -> status
        }
    }
}
