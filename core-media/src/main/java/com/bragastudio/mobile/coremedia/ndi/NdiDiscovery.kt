package com.bragastudio.mobile.coremedia.ndi

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.bragastudio.mobile.coremedia.domain.NdiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class NdiDiscoveryPhase { IDLE, SEARCHING, READY, ERROR }

/**
 * Estado público da descoberta. [proximity] é por NOME da fonte (nunca por IP); a chave ausente
 * significa "ainda medindo" e [ProximityBand.UNKNOWN] presente significa "sem resposta".
 */
data class NdiDiscoveryState(
    val phase: NdiDiscoveryPhase = NdiDiscoveryPhase.IDLE,
    val sources: List<NdiSource> = emptyList(),
    val proximity: Map<String, ProximityBand> = emptyMap(),
)

/**
 * Descoberta de fontes NDI na rede (NDIlib_find). Só roda entre [start] e [stop] (a tela "Ver na
 * rede" aberta) e só nesse período mantém o MulticastLock. Também mede a proximidade aproximada
 * (latência TCP) de cada fonte.
 */
@Singleton
class NdiDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ndiManager: NdiManager,
) {
    private val _state = MutableStateFlow(NdiDiscoveryState())
    val state: StateFlow<NdiDiscoveryState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var job: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /** Último conjunto de fontes visto (continua disponível depois do stop, para abrir a prévia). */
    @Volatile private var lastSources: List<NdiSource> = emptyList()

    /** Endereço (interno) da última fonte vista com esse nome; null se desconhecido. NUNCA exibir. */
    internal fun addressFor(name: String): String? = lastSources.firstOrNull { it.name == name }?.address

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        val previous = job
        acquireLock()
        _state.value = NdiDiscoveryState(NdiDiscoveryPhase.SEARCHING)
        job = scope.launch {
            previous?.cancelAndJoin()
            try {
                run()
            } finally {
                if (NdiNative.loaded) NdiNative.nativeFindStop()
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        releaseLock()
        _state.value = NdiDiscoveryState()
    }

    private suspend fun run() = coroutineScope {
        if (!NdiNative.loaded || !NdiNative.nativeFindStart()) {
            _state.value = NdiDiscoveryState(NdiDiscoveryPhase.ERROR)
            return@coroutineScope
        }
        val tracker = NdiLatencyTracker()
        val startedAt = System.currentTimeMillis()
        val probeGate = Semaphore(PROBE_PARALLELISM)

        // Sonda de proximidade em paralelo com a descoberta (medições em paralelo, registro em sequência).
        launch {
            while (isActive) {
                val snapshot = lastSources.filter { !it.isSelf && it.address != null }
                val measured = snapshot.map { src ->
                    async { src.name to probeGate.withPermit { NdiProbe.measure(src.address) } }
                }.awaitAll()
                val results = HashMap<String, ProximityBand>()
                for ((name, latency) in measured) results[name] = NdiProximity.band(tracker.record(name, latency))
                tracker.retainOnly(snapshot.map { it.name }.toSet())
                val current = _state.value
                if (current.phase != NdiDiscoveryPhase.IDLE && current.phase != NdiDiscoveryPhase.ERROR) {
                    _state.value = current.copy(proximity = results)
                }
                delay(PROBE_INTERVAL_MS)
            }
        }

        while (isActive) {
            val raw = NdiNative.nativeFindPoll(POLL_TIMEOUT_MS)
            if (raw == null) {
                _state.value = NdiDiscoveryState(NdiDiscoveryPhase.ERROR)
                break
            }
            val activeName = ndiManager.activeName.takeIf { ndiManager.isNdiActive.value }
            val sources = buildList {
                var i = 0
                while (i + 1 < raw.size) {
                    add(NdiSourceNames.build(raw[i], raw[i + 1].ifEmpty { null }, activeName))
                    i += 2
                }
            }.sortedBy { it.name.lowercase() }
            lastSources = sources
            val elapsed = System.currentTimeMillis() - startedAt
            val phase = if (sources.isEmpty() && elapsed < SEARCH_GRACE_MS) NdiDiscoveryPhase.SEARCHING else NdiDiscoveryPhase.READY
            val previous = _state.value
            if (previous.phase == NdiDiscoveryPhase.IDLE) break // parado durante a espera
            val names = sources.map { it.name }.toSet()
            val proximity = previous.proximity.filterKeys { it in names }
            if (previous.phase != phase || previous.sources != sources || previous.proximity != proximity) {
                _state.value = NdiDiscoveryState(phase, sources, proximity)
            }
        }
    }

    private fun acquireLock() {
        try {
            if (multicastLock == null) {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifi.createMulticastLock("bdsm_ndi_find_lock").apply { setReferenceCounted(false) }
            }
            multicastLock?.takeIf { !it.isHeld }?.acquire()
        } catch (t: Throwable) {
            Log.w(TAG, "MulticastLock indisponível: ${t.message}")
        }
    }

    private fun releaseLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Falha ao soltar o MulticastLock: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "NdiDiscovery"
        const val POLL_TIMEOUT_MS = 1000
        const val SEARCH_GRACE_MS = 4000L
        const val PROBE_INTERVAL_MS = 6000L
        const val PROBE_PARALLELISM = 4
    }
}
