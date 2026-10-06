package com.bragastudio.mobile.network

import android.util.Log
import com.bragastudio.mobile.network.auth.LinkAuthManager
import com.bragastudio.mobile.network.auth.PairState
import com.bragastudio.mobile.network.service.TransferNotifier
import com.bragastudio.mobile.network.sharing.DeviceInfoService
import com.bragastudio.mobile.network.sharing.LutLibraryService
import com.bragastudio.mobile.network.sharing.MediaLibraryService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.streamProvider
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.util.pipeline.PipelineContext
import io.ktor.websocket.*
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val TAG = "LinkServer"

/** Tamanho máximo de uma mensagem de texto recebida no WebSocket. */
internal const val WS_MAX_INCOMING_CHARS = 1024

/** Limite do corpo de `POST /api/pair/request`. */
internal const val PAIR_MAX_BODY_BYTES = 2048L

/** Limite de um upload de LUT (um .cube 65^3 tem ~7-10 MB). */
internal const val LUT_MAX_UPLOAD_BYTES = 32L * 1024 * 1024

/**
 * Dependências do módulo Ktor. Agrupadas para que o módulo seja testável com
 * `testApplication` (ver `LinkServerRoutesTest`) sem subir Netty.
 */
internal class LinkModuleDeps(
    val auth: LinkAuthManager,
    val deviceInfo: DeviceInfoService,
    val media: MediaLibraryService,
    val luts: LutLibraryService,
    val notifier: TransferNotifier,
    val telemetry: LinkTelemetry,
    val currentState: () -> LinkState,
    /** Número de clientes WS autenticados conectados (o MetadataCollector só coleta se > 0). */
    val wsClients: MutableStateFlow<Int>,
    val wsIntervalMs: Long = 500L,
    /** Teto do upload de LUT (parametrizável para teste). */
    val lutMaxUploadBytes: Long = LUT_MAX_UPLOAD_BYTES,
)

internal fun Application.linkModule(deps: LinkModuleDeps) {
    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(15)
        // Só recebemos JSON minúsculo (TALLY_UPDATE); quadros maiores fecham a conexão.
        maxFrameSize = 8 * 1024
        masking = false
    }

    // Sem CORS: o dashboard é servido pelo próprio servidor (same-origin) e os
    // clientes nativos (plugin OBS) não usam CORS.

    routing {
        get("/") {
            call.respondText(LinkDashboard.html, ContentType.Text.Html)
        }

        route("/api") {
            // Tudo sob /api exige token, exceto pareamento e descoberta mínima.
            intercept(ApplicationCallPipeline.Plugins) {
                val path = call.request.path()
                if (path.startsWith("/api/pair/") || path == "/api/discovery/info") return@intercept
                rejectIfUnauthorized(deps.auth)
            }

            // Pública, mas só com identificação básica; telemetria completa exige token.
            get("/discovery/info") {
                val info = deps.deviceInfo.getDeviceInfo()
                if (deps.auth.isAuthorized(call.bearerToken())) {
                    call.respondText(LinkJson.instance.encodeToString(info), ContentType.Application.Json)
                } else {
                    val minimal = buildJsonObject {
                        put("deviceName", info.deviceName)
                        put("deviceModel", info.deviceModel)
                        put("appVersion", info.appVersion)
                        put("authRequired", true)
                    }
                    call.respondText(minimal.toString(), ContentType.Application.Json)
                }
            }

            // ---- Pareamento (consentimento duplo: cliente + celular) ----
            post("/pair/request") {
                val len = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull() ?: 0L
                if (len > PAIR_MAX_BODY_BYTES) return@post call.respond(HttpStatusCode.PayloadTooLarge)
                // Content-Length pode faltar (chunked): o corpo é lido com teto de qualquer forma.
                val raw = call.receiveBodyLimited(PAIR_MAX_BODY_BYTES.toInt())
                    ?: return@post call.respond(HttpStatusCode.PayloadTooLarge)
                val obj = try {
                    LinkJson.instance.parseToJsonElement(String(raw, Charsets.UTF_8)).jsonObject
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Invalid JSON")
                }
                val clientId = obj["clientId"]?.jsonPrimitive?.content.orEmpty()
                val clientName = obj["clientName"]?.jsonPrimitive?.content.orEmpty()
                val req = deps.auth.createRequest(clientId, clientName, call.request.origin.remoteHost)
                    ?: return@post call.respond(HttpStatusCode.TooManyRequests, "Pending pairing request exists")
                val out = buildJsonObject {
                    put("requestId", req.id)
                    put("code", req.code)
                    put("expiresInSec", 90)
                }
                call.respondText(out.toString(), ContentType.Application.Json)
            }

            get("/pair/status/{id}") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val req = deps.auth.pollRequest(id)
                // Só quem fez o pedido (mesmo endereço) pode ler o resultado/token.
                if (req == null || req.remoteAddress != call.request.origin.remoteHost) {
                    return@get call.respond(HttpStatusCode.NotFound)
                }
                val out = buildJsonObject {
                    put("state", req.state.name)
                    if (req.state == PairState.APPROVED && req.token != null) put("token", req.token)
                }
                call.respondText(out.toString(), ContentType.Application.Json)
            }

            get("/media") {
                val list = deps.media.getMediaList()
                call.respondText(LinkJson.instance.encodeToString(list), ContentType.Application.Json)
            }

            get("/media/{id}/download") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val file = deps.media.getMediaFile(id)
                if (file == null) {
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    // Range/retomada; a notificação "copiada" só dispara quando o último
                    // byte foi entregue (não a cada GET nem a cada retomada parcial).
                    call.respondFileWithRange(file) { deps.notifier.notifyRecordingsCopied(file.name) }
                }
            }

            get("/media/{id}/thumbnail") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val file = deps.media.getThumbnailFile(id)
                if (file != null) call.respondFile(file) else call.respond(HttpStatusCode.NotFound)
            }

            delete("/media/{id}") {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                if (deps.media.deleteMedia(id)) {
                    call.respond(HttpStatusCode.OK)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            get("/luts") {
                val luts = deps.luts.getLutsList()
                call.respondText(LinkJson.instance.encodeToString(luts), ContentType.Application.Json)
            }

            delete("/luts/{path...}") {
                val pathParams = call.parameters.getAll("path") ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val relativePath = pathParams.joinToString("/")
                if (deps.luts.resolveSafe(relativePath) == null) {
                    return@delete call.respond(HttpStatusCode.BadRequest, "Invalid path")
                }
                if (deps.luts.deleteLut(relativePath)) {
                    call.respond(HttpStatusCode.OK)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            post("/luts/upload") {
                var relativePath = ""
                var fileBytes: ByteArray? = null

                try {
                    val multipart = call.receiveMultipart()
                    while (true) {
                        val part = multipart.readPart() ?: break
                        when (part) {
                            is PartData.FormItem -> {
                                if (part.name == "relativePath") relativePath = part.value
                            }

                            is PartData.FileItem -> {
                                // Evita OOM por upload gigante.
                                val out = java.io.ByteArrayOutputStream()
                                val buf = ByteArray(64 * 1024)
                                part.streamProvider().use { input ->
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        if (out.size() + n > deps.lutMaxUploadBytes) {
                                            part.dispose()
                                            return@post call.respond(HttpStatusCode.PayloadTooLarge, "File too large")
                                        }
                                        out.write(buf, 0, n)
                                    }
                                }
                                fileBytes = out.toByteArray()
                            }

                            else -> {}
                        }
                        part.dispose()
                    }
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Invalid multipart data")
                }

                val bytes = fileBytes
                if (relativePath.isBlank() || bytes == null) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Missing relativePath or file")
                }
                if (deps.luts.resolveSafe(relativePath) == null) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Invalid relativePath")
                }

                val newHash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes).joinToString("") { "%02x".format(it) }

                val conflict = deps.luts.checkConflict(relativePath, newHash)
                if (conflict != null) {
                    call.respondText(
                        LinkJson.instance.encodeToString(conflict),
                        ContentType.Application.Json,
                        HttpStatusCode.Conflict,
                    )
                    return@post
                }

                if (deps.luts.saveLut(relativePath, bytes)) {
                    deps.notifier.notifyLutsSynced(1)
                    call.respond(HttpStatusCode.OK)
                } else {
                    call.respond(HttpStatusCode.InternalServerError)
                }
            }
        }

        route("/ws") {
            intercept(ApplicationCallPipeline.Plugins) { rejectIfUnauthorized(deps.auth) }

            webSocket("/link") {
                val token = call.bearerToken()
                deps.wsClients.update { it + 1 }
                Log.i(TAG, "Cliente WebSocket conectado")
                val revoked = CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Access revoked")
                try {
                    // Envio periódico; o token é revalidado a CADA ciclo, então revogar um
                    // cliente derruba a conexão em até ~1 ciclo (500 ms).
                    val sender = launch {
                        while (isActive) {
                            if (!deps.auth.isAuthorized(token)) {
                                close(revoked)
                                return@launch
                            }
                            send(Frame.Text(LinkJson.instance.encodeToString(deps.currentState())))
                            delay(deps.wsIntervalMs)
                        }
                    }
                    try {
                        for (frame in incoming) {
                            if (!deps.auth.isAuthorized(token)) {
                                close(revoked)
                                break
                            }
                            if (frame is Frame.Text) handleClientMessage(deps, frame.readText())
                        }
                    } finally {
                        sender.cancel()
                    }
                } catch (e: ClosedReceiveChannelException) {
                    // Cliente fechou normalmente.
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "WebSocket encerrado: ${e.message}")
                } finally {
                    deps.wsClients.update { it - 1 }
                    Log.i(TAG, "Cliente WebSocket desconectado")
                }
            }
        }
    }
}

/**
 * Mensagens de entrada do cliente (sempre de um cliente já autenticado):
 * `{"type":"TALLY_UPDATE","state":"PROGRAM|PREVIEW|OFF"}`. O resto é ignorado.
 */
internal fun handleClientMessage(deps: LinkModuleDeps, text: String) {
    if (text.length > WS_MAX_INCOMING_CHARS) return
    try {
        val obj = LinkJson.instance.parseToJsonElement(text).jsonObject
        if (obj["type"]?.jsonPrimitive?.content != "TALLY_UPDATE") return
        val state = obj["state"]?.jsonPrimitive?.content?.uppercase() ?: return
        val tally = TallyState.values().firstOrNull { it.name == state } ?: return
        deps.telemetry.setTally(tally)
    } catch (e: Exception) {
        // JSON inválido de um cliente: ignora, sem derrubar a conexão.
    }
}

/** Lê no máximo [max] bytes do corpo; retorna null se o corpo for maior que isso. */
internal suspend fun ApplicationCall.receiveBodyLimited(max: Int): ByteArray? {
    val channel = receiveChannel()
    val buf = ByteArray(max + 1)
    var total = 0
    while (total < buf.size) {
        val n = channel.readAvailable(buf, total, buf.size - total)
        if (n < 0) break
        total += n
    }
    return if (total > max) null else buf.copyOf(total)
}

internal fun ApplicationCall.bearerToken(): String? {
    val h = request.header(HttpHeaders.Authorization)
    if (h != null && h.startsWith("Bearer ", ignoreCase = true)) return h.substring(7).trim()
    return request.queryParameters["token"]
}

private suspend fun PipelineContext<Unit, ApplicationCall>.rejectIfUnauthorized(auth: LinkAuthManager) {
    if (!auth.isAuthorized(call.bearerToken())) {
        call.respond(HttpStatusCode.Unauthorized, "Pairing required")
        finish()
    }
}
