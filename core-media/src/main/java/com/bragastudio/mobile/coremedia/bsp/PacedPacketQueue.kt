package com.bragastudio.mobile.coremedia.bsp

/**
 * Fila circular LIMITADA de pacotes RTP com horário de saída (pacing, 5.4). O produtor (thread do
 * encoder) entrega os pacotes de um quadro entre [beginFrame] e [endFrame]; no [endFrame] cada pacote
 * recebe o seu horário: o primeiro sai já e os demais se distribuem em até [PACING_FRACTION] do
 * intervalo do quadro (padrão 1/3), em vez de uma rajada única. O consumidor (thread de envio) pega os
 * pacotes vencidos com [pollDue].
 *
 * Limites: os slots são pré-alocados (nada de alocação por pacote) e a fila NÃO cresce: se faltar
 * slot, ou se o pacote mais antigo estiver atrasado mais de [maxLagNs] (o envio travou por mais de 2
 * quadros), os quadros NÃO-IDR em espera são descartados e [consumeOverflow] avisa para pedir um IDR.
 * Quadros IDR em espera são preservados.
 *
 * Seguro para 1 produtor e 1 consumidor (métodos sincronizados, trechos curtos).
 */
class PacedPacketQueue(
    private val slotCount: Int,
    private val slotSize: Int,
    private val maxLagNs: Long,
) : RtpPacketSink {
    companion object {
        /** Fração do intervalo do quadro em que os pacotes de um quadro são espalhados (5.4). */
        const val PACING_FRACTION_DIVISOR = 3L
        const val IMMEDIATE = 0L
    }

    private val buffers = Array(slotCount) { ByteArray(slotSize) }
    private val lengths = IntArray(slotCount)
    private val dueNs = LongArray(slotCount)
    private val isKey = BooleanArray(slotCount)

    // anel de índices de slot, do mais antigo (head) ao mais novo
    private val ring = IntArray(slotCount)
    private var head = 0
    private var size = 0
    private val free = IntArray(slotCount) { it }
    private var freeCount = slotCount
    private val scratch = IntArray(slotCount)

    private var frameKey = false
    private var frameFirstRingPos = 0
    private var framePackets = 0
    private var overflowed = false

    @get:Synchronized
    var droppedPackets = 0L
        private set

    @get:Synchronized
    val pending: Int get() = size

    /** Marca o início de um quadro; [key] = é um IDR (não será descartado no overflow). */
    @Synchronized
    fun beginFrame(key: Boolean) {
        frameKey = key
        framePackets = 0
        frameFirstRingPos = size
    }

    /** Copia o pacote para um slot livre. Sem slot: conta como descarte e marca overflow. */
    @Synchronized
    override fun send(packet: ByteArray, length: Int) {
        if (length > slotSize || freeCount == 0) {
            droppedPackets++
            overflowed = true
            return
        }
        val slot = free[--freeCount]
        System.arraycopy(packet, 0, buffers[slot], 0, length)
        lengths[slot] = length
        isKey[slot] = frameKey
        dueNs[slot] = Long.MAX_VALUE // até o endFrame distribuir os horários
        ring[(head + size) % slotCount] = slot
        size++
        framePackets++
    }

    /**
     * Fecha o quadro: distribui os horários dos pacotes dele entre [nowNs] e `nowNs + frameIntervalNs/3`
     * e, se a fila estiver atrasada demais, descarta os quadros não-IDR em espera. Devolve o horário
     * mais próximo de saída (para acordar o consumidor).
     */
    @Synchronized
    fun endFrame(nowNs: Long, frameIntervalNs: Long) {
        val n = framePackets
        if (n > 0) {
            val spread = frameIntervalNs / PACING_FRACTION_DIVISOR
            for (i in 0 until n) {
                val pos = (head + frameFirstRingPos + i) % slotCount
                // pacote i de n: espalhado de forma uniforme; o primeiro sai imediatamente
                dueNs[ring[pos]] = nowNs + if (n == 1) 0L else spread * i / (n - 1)
            }
        }
        if (size > 0 && nowNs - dueNs[ring[head]] > maxLagNs) {
            // o mais antigo está atrasado além do limite: o envio travou
            overflowed = true
        }
        if (overflowed) dropNonKeyFrames()
        framePackets = 0
        frameFirstRingPos = size
    }

    /** true (e zera) se houve descarte/overflow desde a última consulta: o chamador deve pedir um IDR. */
    @Synchronized
    fun consumeOverflow(): Boolean {
        val r = overflowed
        overflowed = false
        return r
    }

    /** Horário de saída do próximo pacote; [Long.MAX_VALUE] se a fila estiver vazia ou sem horário ainda. */
    @Synchronized
    fun nextDueNs(): Long = if (size == 0) Long.MAX_VALUE else dueNs[ring[head]]

    /**
     * Copia o pacote mais antigo para [dest] se já venceu ([nowNs] >= horário) e o remove; devolve o
     * tamanho, ou -1 se não há pacote vencido.
     */
    @Synchronized
    fun pollDue(nowNs: Long, dest: ByteArray): Int {
        if (size == 0) return -1
        val slot = ring[head]
        if (dueNs[slot] > nowNs) return -1
        val len = lengths[slot]
        System.arraycopy(buffers[slot], 0, dest, 0, len)
        head = (head + 1) % slotCount
        size--
        free[freeCount++] = slot
        return len
    }

    /** Esvazia a fila (parada/troca de receptor). */
    @Synchronized
    fun clear() {
        head = 0
        size = 0
        for (i in 0 until slotCount) free[i] = i
        freeCount = slotCount
        framePackets = 0
        frameFirstRingPos = 0
    }

    /** Compacta o anel mantendo só os pacotes de quadros IDR (em ordem). Os demais voltam aos slots livres. */
    private fun dropNonKeyFrames() {
        var kept = 0
        for (i in 0 until size) {
            val slot = ring[(head + i) % slotCount]
            if (isKey[slot]) {
                scratch[kept++] = slot
            } else {
                free[freeCount++] = slot
                droppedPackets++
            }
        }
        head = 0
        for (i in 0 until kept) ring[i] = scratch[i]
        size = kept
    }
}
