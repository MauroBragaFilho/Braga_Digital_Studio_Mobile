package com.braga.bdsm.network

import android.util.Log
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.http.ContentType
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import com.braga.bdsm.network.sharing.DeviceInfoService
import com.braga.bdsm.network.sharing.MediaLibraryService
import com.braga.bdsm.network.sharing.LutLibraryService
import io.ktor.server.request.receiveMultipart
import io.ktor.http.content.PartData
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondFile
import io.ktor.server.request.receive
import io.ktor.http.content.streamProvider

@Singleton
class LinkServer @Inject constructor(
    private val discoveryService: DiscoveryService,
    private val deviceInfoService: DeviceInfoService,
    private val mediaLibraryService: MediaLibraryService,
    private val lutLibraryService: LutLibraryService
) {
    private var server: NettyApplicationEngine? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + Job())
    private val port = 8080 // Default port for BDSM Link

    // Metadados atuais, atualizados pelo MetadataCollector
    var currentState: LinkState = LinkState()
        set(value) {
            field = value
            Log.d("LinkServer", "State updated: $value")
        }

    fun start() {
        if (server != null) return

        server = embeddedServer(Netty, port = port, module = { module() })
        serverScope.launch {
            try {
                server?.start(wait = true)
            } catch (e: Exception) {
                Log.e("LinkServer", "Error starting Ktor server", e)
            }
        }
        
        // Registrar mDNS
        discoveryService.registerService(port)
        Log.i("LinkServer", "Server started on port $port")
    }

    fun stop() {
        discoveryService.unregisterService()
        server?.stop(1000, 2000)
        server = null
        Log.i("LinkServer", "Server stopped")
    }

    private fun Application.module() {
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(15)
            timeout = Duration.ofSeconds(15)
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }
        
        install(CORS) {
            anyHost() // Allow any host for the local network plugin
        }

        routing {
            get("/") {
                call.respondText(getDashboardHtml(), ContentType.Text.Html)
            }

            // Sharing Services Routes
            get("/api/discovery/info") {
                val info = deviceInfoService.getDeviceInfo()
                call.respondText(Json.encodeToString(info), ContentType.Application.Json)
            }

            get("/api/media") {
                val list = mediaLibraryService.getMediaList()
                call.respondText(Json.encodeToString(list), ContentType.Application.Json)
            }

            get("/api/media/{id}/download") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val file = mediaLibraryService.getMediaFile(id)
                if (file != null) {
                    call.respondFile(file)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            get("/api/media/{id}/thumbnail") {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val file = mediaLibraryService.getThumbnailFile(id)
                if (file != null) {
                    call.respondFile(file)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            delete("/api/media/{id}") {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val success = mediaLibraryService.deleteMedia(id)
                if (success) {
                    call.respond(HttpStatusCode.OK)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            get("/api/luts") {
                val luts = lutLibraryService.getLutsList()
                call.respondText(Json.encodeToString(luts), ContentType.Application.Json)
            }

            delete("/api/luts/{path...}") {
                val pathParams = call.parameters.getAll("path") ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val relativePath = pathParams.joinToString("/")
                val success = lutLibraryService.deleteLut(relativePath)
                if (success) {
                    call.respond(HttpStatusCode.OK)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }

            post("/api/luts/upload") {
                var relativePath = ""
                var fileBytes: ByteArray? = null
                
                try {
                    val multipart = call.receiveMultipart()
                    while (true) {
                        val part = multipart.readPart() ?: break
                        when (part) {
                            is PartData.FormItem -> {
                                if (part.name == "relativePath") {
                                    relativePath = part.value
                                }
                            }
                            is PartData.FileItem -> {
                                fileBytes = part.streamProvider().readBytes()
                            }
                            else -> {}
                        }
                        part.dispose()
                    }
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Invalid multipart data")
                }

                if (relativePath.isBlank() || fileBytes == null) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Missing relativePath or file")
                }

                val newHash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(fileBytes).joinToString("") { "%02x".format(it) }

                val conflict = lutLibraryService.checkConflict(relativePath, newHash)
                if (conflict != null) {
                    call.respondText(
                        Json.encodeToString(conflict), 
                        ContentType.Application.Json,
                        HttpStatusCode.Conflict
                    )
                    return@post
                }

                val success = lutLibraryService.saveLut(relativePath, fileBytes!!)
                if (success) {
                    call.respond(HttpStatusCode.OK)
                } else {
                    call.respond(HttpStatusCode.InternalServerError)
                }
            }


            webSocket("/ws/link") {
                Log.i("LinkServer", "Client connected to WebSocket")
                try {
                    while (isActive) {
                        // Send the state at 2 Hz (every 500ms)
                        val json = Json.encodeToString(currentState)
                        send(Frame.Text(json))
                        delay(500)
                    }
                } catch (e: Exception) {
                    Log.e("LinkServer", "WebSocket error or disconnected", e)
                } finally {
                    Log.i("LinkServer", "Client disconnected from WebSocket")
                }
            }
        }
    }

    private fun getDashboardHtml(): String {
        return """
            <!DOCTYPE html>
            <html lang="pt-BR">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>BDSM Link Dashboard</title>
                <style>
                    :root {
                        --bg-color: #0F172A;
                        --panel-bg: rgba(30, 41, 59, 0.7);
                        --accent-color: #38BDF8;
                        --text-primary: #F8FAFC;
                        --text-secondary: #94A3B8;
                        --border-color: rgba(255, 255, 255, 0.1);
                        --success: #10B981;
                        --warning: #F59E0B;
                        --danger: #EF4444;
                    }
                    
                    body {
                        margin: 0;
                        padding: 0;
                        background-color: transparent;
                        color: var(--text-primary);
                        font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
                        overflow: hidden;
                    }

                    /* When loaded in OBS, the body might have a default bg. We want to be transparent if possible, or use our dark bg */
                    .dashboard-container {
                        width: 100vw;
                        height: 100vh;
                        background: var(--bg-color);
                        display: flex;
                        flex-direction: column;
                        box-sizing: border-box;
                        padding: 16px;
                    }

                    .header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        margin-bottom: 16px;
                        padding-bottom: 12px;
                        border-bottom: 1px solid var(--border-color);
                    }

                    .title-area {
                        display: flex;
                        align-items: center;
                        gap: 12px;
                    }

                    .logo {
                        width: 12px;
                        height: 12px;
                        border-radius: 50%;
                        background-color: var(--danger);
                        box-shadow: 0 0 10px var(--danger);
                        animation: pulse 2s infinite;
                    }

                    .title {
                        font-size: 1.1rem;
                        font-weight: 600;
                        letter-spacing: 0.5px;
                    }
                    
                    .device-name {
                        font-size: 0.85rem;
                        color: var(--accent-color);
                        font-weight: 500;
                    }

                    .grid {
                        display: grid;
                        grid-template-columns: repeat(2, 1fr);
                        gap: 12px;
                        flex-grow: 1;
                    }

                    .card {
                        background: var(--panel-bg);
                        border: 1px solid var(--border-color);
                        border-radius: 12px;
                        padding: 16px;
                        display: flex;
                        flex-direction: column;
                        justify-content: center;
                        backdrop-filter: blur(10px);
                        -webkit-backdrop-filter: blur(10px);
                        transition: transform 0.2s, background-color 0.2s;
                    }

                    .card:hover {
                        background: rgba(30, 41, 59, 0.9);
                        transform: translateY(-2px);
                    }

                    .card-title {
                        font-size: 0.75rem;
                        text-transform: uppercase;
                        color: var(--text-secondary);
                        letter-spacing: 1px;
                        margin-bottom: 8px;
                    }

                    .card-value {
                        font-size: 1.8rem;
                        font-weight: 700;
                        display: flex;
                        align-items: baseline;
                        gap: 4px;
                    }

                    .card-unit {
                        font-size: 0.9rem;
                        font-weight: 500;
                        color: var(--text-secondary);
                    }

                    .battery-indicator {
                        display: flex;
                        align-items: center;
                        gap: 8px;
                    }

                    .battery-icon {
                        width: 24px;
                        height: 12px;
                        border: 2px solid var(--text-primary);
                        border-radius: 3px;
                        position: relative;
                        padding: 2px;
                    }

                    .battery-icon::after {
                        content: '';
                        position: absolute;
                        right: -5px;
                        top: 2px;
                        width: 3px;
                        height: 8px;
                        background: var(--text-primary);
                        border-radius: 0 2px 2px 0;
                    }

                    .battery-level {
                        height: 100%;
                        background: var(--success);
                        border-radius: 1px;
                        transition: width 0.5s ease-in-out, background-color 0.5s ease-in-out;
                    }

                    .status-dot {
                        width: 8px;
                        height: 8px;
                        border-radius: 50%;
                        background-color: var(--text-secondary);
                        margin-right: 6px;
                        display: inline-block;
                    }

                    .status-connected {
                        background-color: var(--success);
                        box-shadow: 0 0 8px var(--success);
                    }

                    .status-disconnected {
                        background-color: var(--danger);
                        box-shadow: 0 0 8px var(--danger);
                    }

                    .footer {
                        margin-top: 16px;
                        font-size: 0.75rem;
                        color: var(--text-secondary);
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                    }

                    @keyframes pulse {
                        0% { box-shadow: 0 0 0 0 rgba(239, 68, 68, 0.7); }
                        70% { box-shadow: 0 0 0 6px rgba(239, 68, 68, 0); }
                        100% { box-shadow: 0 0 0 0 rgba(239, 68, 68, 0); }
                    }

                    /* Google Fonts */
                    @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap');
                </style>
            </head>
            <body>
                <div class="dashboard-container">
                    <div class="header">
                        <div class="title-area">
                            <div class="logo" id="rec-indicator"></div>
                            <div class="title">BDSM Monitor</div>
                        </div>
                        <div class="device-name" id="device-name">Waiting...</div>
                    </div>

                    <div class="grid">
                        <div class="card">
                            <div class="card-title">FPS</div>
                            <div class="card-value">
                                <span id="fps-val">--</span>
                                <span class="card-unit">fps</span>
                            </div>
                        </div>

                        <div class="card">
                            <div class="card-title">Battery</div>
                            <div class="battery-indicator">
                                <div class="battery-icon">
                                    <div class="battery-level" id="battery-bar" style="width: 0%;"></div>
                                </div>
                                <div class="card-value" style="font-size: 1.2rem;">
                                    <span id="battery-val">--</span><span class="card-unit">%</span>
                                </div>
                            </div>
                            <div id="charging-status" style="font-size: 0.75rem; color: var(--accent-color); margin-top: 4px;"></div>
                        </div>

                        <div class="card" style="grid-column: span 2;">
                            <div class="card-title">Camera & Source</div>
                            <div style="display: flex; justify-content: space-between; align-items: flex-end;">
                                <div class="card-value" style="font-size: 1.2rem;">
                                    <span id="lens-val">--</span>
                                </div>
                                <div style="font-size: 0.9rem; color: var(--accent-color);" id="source-val">--</div>
                            </div>
                        </div>
                    </div>

                    <div class="footer">
                        <div>
                            <span class="status-dot" id="conn-dot"></span>
                            <span id="conn-text">Connecting...</span>
                        </div>
                        <div id="mic-val">Mic: --</div>
                    </div>
                </div>

                <script>
                    const UI = {
                        deviceName: document.getElementById('device-name'),
                        fps: document.getElementById('fps-val'),
                        batteryBar: document.getElementById('battery-bar'),
                        batteryVal: document.getElementById('battery-val'),
                        charging: document.getElementById('charging-status'),
                        lens: document.getElementById('lens-val'),
                        source: document.getElementById('source-val'),
                        mic: document.getElementById('mic-val'),
                        connDot: document.getElementById('conn-dot'),
                        connText: document.getElementById('conn-text'),
                        recIndicator: document.getElementById('rec-indicator')
                    };

                    let ws = null;
                    let reconnectInterval = null;

                    function connect() {
                        const wsUrl = `ws://${'$'}{window.location.host}/ws/link`;
                        ws = new WebSocket(wsUrl);

                        ws.onopen = () => {
                            UI.connDot.className = 'status-dot status-connected';
                            UI.connText.innerText = 'Connected';
                            UI.recIndicator.style.animationPlayState = 'running';
                            if (reconnectInterval) {
                                clearInterval(reconnectInterval);
                                reconnectInterval = null;
                            }
                        };

                        ws.onmessage = (event) => {
                            try {
                                const data = JSON.parse(event.data);
                                updateUI(data);
                            } catch(e) {
                                console.error("Error parsing WS data", e);
                            }
                        };

                        ws.onclose = () => {
                            UI.connDot.className = 'status-dot status-disconnected';
                            UI.connText.innerText = 'Disconnected';
                            UI.recIndicator.style.animationPlayState = 'paused';
                            
                            // Try to reconnect every 3 seconds
                            if (!reconnectInterval) {
                                reconnectInterval = setInterval(connect, 3000);
                            }
                        };

                        ws.onerror = (err) => {
                            console.error("WebSocket error", err);
                            ws.close();
                        };
                    }

                    function updateUI(data) {
                        UI.deviceName.innerText = data.deviceName || "BDSM Device";
                        UI.fps.innerText = data.fps || 0;
                        
                        const bat = data.batteryLevel || 0;
                        UI.batteryVal.innerText = bat;
                        UI.batteryBar.style.width = bat + '%';
                        
                        if (bat <= 20) {
                            UI.batteryBar.style.backgroundColor = 'var(--danger)';
                        } else if (bat <= 50) {
                            UI.batteryBar.style.backgroundColor = 'var(--warning)';
                        } else {
                            UI.batteryBar.style.backgroundColor = 'var(--success)';
                        }

                        if (data.isCharging) {
                            UI.charging.innerText = "⚡ Charging";
                        } else {
                            UI.charging.innerText = "";
                        }

                        UI.lens.innerText = data.cameraLens || "Unknown";
                        UI.source.innerText = data.captureSource || "Unknown";
                        UI.mic.innerText = "Mic: " + (data.microphone || "Unknown");
                    }

                    // Start connection
                    connect();
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
