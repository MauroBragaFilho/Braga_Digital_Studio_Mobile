package com.bragastudio.mobile.network.auth

import com.bragastudio.mobile.network.mockAndroidLog
import com.bragastudio.mobile.network.unmockAndroidLog
import java.security.SecureRandom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Armazenamento em memória no lugar das SharedPreferences. */
internal class FakeAuthStore(var value: String? = null) : LinkAuthStore {
    override fun read(): String? = value
    override fun write(value: String) {
        this.value = value
    }
}

class LinkAuthManagerTest {

    private var now = 1_000_000L
    private lateinit var store: FakeAuthStore
    private lateinit var auth: LinkAuthManager

    @Before
    fun setUp() {
        mockAndroidLog()
        store = FakeAuthStore()
        auth = newManager()
    }

    @After
    fun tearDown() {
        unmockAndroidLog()
    }

    private fun newManager() = LinkAuthManager(store, { now }, SecureRandom())

    /** Cria, aprova e devolve o token entregue. */
    private fun pair(clientId: String = "obs-1", address: String = "192.168.0.10"): String {
        val req = auth.createRequest(clientId, "OBS", address)!!
        auth.approve(req.id)
        return auth.pollRequest(req.id)!!.token!!
    }

    @Test
    fun `token valido apos aprovacao e invalido antes`() {
        val req = auth.createRequest("obs-1", "OBS", "192.168.0.10")!!
        assertEquals(PairState.PENDING, auth.pollRequest(req.id)!!.state)
        assertFalse(auth.isAuthorized("qualquer"))

        auth.approve(req.id)
        val token = auth.pollRequest(req.id)!!.token
        assertNotNull(token)
        assertTrue(auth.isAuthorized(token))
        assertFalse(auth.isAuthorized(token + "x"))
        assertFalse(auth.isAuthorized(null))
        assertFalse(auth.isAuthorized(""))
        assertFalse(auth.isAuthorized("a".repeat(129)))
    }

    @Test
    fun `token e entregue uma unica vez`() {
        val req = auth.createRequest("obs-1", "OBS", "192.168.0.10")!!
        auth.approve(req.id)
        val first = auth.pollRequest(req.id)!!
        assertEquals(PairState.APPROVED, first.state)
        assertNotNull(first.token)
        val second = auth.pollRequest(req.id)!!
        assertEquals(PairState.APPROVED, second.state)
        assertNull(second.token)
    }

    @Test
    fun `pedido nao aprovado nunca entrega token`() {
        val req = auth.createRequest("obs-1", "OBS", "192.168.0.10")!!
        auth.deny(req.id)
        val polled = auth.pollRequest(req.id)!!
        assertEquals(PairState.DENIED, polled.state)
        assertNull(polled.token)
        // approve depois de recusado nao tem efeito
        auth.approve(req.id)
        assertEquals(PairState.DENIED, auth.pollRequest(req.id)!!.state)
        assertTrue(auth.paired.value.isEmpty())
    }

    @Test
    fun `pedido expira apos 90 segundos e nao pode ser aprovado`() {
        val req = auth.createRequest("obs-1", "OBS", "192.168.0.10")!!
        assertEquals(1, auth.pending.value.size)

        now += 91_000
        auth.refresh()
        assertTrue(auth.pending.value.isEmpty())
        assertEquals(PairState.EXPIRED, auth.pollRequest(req.id)!!.state)

        auth.approve(req.id)
        assertTrue(auth.paired.value.isEmpty())
    }

    @Test
    fun `anti-spam um pedido pendente por endereco`() {
        assertNotNull(auth.createRequest("a", "A", "192.168.0.10"))
        assertNull(auth.createRequest("b", "B", "192.168.0.10"))
        assertNotNull(auth.createRequest("c", "C", "192.168.0.11"))
    }

    @Test
    fun `anti-spam limite de tres pedidos pendentes`() {
        assertNotNull(auth.createRequest("a", "A", "10.0.0.1"))
        assertNotNull(auth.createRequest("b", "B", "10.0.0.2"))
        assertNotNull(auth.createRequest("c", "C", "10.0.0.3"))
        assertNull(auth.createRequest("d", "D", "10.0.0.4"))
    }

    @Test
    fun `anti-spam espera apos recusa e libera depois`() {
        val req = auth.createRequest("a", "A", "10.0.0.1")!!
        auth.deny(req.id)
        assertNull(auth.createRequest("a", "A", "10.0.0.1"))
        now += 6_000
        assertNotNull(auth.createRequest("a", "A", "10.0.0.1"))
    }

    @Test
    fun `revogar invalida o token e atualiza a lista`() {
        val token = pair("obs-1")
        val other = pair("obs-2", "192.168.0.11")
        assertEquals(2, auth.paired.value.size)

        auth.revoke("obs-1")
        assertFalse(auth.isAuthorized(token))
        assertTrue(auth.isAuthorized(other))
        assertEquals(listOf("obs-2"), auth.paired.value.map { it.clientId })
    }

    @Test
    fun `revogar todos invalida todos os tokens`() {
        val a = pair("obs-1")
        val b = pair("obs-2", "192.168.0.11")
        auth.revokeAll()
        assertFalse(auth.isAuthorized(a))
        assertFalse(auth.isAuthorized(b))
        assertTrue(auth.paired.value.isEmpty())
    }

    @Test
    fun `repareamento do mesmo cliente substitui o token antigo`() {
        val old = pair("obs-1")
        now += 10_000
        val new = pair("obs-1")
        assertFalse(auth.isAuthorized(old))
        assertTrue(auth.isAuthorized(new))
        assertEquals(1, auth.paired.value.size)
    }

    @Test
    fun `pareamentos sobrevivem a recriacao e guardam so o hash`() {
        val token = pair("obs-1")
        assertFalse("o token em claro nao pode ser persistido", store.value!!.contains(token))

        val reloaded = newManager()
        assertTrue(reloaded.isAuthorized(token))
        assertEquals("obs-1", reloaded.paired.value.single().clientId)

        reloaded.revoke("obs-1")
        assertFalse(newManager().isAuthorized(token))
    }

    @Test
    fun `armazenamento corrompido comeca vazio`() {
        store.value = "{ isto nao e json"
        val fresh = newManager()
        assertTrue(fresh.paired.value.isEmpty())
    }

    @Test
    fun `nome e clientId sao saneados`() {
        val req = auth.createRequest("id com espaco/\n<x>", "  Nome\u0000Estranho  ", "10.0.0.1")!!
        assertFalse(req.clientId.any { it.isWhitespace() || it == '/' || it == '<' })
        assertFalse(req.clientName.any { it.isISOControl() })
        val long = auth.createRequest("b", "x".repeat(200), "10.0.0.2")!!
        assertEquals(LinkAuthManager.MAX_NAME, long.clientName.length)
    }
}
