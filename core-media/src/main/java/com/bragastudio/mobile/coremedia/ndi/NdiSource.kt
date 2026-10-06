package com.bragastudio.mobile.coremedia.ndi

/** Tipo inferido pelo nome da fonte (o NDI não informa o tipo de aparelho). */
enum class NdiSourceType { BDSM, OBS, VMIX, OTHER }

/**
 * Uma fonte NDI visível na rede. [address] (IP:porta) existe SÓ para conectar o receptor e para a
 * sonda de proximidade: NUNCA deve ser exibido (a UI usa modelos próprios sem esse campo; o
 * [toString] também não o inclui).
 */
class NdiSource(
    val name: String,
    internal val address: String?,
    val type: NdiSourceType,
    val isSelf: Boolean,
) {
    /** Nome para exibir: para BDSM só o nome do aparelho; sempre sem IPs. */
    val displayName: String get() = NdiSourceNames.displayName(name, type)

    override fun equals(other: Any?): Boolean = other is NdiSource && other.name == name && other.address == address && other.isSelf == isSelf

    override fun hashCode(): Int = 31 * (31 * name.hashCode() + (address?.hashCode() ?: 0)) + isSelf.hashCode()

    override fun toString(): String = "NdiSource(name=$name, type=$type, isSelf=$isSelf)"
}

/** Classificação e nome de exibição das fontes (puro, testável em JVM). */
object NdiSourceNames {
    private const val BDSM_PREFIX = "BDSM ("
    private val IPV4 = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}(?::\d{1,5})?\b""")
    private val OBS_WORD = Regex("""\bobs\b""")
    private const val MASK = "•••"

    /** Tipo pelo padrão do nome: "BDSM (x)", OBS (DistroAV: "PC (OBS)"), vMix ("PC (vMix - ...)"). */
    fun classify(ndiName: String): NdiSourceType {
        val trimmed = ndiName.trim()
        if (trimmed.startsWith(BDSM_PREFIX) && trimmed.endsWith(")")) return NdiSourceType.BDSM
        val all = trimmed.lowercase()
        return when {
            OBS_WORD.containsMatchIn(all) -> NdiSourceType.OBS
            all.contains("vmix") -> NdiSourceType.VMIX
            else -> NdiSourceType.OTHER
        }
    }

    /** Nome mostrado: o aparelho dentro de "BDSM (...)"; os demais tipos mostram o nome completo. IPs são mascarados. */
    fun displayName(ndiName: String, type: NdiSourceType = classify(ndiName)): String {
        val base = if (type == NdiSourceType.BDSM) {
            val t = ndiName.trim()
            if (t.contains('(') && t.endsWith(")")) t.substringAfter('(').removeSuffix(")").trim() else t
        } else {
            ndiName.trim()
        }
        return maskIps(base).ifEmpty { "NDI" }
    }

    /** Troca qualquer IPv4 (com ou sem porta) por "•••": o IP nunca aparece na interface. */
    fun maskIps(text: String): String = IPV4.replace(text, MASK)

    /**
     * A fonte é este aparelho transmitindo? Na rede o nome é "BDSM (sender)", mas o SDK mostra as
     * fontes da própria máquina como "LOCALHOST (sender)": aceita as duas máquinas.
     */
    fun isSelf(ndiName: String, activeSenderName: String?, machine: String = "BDSM"): Boolean {
        if (activeSenderName.isNullOrBlank()) return false
        val n = ndiName.trim()
        return n == "$machine ($activeSenderName)" || n.equals("LOCALHOST ($activeSenderName)", ignoreCase = true)
    }

    fun build(ndiName: String, address: String?, activeSenderName: String?): NdiSource = isSelf(ndiName, activeSenderName).let { self ->
        // Este aparelho aparece como "LOCALHOST (nome)", mas é um BDSM.
        NdiSource(ndiName, address, if (self) NdiSourceType.BDSM else classify(ndiName), self)
    }
}

/** Faixa de proximidade APROXIMADA (distância de rede por latência, não física). */
enum class ProximityBand { NEAR, MEDIUM, FAR, UNKNOWN, SELF }

object NdiProximity {
    const val NEAR_MAX_MS = 8L
    const val MEDIUM_MAX_MS = 25L

    /** Mediana das amostras (ms) que responderam; null sem nenhuma amostra. */
    fun median(samples: List<Long>): Long? {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    fun band(latencyMs: Long?): ProximityBand = when {
        latencyMs == null -> ProximityBand.UNKNOWN
        latencyMs <= NEAR_MAX_MS -> ProximityBand.NEAR
        latencyMs <= MEDIUM_MAX_MS -> ProximityBand.MEDIUM
        else -> ProximityBand.FAR
    }

    /** Extrai o host de "ip:porta", "[ipv6]:porta" ou "host". Porta padrão do NDI quando ausente. */
    fun parseEndpoint(address: String?, defaultPort: Int = 5960): Pair<String, Int>? {
        val a = address?.trim().orEmpty()
        if (a.isEmpty()) return null
        if (a.startsWith("[")) {
            val end = a.indexOf(']')
            if (end < 0) return null
            val port = a.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            return a.substring(1, end) to port
        }
        val colons = a.count { it == ':' }
        return when {
            colons == 0 -> a to defaultPort
            colons == 1 -> a.substringBefore(':') to (a.substringAfter(':').toIntOrNull() ?: defaultPort)
            else -> a to defaultPort // IPv6 sem colchetes: sem porta
        }
    }
}

/** Ordenação das fontes (para a lista e para o radar). */
enum class NdiSortMode { PROXIMITY, TYPE }

object NdiSourceSorting {
    private val bandOrder = mapOf(
        ProximityBand.SELF to 0,
        ProximityBand.NEAR to 1,
        ProximityBand.MEDIUM to 2,
        ProximityBand.FAR to 3,
        ProximityBand.UNKNOWN to 4,
    )
    private val typeOrder = mapOf(NdiSourceType.BDSM to 0, NdiSourceType.OBS to 1, NdiSourceType.VMIX to 2, NdiSourceType.OTHER to 3)

    fun <T> sorted(
        items: List<T>,
        mode: NdiSortMode,
        type: (T) -> NdiSourceType,
        band: (T) -> ProximityBand,
        name: (T) -> String,
    ): List<T> {
        val byName = compareBy<T, String>(String.CASE_INSENSITIVE_ORDER) { name(it) }
        val comparator = when (mode) {
            NdiSortMode.PROXIMITY -> compareBy<T> { bandOrder.getValue(band(it)) }.then(byName)
            NdiSortMode.TYPE -> compareBy<T> { typeOrder.getValue(type(it)) }.then(compareBy { bandOrder.getValue(band(it)) }).then(byName)
        }
        return items.sortedWith(comparator)
    }
}

/** Qualidade do recebimento da prévia. */
enum class NdiPreviewQuality { STANDARD, LOW }

object NdiQualityPolicy {
    /** Status térmico do Android (PowerManager.THERMAL_STATUS_MODERATE = 2): a partir daí usa o proxy de baixa banda. */
    const val THERMAL_MODERATE = 2

    fun forThermal(thermalStatus: Int): NdiPreviewQuality = if (thermalStatus >= THERMAL_MODERATE) NdiPreviewQuality.LOW else NdiPreviewQuality.STANDARD
}
