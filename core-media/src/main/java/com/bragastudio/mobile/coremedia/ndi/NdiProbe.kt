package com.bragastudio.mobile.coremedia.ndi

import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Suaviza a latência de cada fonte: mediana das últimas [window] medições (cada medição já é a
 * mediana de várias amostras), para a faixa não ficar pulando entre Perto/Médio/Longe.
 */
class NdiLatencyTracker(private val window: Int = 5) {
    private val history = LinkedHashMap<String, ArrayDeque<Long>>()

    /** Registra uma medição ([latencyMs] null = sem resposta) e devolve a latência suavizada (ou null). */
    fun record(key: String, latencyMs: Long?): Long? {
        val q = history.getOrPut(key) { ArrayDeque() }
        if (latencyMs != null) {
            q.addLast(latencyMs)
            while (q.size > window) q.removeFirst()
        } else if (q.isNotEmpty()) {
            q.removeFirst() // sem resposta: o histórico antigo vai perdendo peso até virar "sem dados"
        }
        return NdiProximity.median(q.toList())
    }

    /** Esquece fontes que sumiram da rede. */
    fun retainOnly(keys: Set<String>) {
        history.keys.retainAll(keys)
    }
}

/** Sonda de proximidade: tempo de um TCP connect ao endereço da fonte (mediana de poucas amostras). */
internal object NdiProbe {
    private const val SAMPLES = 3
    private const val TIMEOUT_MS = 700

    /** Mediana em ms das conexões que responderam; null se nenhuma respondeu. */
    suspend fun measure(address: String?): Long? = withContext(Dispatchers.IO) {
        val endpoint = NdiProximity.parseEndpoint(address) ?: return@withContext null
        val samples = ArrayList<Long>(SAMPLES)
        repeat(SAMPLES) {
            val start = System.nanoTime()
            val answered = try {
                Socket().use { it.connect(InetSocketAddress(endpoint.first, endpoint.second), TIMEOUT_MS) }
                true
            } catch (e: ConnectException) {
                // "Connection refused" também prova que o host respondeu (RST) e dá o tempo de ida e volta.
                e.message?.contains("refused", ignoreCase = true) == true
            } catch (_: Exception) {
                false
            }
            if (answered) samples += (System.nanoTime() - start) / 1_000_000L
        }
        NdiProximity.median(samples)
    }
}
