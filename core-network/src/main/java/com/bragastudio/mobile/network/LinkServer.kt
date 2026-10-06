package com.bragastudio.mobile.network

import android.util.Log
import com.bragastudio.mobile.network.auth.LinkAuthManager
import com.bragastudio.mobile.network.service.TransferNotifier
import com.bragastudio.mobile.network.sharing.DeviceInfoService
import com.bragastudio.mobile.network.sharing.LutLibraryService
import com.bragastudio.mobile.network.sharing.MediaLibraryService
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Servidor HTTP/WebSocket do BDSM Link (Ktor + Netty, porta 8080) e anúncio mDNS.
 *
 * Ciclo de vida: [start] e [stop] só ENFILEIRAM o comando; um único consumidor os executa
 * em ordem, fora da thread principal (parar o Netty pode demorar segundos). O resultado
 * fica em [state] (e em [LinkServerStatus.state]): o mDNS só é registrado depois de o
 * bind da porta dar certo e uma falha (porta ocupada) é visível em vez de engolida.
 */
@Singleton
class LinkServer @Inject constructor(
    private val discoveryService: DiscoveryService,
    private val deviceInfoService: DeviceInfoService,
    private val mediaLibraryService: MediaLibraryService,
    private val lutLibraryService: LutLibraryService,
    private val transferNotifier: TransferNotifier,
    private val linkAuthManager: LinkAuthManager,
    private val linkTelemetry: LinkTelemetry,
) {
    companion object {
        private const val TAG = "LinkServer"
        const val PORT = 8080
    }

    private var server: NettyApplicationEngine? = null
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Comandos de ciclo de vida, executados em ordem: true = iniciar, false = parar. */
    private val lifecycle = Channel<Boolean>(Channel.UNLIMITED)

    /** Estado do servidor (A9). */
    val state: StateFlow<ServerState> get() = LinkServerStatus.state

    private val _wsClients = MutableStateFlow(0)

    /** Quantidade de clientes WebSocket conectados e autenticados. */
    val wsClientCount: StateFlow<Int> = _wsClients.asStateFlow()

    /** Metadados atuais, atualizados pelo [MetadataCollector]. */
    @Volatile
    var currentState: LinkState = LinkState()

    init {
        serverScope.launch {
            for (start in lifecycle) {
                if (start) doStart() else doStop()
            }
        }
    }

    /** Pede para iniciar o servidor (idempotente). Retorna imediatamente. */
    fun start() {
        lifecycle.trySend(true)
    }

    /** Pede para parar o servidor e o mDNS. Retorna imediatamente (parada assíncrona). */
    fun stop() {
        lifecycle.trySend(false)
    }

    private fun doStart() {
        if (server != null) return
        LinkServerStatus.set(ServerState.Starting)
        val deps = LinkModuleDeps(
            auth = linkAuthManager,
            deviceInfo = deviceInfoService,
            media = mediaLibraryService,
            luts = lutLibraryService,
            notifier = transferNotifier,
            telemetry = linkTelemetry,
            currentState = { currentState },
            wsClients = _wsClients,
        )
        val engine = embeddedServer(Netty, port = PORT, host = "0.0.0.0", module = { linkModule(deps) })
        try {
            // wait = false: retorna depois do bind; BindException chega aqui.
            engine.start(wait = false)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao abrir a porta $PORT", e)
            try {
                engine.stop(0, 500)
            } catch (ignored: Exception) { }
            LinkServerStatus.set(ServerState.Failed(e.message ?: e.javaClass.simpleName))
            return
        }
        server = engine
        // mDNS só depois do bind: nunca anunciar um serviço inexistente.
        discoveryService.registerService(PORT)
        LinkServerStatus.set(ServerState.Running(PORT))
        Log.i(TAG, "Servidor iniciado na porta $PORT")
    }

    private fun doStop() {
        discoveryService.unregisterService()
        val engine = server
        server = null
        if (engine != null) {
            try {
                engine.stop(500, 1000)
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao parar o servidor: ${e.message}")
            }
        }
        _wsClients.value = 0
        LinkServerStatus.set(ServerState.Stopped)
        Log.i(TAG, "Servidor parado")
    }
}
