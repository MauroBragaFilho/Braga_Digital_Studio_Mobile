package com.bragastudio.mobile.coremedia.bsp

/** Limites do canal de controle do BSP v2 (`.docs/BSP_ESPECIFICACAO.md`, 4 e 10). */
object BspLimits {
    /** Tamanho máximo de uma linha JSON do controle; acima disso: erro e fechamento. */
    const val MAX_LINE_BYTES = 8 * 1024

    /** Uma conexão que não autentica nesse prazo é fechada com `ERROR AUTH`. */
    const val AUTH_TIMEOUT_MS = 4_000L

    /** Sessões simultâneas autenticadas; a terceira recebe `ERROR BUSY`. */
    const val MAX_SESSIONS = 2

    /** Conexões TCP abertas ao mesmo tempo (autenticadas ou não): protege contra inundação sem prova. */
    const val MAX_CONNECTIONS = MAX_SESSIONS + 2

    /** Mensagens por segundo aceitas de cada conexão. */
    const val MAX_MESSAGES_PER_SECOND = 20

    const val HEARTBEAT_INTERVAL_MS = 1_000L
    const val HEARTBEAT_TIMEOUT_MS = 5_000L

    /** META no máximo 2 vezes por segundo (4.2). */
    const val META_MIN_INTERVAL_MS = 500L

    /** SR a cada 1 s (6.1). */
    const val SR_INTERVAL_MS = 1_000L

    /** PLI: no máximo 1 IDR por 500 ms (6.4). */
    const val MIN_KEYFRAME_INTERVAL_MS = 500L

    const val MAX_CLIENT_ID = 64
    const val MAX_CLIENT_NAME = 40
}

/**
 * Limite de mensagens por segundo (janela deslizante de 1 s): aceita no máximo [maxPerSecond]
 * chamadas de [tryAcquire] em qualquer janela de 1000 ms. Sem alocação após a construção.
 */
class MessageRateLimiter(private val maxPerSecond: Int, private val clock: () -> Long) {
    private val stamps = LongArray(maxPerSecond)
    private var count = 0
    private var head = 0

    /** true = a mensagem cabe no limite (e foi contada); false = excedeu. */
    fun tryAcquire(): Boolean {
        val now = clock()
        if (count == maxPerSecond) {
            val oldest = stamps[head]
            if (now - oldest < 1_000L) return false
            // a mais antiga saiu da janela: o slot é reaproveitado
            stamps[head] = now
            head = (head + 1) % maxPerSecond
            return true
        }
        stamps[(head + count) % maxPerSecond] = now
        count++
        return true
    }
}

/** Política pura de admissão e de prazo de autenticação (testável sem rede). */
object BspSessionPolicy {
    enum class Admission {
        /** Há vaga. */
        ADMIT,

        /** Já existe sessão do MESMO cliente: ela é encerrada e esta a substitui (reconexão após queda). */
        REPLACE_SAME_CLIENT,

        /** Sem vaga: `ERROR BUSY`. */
        BUSY,
    }

    /**
     * [activeClientIds] = clientIds das sessões autenticadas em andamento. Um cliente que reconecta
     * substitui a própria sessão antiga (a queda pode ainda não ter sido percebida); caso contrário
     * vale o limite de [BspLimits.MAX_SESSIONS].
     */
    fun admit(activeClientIds: Collection<String>, clientId: String, maxSessions: Int = BspLimits.MAX_SESSIONS): Admission = when {
        activeClientIds.contains(clientId) -> Admission.REPLACE_SAME_CLIENT
        activeClientIds.size >= maxSessions -> Admission.BUSY
        else -> Admission.ADMIT
    }

    /** Tempo que ainda resta para a conexão autenticar (0 = esgotado). */
    fun authTimeLeftMs(connectedAtMs: Long, nowMs: Long, timeoutMs: Long = BspLimits.AUTH_TIMEOUT_MS): Long = (connectedAtMs + timeoutMs - nowMs).coerceIn(0L, timeoutMs)

    fun authExpired(connectedAtMs: Long, nowMs: Long, timeoutMs: Long = BspLimits.AUTH_TIMEOUT_MS): Boolean = authTimeLeftMs(connectedAtMs, nowMs, timeoutMs) == 0L

    /** Queda por falta de resposta: nenhuma mensagem do receptor há mais de [BspLimits.HEARTBEAT_TIMEOUT_MS]. */
    fun heartbeatExpired(lastHeardMs: Long, nowMs: Long): Boolean = nowMs - lastHeardMs > BspLimits.HEARTBEAT_TIMEOUT_MS
}

/** Limita os pedidos de IDR (PLI do receptor, descarte da fila): no máximo 1 por [minIntervalMs]. */
class KeyframeLimiter(private val minIntervalMs: Long = BspLimits.MIN_KEYFRAME_INTERVAL_MS) {
    private var last = Long.MIN_VALUE

    /** true = pode pedir o IDR agora (e registra o instante). */
    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        if (last != Long.MIN_VALUE && nowMs - last < minIntervalMs) return false
        last = nowMs
        return true
    }
}
