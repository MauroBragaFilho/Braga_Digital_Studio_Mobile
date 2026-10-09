package com.bragastudio.mobile.coremedia.bsp

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Teste de integração do servidor de controle com sockets reais em loopback (sem Android). */
class BspControlServerTest {

    private class FakeAuthority : BspAuthority {
        val keys = HashMap<String, ByteArray>()
        val names = HashMap<String, String>()
        fun pair(clientId: String, token: String, name: String = "PC do Estúdio") {
            keys[clientId] = BspCrypto.sha256(token.toByteArray())
            names[clientId] = name
        }

        override fun clientKey(clientId: String): ByteArray? = keys[clientId]?.copyOf()
        override fun clientName(clientId: String): String? = names[clientId]
    }

    private class FakeProvider : BspSessionProvider {
        override val deviceName = "Scorpio"
        override var allowPlainMedia = false
        var params: BspStreamParams? = BspStreamParams(
            video = BspVideoParams(1920, 1080, 30, 10_000, ssrc = 1111, payloadType = 96, sps = byteArrayOf(0x67, 1), pps = byteArrayOf(0x68, 2), orientation = 0),
            audio = BspAudioParams(48_000, 2, ssrc = 2222, payloadType = 97, config = byteArrayOf(0x11, 0x90.toByte())),
            net = BspNetParams(1200, 80, 0, false, 7071, true),
            keyframeIntervalMs = 2000,
        )

        override fun streamParams() = params
    }

    private class RecordingListener : BspSessionListener {
        val ready = CopyOnWriteArrayList<BspSession>()
        val changed = CopyOnWriteArrayList<BspSession>()
        val closed = CopyOnWriteArrayList<BspSession>()
        override fun onSessionReady(session: BspSession) {
            ready += session
        }

        override fun onSessionChanged(session: BspSession) {
            changed += session
        }

        override fun onSessionClosed(session: BspSession) {
            closed += session
        }
    }

    private class Client(port: Int) {
        val socket = Socket()
        val reader: BufferedReader

        init {
            socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
            socket.soTimeout = 8000
            reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        }

        fun send(line: String) {
            socket.getOutputStream().write((line + "\n").toByteArray())
            socket.getOutputStream().flush()
        }

        /** Próxima linha como JSON, ou null no fim do stream. Estoura o teste se nada chegar em 8 s. */
        fun read(): JSONObject? = reader.readLine()?.let { JSONObject(it) }

        /** Lê até achar [type] (ignora HEARTBEAT etc.). */
        fun readType(type: String): JSONObject {
            while (true) {
                val m = read() ?: error("conexão fechada antes de $type")
                if (m.getString("type") == type) return m
            }
        }

        /** true se o servidor fechou a conexão (fim do stream ou reset) dentro do prazo. */
        fun isClosedByServer(): Boolean {
            while (true) {
                try {
                    if (reader.readLine() == null) return true
                } catch (_: SocketTimeoutException) {
                    return false
                } catch (_: java.io.IOException) {
                    return true
                }
            }
        }

        fun close() = runCatching { socket.close() }
    }

    private lateinit var authority: FakeAuthority
    private lateinit var provider: FakeProvider
    private lateinit var listener: RecordingListener
    private lateinit var server: BspControlServer
    private val clients = ArrayList<Client>()
    private val b64 = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    @Before
    fun setUp() {
        authority = FakeAuthority().apply { pair("obs-1", "token-1") }
        provider = FakeProvider()
        listener = RecordingListener()
        // prazo folgado: a máquina de teste pode estar carregada (Gradle em paralelo); os testes de
        // prazo criam o próprio servidor com 600 ms
        server = BspControlServer(authority, provider, listener, authTimeoutMs = 10_000)
        server.start(0)
    }

    @After
    fun tearDown() {
        clients.forEach { it.close() }
        server.stop()
        extraServers.forEach { it.stop() }
    }

    private fun connect(): Client = Client(server.port).also { clients += it }

    private fun kt(token: String) = BspCrypto.sha256(token.toByteArray())

    private fun hello(
        clientId: String = "obs-1",
        token: String = "token-1",
        nonce: ByteArray = ByteArray(16) { (it + 1).toByte() },
        extra: String = "",
        caps: String = """"caps":{"video":["h264"],"audio":["aac"],"aead":true},""",
        proofKey: ByteArray? = null,
    ): String {
        val proof = BspCrypto.helloProof(proofKey ?: kt(token), nonce)
        return """{"type":"HELLO","protocolVersion":2,"clientId":"$clientId","clientName":"nome-do-hello","nonce":"${b64.encodeToString(nonce)}",$caps"udpPort":50123,""" +
            """"auth":{"scheme":"link-token","proof":"${b64.encodeToString(proof)}"}$extra}"""
    }

    private fun authenticate(clientId: String = "obs-1", token: String = "token-1"): Pair<Client, JSONObject> {
        val c = connect()
        c.send(hello(clientId, token))
        return c to c.readType("WELCOME")
    }

    // ---- Autenticação ----------------------------------------------------------------

    @Test
    fun validHelloGetsAWelcomeWithTheFinalParametersAndAVerifiableSourceProof() {
        val nonceR = ByteArray(16) { (it + 1).toByte() }
        val c = connect()
        c.send(hello(nonce = nonceR))
        val w = c.readType("WELCOME")
        assertEquals(2, w.getInt("protocolVersion"))
        assertEquals("Scorpio", w.getString("deviceName"))
        assertEquals(1920, w.getJSONObject("video").getInt("width"))
        assertEquals(1111L, w.getJSONObject("video").getLong("ssrc"))
        assertEquals(2222L, w.getJSONObject("audio").getLong("ssrc"))
        assertTrue(w.getJSONObject("net").getBoolean("aead"))
        assertEquals(2000, w.getInt("keyframeIntervalMs"))
        val nonceS = b64d.decode(w.getString("nonce"))
        assertEquals(16, nonceS.size)
        val sessionId = w.getLong("sessionId").toInt()

        // a prova da fonte confere com Kt (autenticação mútua)
        val expectedProof = BspCrypto.welcomeProof(kt("token-1"), nonceR, nonceS)
        assertArrayEquals(expectedProof, b64d.decode(w.getJSONObject("auth").getString("proof")))

        // e a chave de mídia da sessão é a derivada de Kt/nonces/sessionId
        val session = server.sessionList().single()
        val expectedKeys = BspCrypto.deriveSessionKeys(kt("token-1"), nonceR, nonceS, sessionId)
        assertArrayEquals(expectedKeys.key, session.keys!!.key)
        assertArrayEquals(expectedKeys.nonceSalt, session.keys!!.nonceSalt)
        assertEquals("o nome mostrado é o do pareamento, não o do HELLO", "PC do Estúdio", session.name)
        assertFalse(session.ready)
    }

    @Test
    fun readyStartsTheMediaForTheReceiverAndKeepsTheIpOutOfTheSnapshot() {
        val (c, _) = authenticate()
        c.send("""{"type":"READY","udpPort":50999}""")
        waitUntil { listener.ready.isNotEmpty() }
        val s = listener.ready.single()
        assertEquals(50999, s.udpPort)
        assertTrue(s.ready)
        assertEquals("127.0.0.1", s.mediaTarget().address.hostAddress)
        assertEquals(50999, s.mediaTarget().port)
        val snap = server.sessions.value.single()
        assertEquals("PC do Estúdio", snap.name)
        assertTrue(snap.ready)
        assertFalse(snap.toString().contains("127.0.0.1"))
    }

    @Test
    fun readyWithoutAPortFallsBackToTheHelloPort() {
        val (c, _) = authenticate()
        c.send("""{"type":"READY"}""")
        waitUntil { listener.ready.isNotEmpty() }
        assertEquals(50123, listener.ready.single().udpPort)
    }

    @Test
    fun wrongProofGetsErrorAuthAndTheConnectionIsClosed() {
        val c = connect()
        c.send(hello(proofKey = kt("token-errado")))
        val e = c.read()!!
        assertEquals("ERROR", e.getString("type"))
        assertEquals("AUTH", e.getString("code"))
        assertTrue(c.isClosedByServer())
        assertTrue(server.sessionList().isEmpty())
    }

    @Test
    fun unknownClientIsIndistinguishableFromAWrongProof() {
        val known = connect().also { it.send(hello(proofKey = kt("errado"))) }.read()!!
        val unknown = connect().also { it.send(hello(clientId = "desconhecido")) }.read()!!
        assertEquals(known.toString(), unknown.toString())
        assertEquals("AUTH", unknown.getString("code"))
    }

    @Test
    fun nothingButErrorIsAnsweredBeforeAuthentication() {
        val c = connect()
        c.send("""{"type":"HEARTBEAT","id":1,"ts":1}""")
        val first = c.read()!!
        assertEquals("ERROR", first.getString("type"))
        assertEquals("AUTH", first.getString("code"))
        assertTrue(c.isClosedByServer())
    }

    @Test
    fun malformedJsonBeforeAuthIsAnAuthError() {
        val c = connect()
        c.send("{isto nao e json")
        assertEquals("AUTH", c.read()!!.getString("code"))
        assertTrue(c.isClosedByServer())
    }

    /** Servidor extra com prazo curto (600 ms) para os testes de tempo. */
    private fun shortDeadlineServer(): BspControlServer = BspControlServer(authority, provider, listener, authTimeoutMs = 600).also {
        it.start(0)
        extraServers += it
    }

    private val extraServers = ArrayList<BspControlServer>()

    @Test
    fun silentConnectionGetsErrorAuthWithinTheDeadline() {
        val c = Client(shortDeadlineServer().port).also { clients += it }
        val started = System.nanoTime()
        val e = c.read()!!
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("AUTH", e.getString("code"))
        assertTrue("prazo de autenticação (600 ms no teste): ${elapsedMs}ms", elapsedMs in 400..2500)
        assertTrue(c.isClosedByServer())
    }

    @Test
    fun drippingBytesCannotHoldTheConnectionPastTheDeadline() {
        val c = Client(shortDeadlineServer().port).also { clients += it }
        val started = System.nanoTime()
        val t = Thread {
            try {
                repeat(40) {
                    c.socket.getOutputStream().write('{'.code)
                    c.socket.getOutputStream().flush()
                    Thread.sleep(100)
                }
            } catch (_: Exception) {
                // o servidor fechou: esperado
            }
        }
        t.start()
        val e = c.read()
        assertNotNull(e)
        assertEquals("AUTH", e!!.getString("code"))
        assertTrue("fechou por prazo total, não por leitura", (System.nanoTime() - started) / 1_000_000 < 2500)
        t.join(5000)
    }

    @Test
    fun otherProtocolVersionGetsErrorVersionWithTheSupportedList() {
        val c = connect()
        c.send("""{"type":"HELLO","protocolVersion":1,"clientId":"obs-1"}""")
        val e = c.read()!!
        assertEquals("VERSION", e.getString("code"))
        assertEquals(2, e.getJSONArray("supported").getInt(0))
        assertTrue(c.isClosedByServer())
    }

    @Test
    fun lineAbove8KiBIsRejectedAndClosed() {
        val c = connect()
        c.send("x".repeat(BspLimits.MAX_LINE_BYTES + 100))
        val e = c.read()!!
        assertEquals("ERROR", e.getString("type"))
        assertEquals("FORMAT", e.getString("code"))
        assertTrue(c.isClosedByServer())
    }

    @Test
    fun receiverWithoutH264GetsErrorCodec() {
        val c = connect()
        c.send(hello(caps = """"caps":{"video":["hevc"]},"""))
        assertEquals("CODEC", c.read()!!.getString("code"))
    }

    @Test
    fun unavailableSourceGetsErrorInternal() {
        provider.params = null
        val c = connect()
        c.send(hello())
        assertEquals("INTERNAL", c.read()!!.getString("code"))
    }

    // ---- Sessões simultâneas -------------------------------------------------------------

    @Test
    fun thirdReceiverGetsBusyAfterAuthenticating() {
        authority.pair("obs-2", "token-2")
        authority.pair("obs-3", "token-3")
        authenticate("obs-1", "token-1")
        authenticate("obs-2", "token-2")
        val third = connect()
        third.send(hello("obs-3", "token-3"))
        val e = third.read()!!
        assertEquals("BUSY", e.getString("code"))
        assertTrue(third.isClosedByServer())
        assertEquals(2, server.sessionList().size)
    }

    @Test
    fun anUnpairedClientCannotLearnWhetherTheServerIsBusy() {
        authority.pair("obs-2", "token-2")
        authenticate("obs-1", "token-1")
        authenticate("obs-2", "token-2")
        val stranger = connect()
        stranger.send(hello(clientId = "intruso", token = "x"))
        assertEquals("sem prova válida a resposta é AUTH, nunca BUSY", "AUTH", stranger.read()!!.getString("code"))
    }

    @Test
    fun sameClientReconnectingReplacesItsOldSession() {
        val (old, firstWelcome) = authenticate()
        val oldId = firstWelcome.getLong("sessionId").toInt()
        val (_, secondWelcome) = authenticate()
        assertTrue("o par antigo recebe BYE e é fechado", old.isClosedByServer())
        waitUntil { listener.closed.any { it.id == oldId } }
        assertEquals(1, server.sessionList().size)
        assertEquals(secondWelcome.getLong("sessionId").toInt(), server.sessionList().single().id)
    }

    @Test
    fun byeClosesTheSessionAndNotifiesTheListener() {
        val (c, w) = authenticate()
        c.send("""{"type":"BYE"}""")
        assertTrue(c.isClosedByServer())
        waitUntil { listener.closed.isNotEmpty() }
        assertEquals(w.getLong("sessionId").toInt(), listener.closed.single().id)
        assertTrue(server.sessionList().isEmpty())
        assertTrue(server.sessions.value.isEmpty())
    }

    @Test
    fun disconnectingTheSocketEndsTheSession() {
        val (c, _) = authenticate()
        c.close()
        waitUntil { listener.closed.isNotEmpty() }
        assertTrue(server.sessionList().isEmpty())
    }

    @Test
    fun receiverStopAndStartPauseAndResumeTheMedia() {
        val (c, _) = authenticate()
        c.send("""{"type":"READY","udpPort":50999}""")
        waitUntil { listener.ready.isNotEmpty() }
        c.send("""{"type":"STOP"}""")
        waitUntil { listener.changed.size >= 1 }
        assertTrue(server.sessionList().single().receiverPaused)
        assertTrue(server.sessions.value.single().paused)
        c.send("""{"type":"START"}""")
        waitUntil { listener.changed.size >= 2 }
        assertFalse(server.sessionList().single().receiverPaused)
    }

    // ---- Heartbeat, limites e envios da fonte ---------------------------------------------

    @Test
    fun serverSendsHeartbeatsAndMeasuresRttFromTheAck() {
        val (c, _) = authenticate()
        val hb = c.readType("HEARTBEAT")
        c.send(BspProtocol.heartbeatAck(hb.getLong("id"), hb.getLong("ts")))
        waitUntil { server.sessionList().single().heartbeatAcks == 1 }
        val session = server.sessionList().single()
        assertTrue("RTT medido em loopback", session.rttMs in 0..500)
        // ACK com id desconhecido ou repetido não conta
        c.send(BspProtocol.heartbeatAck(hb.getLong("id"), 0))
        c.send(BspProtocol.heartbeatAck(999_999, 0))
        c.send(BspProtocol.heartbeat(1, 1)) // sincroniza: quando o ACK deste chegar, os anteriores já foram processados
        c.readType("HEARTBEAT_ACK")
        assertEquals(1, session.heartbeatAcks)
    }

    @Test
    fun receiverHeartbeatIsAnsweredWithAnEchoAck() {
        val (c, _) = authenticate()
        c.send(BspProtocol.heartbeat(77, 123456))
        val ack = c.readType("HEARTBEAT_ACK")
        assertEquals(77L, ack.getLong("id"))
        assertEquals(123456L, ack.getLong("ts"))
    }

    @Test
    fun floodingFasterThanTwentyMessagesPerSecondGetsErrorRateAndIsClosed() {
        val (c, _) = authenticate()
        repeat(60) { c.send("""{"type":"TALLY","state":"PROGRAM"}""") }
        var rate: JSONObject? = null
        while (rate == null) {
            val m = c.read() ?: break
            if (m.optString("code") == "RATE") rate = m
        }
        assertNotNull("deveria receber ERROR RATE", rate)
        assertTrue(c.isClosedByServer())
    }

    @Test
    fun malformedAndUnknownMessagesAfterAuthAreIgnored() {
        val (c, _) = authenticate()
        c.send("isto nao e json")
        c.send("""{"type":"FUTURO","x":1}""")
        c.send("""{"sem_tipo":true}""")
        c.send("""{"type":"READY","udpPort":50999}""")
        waitUntil { listener.ready.isNotEmpty() }
        assertEquals("a sessão sobrevive ao lixo", 1, server.sessionList().size)
    }

    @Test
    fun broadcastReachesOnlyReadySessions() {
        authority.pair("obs-2", "token-2")
        val (a, _) = authenticate("obs-1", "token-1")
        val (b, _) = authenticate("obs-2", "token-2")
        a.send("""{"type":"READY","udpPort":50999}""")
        waitUntil { listener.ready.isNotEmpty() }
        server.broadcast(BspProtocol.meta(BspMeta(orientation = 90, lens = "1x", battery = 80)))
        assertEquals(90, a.readType("META").getInt("orientation"))
        // b não está pronto: não recebe o META (só heartbeats)
        b.socket.soTimeout = 800
        var gotMeta = false
        try {
            while (true) if ((b.read() ?: break).getString("type") == "META") gotMeta = true
        } catch (_: SocketTimeoutException) {
            // esperado
        }
        assertFalse(gotMeta)
    }

    @Test
    fun revokedClientsAreDroppedWithErrorAuth() {
        authority.pair("obs-2", "token-2")
        val (a, _) = authenticate("obs-1", "token-1")
        val (b, _) = authenticate("obs-2", "token-2")
        server.closeSessionsWhere { it == "obs-1" }
        assertEquals("AUTH", a.readType("ERROR").getString("code"))
        assertTrue(a.isClosedByServer())
        waitUntil { server.sessionList().size == 1 }
        assertEquals("obs-2", server.sessionList().single().clientId)
        assertNotNull(b)
    }

    // ---- Mídia sem criptografia -------------------------------------------------------------

    @Test
    fun plainMediaIsRefusedByDefaultAndAcceptedOnlyWhenAllowed() {
        val plainCaps = """"caps":{"video":["h264"],"aead":false},"""
        val refused = connect()
        refused.send(hello(caps = plainCaps))
        val e = refused.read()!!
        assertEquals("FORMAT", e.getString("code"))
        assertTrue(server.sessionList().isEmpty())

        provider.allowPlainMedia = true
        val c = connect()
        c.send(hello(caps = plainCaps))
        val w = c.readType("WELCOME")
        assertFalse(w.getJSONObject("net").getBoolean("aead"))
        assertNull("sem AEAD não há chaves", server.sessionList().single().keys)
    }

    @Test
    fun receiverWithoutAacGetsNoAudioBlock() {
        val c = connect()
        c.send(hello(caps = """"caps":{"video":["h264"],"audio":["opus"],"aead":true},"""))
        val w = c.readType("WELCOME")
        assertFalse(w.has("audio"))
        assertNull(server.sessionList().single().audioSsrc)
    }

    // ---- Parada ---------------------------------------------------------------------------

    @Test
    fun stoppingTheServerSaysByeAndClosesEverySession() {
        val (c, _) = authenticate()
        server.stop()
        val bye = c.readType("BYE")
        assertEquals("BYE", bye.getString("type"))
        assertTrue(c.isClosedByServer())
        waitUntil { listener.closed.isNotEmpty() }
    }

    @Test
    fun portFallsBackToAFreeOneWhenBusy() {
        val taken = java.net.ServerSocket(0)
        try {
            val second = BspControlServer(authority, provider, listener)
            val port = second.start(taken.localPort)
            assertTrue(port != taken.localPort && port > 0)
            second.stop()
        } finally {
            taken.close()
        }
    }

    private fun waitUntil(timeoutMs: Long = 8000, condition: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > end) error("condição não ocorreu em ${timeoutMs}ms")
            Thread.sleep(10)
        }
    }
}
