package com.bragastudio.mobile.network.sony

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SonyCameraDevice(
    val friendlyName: String,
    val modelName: String = "",
    val ddUrl: String,
    val endpointUrl: String,
    val liveviewUrl: String = "",
)

@Serializable
data class SonyJsonRpcRequest(
    val method: String,
    val params: List<JsonElement> = emptyList(),
    val id: Int = 1,
    val version: String = "1.0",
)

@Serializable
data class SonyJsonRpcResponse(
    val id: Int = 1,
    val result: List<JsonElement>? = null,
    val results: List<JsonElement>? = null,
    val error: List<JsonElement>? = null,
)

/**
 * Resposta JSON-RPC bem-sucedida: houve resposta HTTP 2xx decodificada e SEM campo `error`.
 * Antes, `response?.error == null` era `true` também quando a requisição falhava (resposta nula).
 */
fun SonyJsonRpcResponse?.isOk(): Boolean = this != null && error == null

/** Código numérico do erro JSON-RPC (primeiro item de `error`), ou null. */
fun SonyJsonRpcResponse?.errorCode(): Int? = (this?.error?.firstOrNull() as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
