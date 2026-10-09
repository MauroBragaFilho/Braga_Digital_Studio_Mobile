package com.bragastudio.mobile.coremedia.bsp

import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

private const val TAG = "BspControl"

/** Fonte das chaves dos receptores pareados (implementada sobre o `LinkAuthManager`; falsa nos testes). */
interface BspAuthority {
    /** `Kt = SHA-256(token)` (32 bytes, cópia nova) do cliente pareado, ou null. */
    fun clientKey(clientId: String): ByteArray?

    /** Nome gravado no pareamento (nunca o IP). */
    fun clientName(clientId: String): String?
}

/** Tudo que a sessão precisa saber da fonte para montar o WELCOME. */
class BspStreamParams(
    val video: BspVideoParams,
    val audio: BspAudioParams?,
    val net: BspNetParams,
    val keyframeIntervalMs: Int,
)

interface BspSessionProvider {
    val deviceName: String

    /** Mídia sem criptografia só com esta chave ligada (depuração; padrão desligado). */
    val allowPlainMedia: Boolean

    /** Parâmetros atuais, ou null se a fonte ainda não está pronta (encoder parado). */
    fun streamParams(): BspStreamParams?
}

/** Quem consome os eventos de sessão (o `BspManager`). Chamado em threads da sessão: não bloqueie. */
interface BspSessionListener {
    /** O receptor confirmou o READY: iniciar a mídia para ele. */
    fun onSessionReady(session: BspSession)

    /** O receptor pediu START/STOP ou a sessão mudou de estado visível. */
    fun onSessionChanged(session: BspSession)

    /** A sessão terminou (qualquer motivo). Só dispara para sessões autenticadas. */
    fun onSessionClosed(session: BspSession)
}

/** Foto imutável de uma sessão para a UI (SOMENTE nome, nunca o IP). */
data class BspSessionSnapshot(val id: Int, val name: String, val ready: Boolean, val paused: Boolean, val rttMs: Long)

/**
 * Uma sessão de controle autenticada. Os campos mutáveis são lidos por outras threads.
 * [remoteAddress] serve só para direcionar a mídia: NUNCA é logado nem mostrado.
 */
class BspSession internal constructor(
    val id: Int,
    val clientId: String,
    val name: String,
    internal val remoteAddress: InetAddress,
    internal val helloUdpPort: Int?,
    private val socket: Socket,
    internal val out: OutputStream,
    /** Chaves da sessão (null com `aead=false`); quem as consome deve chamar [wipeKeys] em seguida. */
    @Volatile internal var keys: BspCrypto.SessionKeys?,
    val aead: Boolean,
    val videoSsrc: Int,
    val audioSsrc: Int?,
) {
    @Volatile var ready = false
        internal set

    @Volatile var udpPort = 0
        internal set

    /** O receptor pediu STOP (pausa a mídia sem encerrar a sessão). */
    @Volatile var receiverPaused = false
        internal set

    @Volatile var rttMs = 0L
        internal set

    /** Quantos HEARTBEAT_ACK já foram casados (diagnóstico e testes). */
    @Volatile var heartbeatAcks = 0
        internal set

    @Volatile var closed = false
        private set

    internal val outbox = ConcurrentLinkedQueue<String>()
    internal val writeLock = Any()

    /** Endereço de destino da mídia (IP observado no TCP + porta do READY). */
    internal fun mediaTarget(): InetSocketAddress = InetSocketAddress(remoteAddress, udpPort)

    /** Enfileira uma mensagem para a thread da sessão enviar (nunca bloqueia o chamador). Fila cheia: descarta. */
    fun send(line: String) {
        if (closed) return
        if (outbox.size < MAX_OUTBOX) outbox.add(line)
    }

    /** Marca como encerrada sem fechar o socket (o fechamento gracioso vem depois, em [BspControlServer.handle]). */
    internal fun markClosed() {
        closed = true
    }

    fun close() {
        closed = true
        try {
            socket.close()
        } catch (_: IOException) {
            // já fechado
        }
    }

    fun wipeKeys() {
        keys?.wipe()
        keys = null
    }

    fun snapshot() = BspSessionSnapshot(id, name, ready, receiverPaused, rttMs)

    private companion object {
        const val MAX_OUTBOX = 32
    }
}

/**
 * Servidor TCP do canal de controle do BSP v2 (4 e 10): JSON de uma linha, até 8 KiB por linha,
 * uma conexão por receptor. Papéis: autentica (HELLO com prova HMAC do `Kt` do pareamento do Link,
 * comparada em tempo constante; 4 s para provar, senão `ERROR AUTH`), admite até 2 sessões (a 3ª
 * recebe `BUSY`), responde WELCOME, espera READY, troca HEARTBEAT (RTT) e aceita START/STOP/BYE.
 * Limite de 20 mensagens/s por conexão. NADA é respondido antes de autenticar além de `ERROR`.
 *
 * Nunca registra token, `Kt`, chave nem IP: os logs trazem só o id aleatório da sessão.
 *
 * Uma thread por conexão (no máximo [BspLimits.MAX_CONNECTIONS]); cada sessão acorda a cada
 * [POLL_MS] para despachar a fila de saída e os temporizadores (1 Hz de heartbeat).
 */
class BspControlServer(
    private val authority: BspAuthority,
    private val provider: BspSessionProvider,
    private val listener: BspSessionListener,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    /** Prazo para autenticar (4 s pela spec); parametrizável só para os testes. */
    private val authTimeoutMs: Long = BspLimits.AUTH_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_PORT = 7070
        private const val POLL_MS = 250
        private const val ACCEPT_BACKLOG = 8
        private const val HEARTBEAT_RING = 8
        private val ZERO_KEY = ByteArray(BspCrypto.HASH_LEN) { 1 }
    }

    private val _sessions = MutableStateFlow<List<BspSessionSnapshot>>(emptyList())

    /** Sessões autenticadas (para a UI): só nomes e estado. */
    val sessions: StateFlow<List<BspSessionSnapshot>> = _sessions.asStateFlow()

    private val live = ArrayList<BspSession>()
    private val liveLock = Any()
    private val connections = AtomicInteger(0)
    private val threadNumber = AtomicInteger(0)

    @Volatile private var serverSocket: ServerSocket? = null

    @Volatile private var acceptThread: Thread? = null
    private var watchdog: ScheduledExecutorService? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "bsp-ctl-${threadNumber.incrementAndGet()}").apply { isDaemon = true } }

    /** Porta realmente ouvida (0 antes do [start]). */
    @Volatile var port = 0
        private set

    /**
     * Abre a porta [preferredPort]; se estiver ocupada cai para uma porta livre (a escolhida vai no
     * anúncio mDNS). Devolve a porta ouvida, ou lança [IOException] se nenhuma abrir.
     */
    @Synchronized
    fun start(preferredPort: Int = DEFAULT_PORT): Int {
        if (serverSocket != null) return port
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(preferredPort), ACCEPT_BACKLOG)
            }
        } catch (e: BindException) {
            Log.w(TAG, "Porta $preferredPort ocupada; usando uma porta livre")
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0), ACCEPT_BACKLOG)
            }
        }
        serverSocket = server
        port = server.localPort
        watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bsp-ctl-watchdog").apply { isDaemon = true } }
        acceptThread = Thread({ acceptLoop(server) }, "bsp-ctl-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Servidor de controle BSP ouvindo na porta $port")
        return port
    }

    @Synchronized
    fun stop() {
        val server = serverSocket ?: return
        serverSocket = null
        try {
            server.close()
        } catch (_: IOException) {
            // ignora
        }
        val sessionsToClose = synchronized(liveLock) { live.toList() }
        for (s in sessionsToClose) {
            s.send(BspProtocol.bye())
            flushQuietly(s)
            s.close()
        }
        acceptThread?.join(1000)
        acceptThread = null
        pool.shutdown()
        watchdog?.shutdownNow()
        watchdog = null
        port = 0
        Log.i(TAG, "Servidor de controle BSP parado")
    }

    /** Encerra as sessões cujo cliente deixou de estar pareado (revogação no Link). */
    fun closeSessionsWhere(predicate: (clientId: String) -> Boolean) {
        val victims = synchronized(liveLock) { live.filter { predicate(it.clientId) } }
        for (s in victims) {
            s.send(BspProtocol.error(BspError.AUTH, "pareamento revogado"))
            flushQuietly(s)
            s.close()
        }
    }

    /** Mensagem a todas as sessões prontas (META, SR, START/STOP da fonte). */
    fun broadcast(line: String, onlyReady: Boolean = true) {
        val targets = synchronized(liveLock) { live.toList() }
        for (s in targets) if (!onlyReady || s.ready) s.send(line)
    }

    fun sessionList(): List<BspSession> = synchronized(liveLock) { live.toList() }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (!server.isClosed) Log.w(TAG, "accept falhou: ${e.javaClass.simpleName}")
                break
            }
            if (connections.incrementAndGet() > BspLimits.MAX_CONNECTIONS) {
                connections.decrementAndGet()
                rejectQuietly(socket, BspError.BUSY, "servidor ocupado")
                continue
            }
            try {
                pool.execute {
                    try {
                        handle(socket)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Conexão de controle terminou com erro: ${t.javaClass.simpleName}")
                    } finally {
                        try {
                            socket.close()
                        } catch (_: IOException) {
                            // ignora
                        }
                        connections.decrementAndGet()
                    }
                }
            } catch (e: Exception) {
                connections.decrementAndGet()
                rejectQuietly(socket, BspError.INTERNAL, "indisponível")
            }
        }
    }

    private fun rejectQuietly(socket: Socket, code: String, message: String) {
        try {
            socket.getOutputStream().write((BspProtocol.error(code, message) + "\n").toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
            // melhor esforço
        } finally {
            try {
                socket.close()
            } catch (_: IOException) {
                // ignora
            }
        }
    }

    private fun flushQuietly(s: BspSession) {
        // despacha o que estiver na fila antes de fechar (BYE/ERROR); falha de escrita é irrelevante
        try {
            var line = s.outbox.poll()
            while (line != null) {
                writeLine(s, line)
                line = s.outbox.poll()
            }
        } catch (_: IOException) {
            // ignora
        }
    }

    private fun writeLine(s: BspSession, line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        synchronized(s.writeLock) {
            s.out.write(bytes)
            s.out.flush()
        }
    }

    // ---- Uma conexão -------------------------------------------------------------------

    private fun handle(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        val out = socket.getOutputStream()
        val authLock = Any()
        val reader = BoundedLineReader(socket.getInputStream())
        val limiter = MessageRateLimiter(BspLimits.MAX_MESSAGES_PER_SECOND, clock)

        // Prazo TOTAL de 4 s para autenticar, mesmo com envio byte a byte (o soTimeout só vale por leitura).
        val authenticated = java.util.concurrent.atomic.AtomicBoolean(false)
        val guard: ScheduledFuture<*>? = try {
            watchdog?.schedule(
                {
                    if (!authenticated.get()) {
                        try {
                            synchronized(authLock) { out.write((BspProtocol.error(BspError.AUTH, "tempo esgotado") + "\n").toByteArray(Charsets.UTF_8)) }
                        } catch (_: IOException) {
                            // ignora
                        }
                        try {
                            socket.close()
                        } catch (_: IOException) {
                            // ignora
                        }
                    }
                },
                authTimeoutMs,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: Exception) {
            null
        }

        var session: BspSession? = null
        try {
            socket.soTimeout = (authTimeoutMs + 1_000).toInt()
            session = authenticate(socket, out, reader, limiter, authLock, authenticated) ?: return
            guard?.cancel(false)
            runSession(session, socket, reader, limiter)
        } catch (e: BspLineTooLongException) {
            sendRaw(out, authLock, BspProtocol.error(BspError.FORMAT, "linha de controle grande demais"))
        } catch (e: SocketException) {
            // fechada pelo watchdog, pelo par ou pelo stop()
        } catch (e: IOException) {
            // queda de rede
        } finally {
            guard?.cancel(false)
            // 1) tira a sessão da lista e avisa a UI/mídia já; 2) só depois dá um instante para o par ler o último ERROR
            session?.let { endSession(it) }
            lingerClose(socket)
        }
    }

    /**
     * Fecha dando tempo de o par ler o último `ERROR`: fechar com bytes ainda não lidos no buffer de
     * recepção manda RST e o par pode perder a mensagem. Meia-fecha a escrita e descarta a entrada por
     * até ~300 ms antes de fechar de vez.
     */
    private fun lingerClose(socket: Socket) {
        try {
            if (socket.isClosed) return
            socket.shutdownOutput()
            socket.soTimeout = 100
            val sink = ByteArray(256)
            var rounds = 0
            while (rounds++ < 3 && socket.getInputStream().read(sink) >= 0) {
                // descarta
            }
        } catch (_: IOException) {
            // fechado ou sem dados: segue para o close
        }
    }

    private fun sendRaw(out: OutputStream, lock: Any, line: String) {
        try {
            synchronized(lock) {
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (_: IOException) {
            // melhor esforço
        }
    }

    /** Lê o HELLO, confere a prova e devolve a sessão (ou null depois de responder `ERROR`). */
    private fun authenticate(
        socket: Socket,
        out: OutputStream,
        reader: BoundedLineReader,
        limiter: MessageRateLimiter,
        authLock: Any,
        authenticated: java.util.concurrent.atomic.AtomicBoolean,
    ): BspSession? {
        fun fail(code: String, message: String, versions: List<Int>? = null): BspSession? {
            sendRaw(out, authLock, BspProtocol.error(code, message, versions))
            return null
        }

        val line = try {
            reader.readLine()
        } catch (_: SocketTimeoutException) {
            return fail(BspError.AUTH, "tempo esgotado")
        } ?: return null
        if (!limiter.tryAcquire()) return fail(BspError.RATE, "mensagens demais")
        val msg = BspProtocol.parseLine(line) ?: return fail(BspError.AUTH, "autenticação inválida")
        // Nada é respondido antes de autenticar além de ERROR: qualquer outro tipo é recusado.
        if (BspProtocol.typeOf(msg) != "HELLO") return fail(BspError.AUTH, "autenticação inválida")
        val hello = when (val parsed = BspProtocol.parseHello(msg)) {
            is HelloParse.Ok -> parsed.hello
            is HelloParse.Rejected -> return fail(parsed.code, parsed.message, parsed.supportedVersions)
        }

        // Prova em tempo constante; mesmo sem pareamento calcula o HMAC com chave de enchimento (sem atalho por timing).
        val kt = authority.clientKey(hello.clientId)
        val expected = BspCrypto.helloProof(kt ?: ZERO_KEY, hello.nonceR)
        val proofOk = BspCrypto.constantTimeEquals(expected, hello.proof)
        if (kt == null || !proofOk) {
            kt?.fill(0)
            return fail(BspError.AUTH, "autenticação inválida")
        }

        authenticated.set(true) // a partir daqui o prazo de 4 s não se aplica mais
        try {
            return admit(socket, out, authLock, hello, kt)
        } finally {
            kt.fill(0)
        }
    }

    /** Depois de provar: admissão (vagas/substituição), escolha de codecs e WELCOME. */
    private fun admit(socket: Socket, out: OutputStream, authLock: Any, hello: BspHello, kt: ByteArray): BspSession? {
        fun fail(code: String, message: String): BspSession? {
            sendRaw(out, authLock, BspProtocol.error(code, message))
            return null
        }

        if (hello.videoCodecs.isNotEmpty() && "h264" !in hello.videoCodecs) return fail(BspError.CODEC, "sem codec de vídeo em comum (h264)")
        val params = provider.streamParams() ?: return fail(BspError.INTERNAL, "fonte indisponível")
        val wantAead = hello.aead != false
        if (!wantAead && !provider.allowPlainMedia) return fail(BspError.FORMAT, "mídia sem criptografia não permitida")
        val sendAudio = params.audio != null && (hello.audioCodecs == null || "aac" in hello.audioCodecs)

        val nonceS = ByteArray(BspProtocol.NONCE_LEN).also { random.nextBytes(it) }
        val sessionId = 1 + random.nextInt(Int.MAX_VALUE - 1)
        val keys = if (wantAead) BspCrypto.deriveSessionKeys(kt, hello.nonceR, nonceS, sessionId) else null
        val remote = socket.inetAddress ?: return fail(BspError.INTERNAL, "conexão fechada")
        val pairedName = authority.clientName(hello.clientId)?.takeIf { it.isNotBlank() } ?: hello.clientName.ifBlank { "Receptor" }
        val session = BspSession(
            id = sessionId,
            clientId = hello.clientId,
            name = pairedName,
            remoteAddress = remote,
            helloUdpPort = hello.udpPort,
            socket = socket,
            out = out,
            keys = keys,
            aead = wantAead,
            videoSsrc = params.video.ssrc,
            audioSsrc = if (sendAudio) params.audio.ssrc else null,
        )

        // Admissão atômica: vaga, ou substituição da sessão antiga do mesmo cliente.
        val replaced: BspSession?
        synchronized(liveLock) {
            val admission = BspSessionPolicy.admit(live.filter { !it.closed }.map { it.clientId }, hello.clientId)
            if (admission == BspSessionPolicy.Admission.BUSY) {
                keys?.wipe()
                return fail(BspError.BUSY, "limite de ${BspLimits.MAX_SESSIONS} receptores")
            }
            replaced = if (admission == BspSessionPolicy.Admission.REPLACE_SAME_CLIENT) {
                live.firstOrNull { it.clientId == hello.clientId && !it.closed }
            } else {
                null
            }
            live.add(session)
        }
        replaced?.let {
            it.send(BspProtocol.bye())
            flushQuietly(it)
            it.close()
        }
        publish()

        val welcome = BspProtocol.welcome(
            sessionId = sessionId,
            deviceName = provider.deviceName,
            nonceS = nonceS,
            video = params.video,
            audio = if (sendAudio) params.audio else null,
            net = BspNetParams(params.net.mtu, params.net.latencyMs, params.net.fecK, params.net.nack, params.net.feedbackPort, wantAead),
            keyframeIntervalMs = params.keyframeIntervalMs,
            sourceProof = BspCrypto.welcomeProof(kt, hello.nonceR, nonceS),
        )
        sendRaw(out, authLock, welcome)
        Log.i(TAG, "Sessão $sessionId autenticada (aead=$wantAead)")
        return session
    }

    private fun runSession(session: BspSession, socket: Socket, reader: BoundedLineReader, limiter: MessageRateLimiter) {
        socket.soTimeout = POLL_MS
        var lastHeard = clock()
        var nextHeartbeat = lastHeard + BspLimits.HEARTBEAT_INTERVAL_MS
        val heartbeatSent = LongArray(HEARTBEAT_RING) { -1L }
        val heartbeatIds = LongArray(HEARTBEAT_RING) { -1L }
        var heartbeatSeq = 0L

        while (!session.closed) {
            var line = session.outbox.poll()
            while (line != null) {
                writeLine(session, line)
                line = session.outbox.poll()
            }

            val incoming = try {
                reader.readLine() ?: break
            } catch (_: SocketTimeoutException) {
                null
            }
            val now = clock()
            if (incoming != null) {
                lastHeard = now
                if (!limiter.tryAcquire()) {
                    writeLine(session, BspProtocol.error(BspError.RATE, "mensagens demais"))
                    break
                }
                val msg = BspProtocol.parseLine(incoming)
                if (msg != null && !handleMessage(session, msg, heartbeatSent, heartbeatIds)) break
            }
            if (now >= nextHeartbeat) {
                heartbeatSeq++
                val slot = (heartbeatSeq % HEARTBEAT_RING).toInt()
                heartbeatIds[slot] = heartbeatSeq
                heartbeatSent[slot] = System.nanoTime()
                writeLine(session, BspProtocol.heartbeat(heartbeatSeq, now))
                nextHeartbeat = now + BspLimits.HEARTBEAT_INTERVAL_MS
            }
            if (BspSessionPolicy.heartbeatExpired(lastHeard, now)) {
                Log.i(TAG, "Sessão ${session.id}: sem resposta do receptor; encerrando")
                break
            }
        }
        // despacha o que ficou na fila (BYE/ERROR enfileirado por quem fechou) e sai
        flushQuietly(session)
    }

    /** @return false para encerrar a sessão. */
    private fun handleMessage(session: BspSession, msg: JSONObject, sent: LongArray, ids: LongArray): Boolean {
        when (BspProtocol.typeOf(msg)) {
            "READY" -> {
                if (!session.ready) {
                    val port = BspProtocol.readyPort(msg) ?: session.helloUdpPort
                    if (port == null) {
                        writeLine(session, BspProtocol.error(BspError.FORMAT, "READY sem udpPort válido"))
                        return false
                    }
                    session.udpPort = port
                    session.ready = true
                    publish()
                    listener.onSessionReady(session)
                    Log.i(TAG, "Sessão ${session.id}: READY")
                }
            }

            "START" -> setPaused(session, false)

            "STOP" -> setPaused(session, true)

            "HEARTBEAT" -> writeLine(session, BspProtocol.heartbeatAck(msg.optLong("id", 0L), msg.optLong("ts", 0L)))

            "HEARTBEAT_ACK" -> {
                val id = msg.optLong("id", -1L)
                if (id > 0) {
                    val slot = (id % HEARTBEAT_RING).toInt()
                    if (ids[slot] == id) {
                        session.rttMs = ((System.nanoTime() - sent[slot]) / 1_000_000L).coerceAtLeast(0L)
                        session.heartbeatAcks++
                        ids[slot] = -1L
                        publish()
                    }
                }
            }

            "BYE" -> return false

            // TALLY e tipos futuros: ignorados (4: campos e tipos desconhecidos são ignorados)
            else -> Unit
        }
        return true
    }

    private fun setPaused(session: BspSession, paused: Boolean) {
        if (session.receiverPaused == paused) return
        session.receiverPaused = paused
        publish()
        listener.onSessionChanged(session)
    }

    private fun endSession(session: BspSession) {
        val wasLive: Boolean
        synchronized(liveLock) { wasLive = live.remove(session) }
        session.markClosed()
        session.wipeKeys()
        if (wasLive) {
            publish()
            listener.onSessionClosed(session)
            Log.i(TAG, "Sessão ${session.id} encerrada")
        }
    }

    private fun publish() {
        val snapshot = synchronized(liveLock) { live.map { it.snapshot() } }
        _sessions.value = snapshot
    }
}
