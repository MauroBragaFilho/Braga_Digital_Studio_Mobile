package com.bragastudio.mobile.network.auth

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class PairState { PENDING, APPROVED, DENIED, EXPIRED }

/** Validade de um pedido de pareamento. */
internal const val PAIR_REQUEST_TTL_MS = 90_000L

/**
 * Pedido de pareamento vindo de um cliente do Link (plugin/dock do OBS, navegador).
 * [code] é um código de 4 dígitos mostrado nos DOIS lados (celular e OBS) para o
 * operador conferir que está aprovando o pedido certo.
 */
data class PairRequest(
    val id: String,
    val clientId: String,
    val clientName: String,
    val remoteAddress: String,
    val code: String,
    val createdAtMs: Long,
    val state: PairState = PairState.PENDING,
    /** Token em claro, mantido só na memória até o cliente buscá-lo uma vez. */
    val token: String? = null,
    /** Instante em que o pedido deixa de valer (usado pelo timeout da notificação). */
    val expiresAtMs: Long = createdAtMs + PAIR_REQUEST_TTL_MS,
)

data class PairedClient(val clientId: String, val name: String, val pairedAtMs: Long)

/**
 * Armazenamento simples (um texto) da lista de clientes pareados. Abstraído para que a
 * lógica de autenticação seja testável em JVM, sem Robolectric.
 */
internal interface LinkAuthStore {
    fun read(): String?
    fun write(value: String)
}

internal class PrefsLinkAuthStore(context: Context) : LinkAuthStore {
    private val prefs = context.getSharedPreferences("bdsm_link_auth", Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString("clients", null)
    override fun write(value: String) {
        prefs.edit().putString("clients", value).apply()
    }
}

/**
 * Autenticação do BDSM Link por pareamento com consentimento duplo:
 *  1. o cliente (OBS) pergunta ao seu operador e só então chama `POST /api/pair/request`;
 *  2. o celular mostra um diálogo/notificação com o mesmo código e o operador aceita ou recusa;
 *  3. aprovado, o cliente busca o token (uma única vez) em `GET /api/pair/status/{id}`.
 *
 * O servidor guarda apenas o SHA-256 do token. Requisições às rotas protegidas
 * precisam de `Authorization: Bearer <token>` ou `?token=<token>` (WebSocket/navegador).
 *
 * A lista de clientes fica em memória (e persistida em [LinkAuthStore]): [isAuthorized]
 * é barato o bastante para ser chamado a cada ciclo de envio do WebSocket, o que permite
 * derrubar a conexão de um cliente revogado em poucos instantes.
 */
@Singleton
class LinkAuthManager internal constructor(
    private val store: LinkAuthStore,
    private val clock: () -> Long,
    private val random: SecureRandom,
) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(PrefsLinkAuthStore(context), System::currentTimeMillis, SecureRandom())

    companion object {
        private const val TAG = "LinkAuth"
        private const val MAX_PENDING = 3

        /** Após recusa/expiração, o mesmo endereço precisa esperar antes de pedir de novo. */
        private const val RETRY_COOLDOWN_MS = 5_000L
        const val MAX_NAME = 40
    }

    private val lock = Any()

    /** Pedidos ainda pendentes (a UI do celular observa esta lista). */
    private val _pending = MutableStateFlow<List<PairRequest>>(emptyList())
    val pending: StateFlow<List<PairRequest>> = _pending.asStateFlow()

    private val requests = LinkedHashMap<String, PairRequest>()

    /** Último instante em que um pedido de cada endereço foi recusado/expirou. */
    private val lastRejectedAt = HashMap<String, Long>()

    /** hash SHA-256 do token -> cliente. */
    private val clients = LinkedHashMap<String, PairedClient>().also { it.putAll(loadClients()) }

    private val _paired = MutableStateFlow(clients.values.toList())
    val paired: StateFlow<List<PairedClient>> = _paired.asStateFlow()

    // ---- Pedido (chamado pelo servidor HTTP) -------------------------------------------

    /** Retorna o pedido criado, ou null se houver pedidos demais (anti-spam). */
    fun createRequest(clientId: String, clientName: String, remoteAddress: String): PairRequest? = synchronized(lock) {
        expireOld()
        val now = clock()
        val active = requests.values.filter { it.state == PairState.PENDING }
        if (active.size >= MAX_PENDING) return null
        // No máximo um pedido pendente por endereço.
        if (active.any { it.remoteAddress == remoteAddress }) return null
        // Espera mínima após uma recusa, para um cliente não "metralhar" o diálogo.
        val rejected = lastRejectedAt[remoteAddress]
        if (rejected != null && now - rejected < RETRY_COOLDOWN_MS) return null

        val req = PairRequest(
            id = randomHex(12),
            clientId = sanitizeClientId(clientId).ifBlank { randomHex(8) },
            clientName = sanitizeName(clientName),
            remoteAddress = remoteAddress,
            code = "%04d".format(random.nextInt(10_000)),
            createdAtMs = now,
        )
        requests[req.id] = req
        publish()
        Log.i(TAG, "Pedido de pareamento ${req.id} de ${req.clientName} (${req.remoteAddress})")
        req
    }

    /** Estado do pedido; se aprovado, entrega o token UMA vez e o apaga da memória. */
    fun pollRequest(id: String): PairRequest? = synchronized(lock) {
        expireOld()
        val req = requests[id] ?: return null
        if (req.state == PairState.APPROVED && req.token != null) {
            requests[id] = req.copy(token = null)
        }
        req
    }

    // ---- Decisão do operador (chamado pela UI/notificação do celular) ---------------------

    fun approve(id: String) = synchronized(lock) {
        expireOld()
        val req = requests[id] ?: return@synchronized
        if (req.state != PairState.PENDING) return@synchronized
        val token = randomHex(32)
        // Um clientId tem um único token: remove o anterior.
        clients.entries.removeAll { it.value.clientId == req.clientId }
        clients[sha256(token)] = PairedClient(req.clientId, req.clientName, clock())
        persist()
        _paired.value = clients.values.toList()
        requests[id] = req.copy(state = PairState.APPROVED, token = token)
        publish()
    }

    fun deny(id: String) = synchronized(lock) {
        val req = requests[id] ?: return@synchronized
        if (req.state != PairState.PENDING) return@synchronized
        requests[id] = req.copy(state = PairState.DENIED)
        lastRejectedAt[req.remoteAddress] = clock()
        publish()
    }

    // ---- Autorização --------------------------------------------------------------------

    fun isAuthorized(token: String?): Boolean {
        if (token.isNullOrBlank() || token.length > 128) return false
        val hash = sha256(token).toByteArray()
        return synchronized(lock) {
            var ok = false
            // Percorre todos (sem sair no primeiro acerto) e compara em tempo constante.
            for (h in clients.keys) {
                if (MessageDigest.isEqual(h.toByteArray(), hash)) ok = true
            }
            ok
        }
    }

    fun revoke(clientId: String) = synchronized(lock) {
        if (clients.entries.removeAll { it.value.clientId == clientId }) {
            persist()
            _paired.value = clients.values.toList()
        }
        Unit
    }

    fun revokeAll() = synchronized(lock) {
        clients.clear()
        persist()
        _paired.value = emptyList()
    }

    /** Chamado periodicamente pela UI para sumir com diálogos de pedidos expirados. */
    fun refresh() = synchronized(lock) { expireOld() }

    // ---- Internos -----------------------------------------------------------------------

    private fun expireOld() {
        val now = clock()
        var changed = false
        for ((id, r) in requests.toMap()) {
            if (r.state == PairState.PENDING && now - r.createdAtMs > PAIR_REQUEST_TTL_MS) {
                requests[id] = r.copy(state = PairState.EXPIRED)
                lastRejectedAt[r.remoteAddress] = now
                changed = true
            }
            // Descarta pedidos resolvidos antigos.
            if (r.state != PairState.PENDING && now - r.createdAtMs > PAIR_REQUEST_TTL_MS * 4) {
                requests.remove(id)
            }
        }
        lastRejectedAt.entries.removeAll { now - it.value > RETRY_COOLDOWN_MS * 12 }
        if (changed) publish()
    }

    private fun publish() {
        _pending.update { requests.values.filter { it.state == PairState.PENDING } }
    }

    private fun persist() {
        val obj = buildJsonObject {
            for ((hash, c) in clients) {
                put(
                    hash,
                    buildJsonObject {
                        put("clientId", c.clientId)
                        put("name", c.name)
                        put("at", c.pairedAtMs)
                    },
                )
            }
        }
        store.write(obj.toString())
    }

    private fun loadClients(): Map<String, PairedClient> = try {
        val raw = store.read()
        if (raw.isNullOrBlank()) {
            emptyMap()
        } else {
            val obj: JsonObject = Json.parseToJsonElement(raw).jsonObject
            obj.entries.associate { (hash, v) ->
                val o = v.jsonObject
                hash to PairedClient(
                    clientId = o["clientId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    pairedAtMs = (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L,
                )
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Lista de clientes pareados ilegível; começando vazia")
        emptyMap()
    }

    private fun sanitizeName(n: String): String = n.filter { !it.isISOControl() }.trim().take(MAX_NAME).ifBlank { "Cliente desconhecido" }

    private fun sanitizeClientId(id: String): String = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == ':' }.take(64)

    private fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
