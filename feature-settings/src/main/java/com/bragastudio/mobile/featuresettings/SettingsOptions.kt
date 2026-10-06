package com.bragastudio.mobile.featuresettings

/**
 * Opções tipadas das configurações (M48). Cada enum guarda em [persisted] a
 * string EXATA que já é gravada no DataStore — nenhum valor persistido muda,
 * então instalações existentes continuam lendo as preferências antigas.
 *
 * A leitura é tolerante: valor desconhecido/corrompido cai no padrão do enum
 * (o mesmo padrão que o RecordManager usa no `else` do `when`).
 */
interface PersistedOption {
    val persisted: String
    val label: String
}

private fun <T> parseOption(
    values: Array<T>,
    raw: String?,
): T? where T : Enum<T>, T : PersistedOption = raw?.trim()?.let { r -> values.firstOrNull { it.persisted.equals(r, ignoreCase = true) } }

/** Resolução da gravação. Persistido como "1080p" | "1440p" | "4K". */
enum class Resolution(override val persisted: String, override val label: String) : PersistedOption {
    P1080("1080p", "1080p (Full HD)"),
    P1440("1440p", "1440p (QHD)"),
    UHD4K("4K", "4K (UHD)"),
    ;

    companion object {
        val DEFAULT = P1080
        fun fromPersistedOrNull(raw: String?): Resolution? = parseOption(values(), raw)
        fun fromPersisted(raw: String?): Resolution = fromPersistedOrNull(raw) ?: DEFAULT
    }
}

/** Taxa de quadros oferecida na UI. Persistido como Int (24 | 30 | 60). */
enum class Fps(val value: Int) {
    F24(24),
    F30(30),
    F60(60),
    ;

    val label: String get() = "$value FPS"

    companion object {
        val DEFAULT = F30
        fun fromValueOrNull(value: Int?): Fps? = values().firstOrNull { it.value == value }
        fun fromValue(value: Int?): Fps = fromValueOrNull(value) ?: DEFAULT
    }
}

/** Codec de vídeo. Persistido como "H.264" | "H.265". */
enum class Codec(override val persisted: String, override val label: String) : PersistedOption {
    H264("H.264", "H.264 (AVC)"),
    H265("H.265", "H.265 (HEVC)"),
    ;

    companion object {
        val DEFAULT = H264
        fun fromPersistedOrNull(raw: String?): Codec? = parseOption(values(), raw)
        fun fromPersisted(raw: String?): Codec = fromPersistedOrNull(raw) ?: DEFAULT
    }
}

/** Fonte de vídeo. Persistido como "Camera" | "USB" | "SONY". */
enum class VideoSourceOption(override val persisted: String, override val label: String) : PersistedOption {
    CAMERA("Camera", "Câmera do celular"),
    USB("USB", "Câmera USB (UVC)"),
    SONY("SONY", "Sony (Wi-Fi)"),
    ;

    companion object {
        val DEFAULT = CAMERA
        fun fromPersistedOrNull(raw: String?): VideoSourceOption? = parseOption(values(), raw)
        fun fromPersisted(raw: String?): VideoSourceOption = fromPersistedOrNull(raw) ?: DEFAULT
    }
}

/** Cor do Focus Peaking. Persistido em inglês: "Red" | "Green" | "Blue" | "White". */
enum class PeakingColor(override val persisted: String, override val label: String) : PersistedOption {
    RED("Red", "Vermelho"),
    GREEN("Green", "Verde"),
    BLUE("Blue", "Azul"),
    WHITE("White", "Branco"),
    ;

    companion object {
        val DEFAULT = RED
        fun fromPersistedOrNull(raw: String?): PeakingColor? = parseOption(values(), raw)
        fun fromPersisted(raw: String?): PeakingColor = fromPersistedOrNull(raw) ?: DEFAULT
    }
}

/** Sensibilidade do Focus Peaking. Persistido: "Low" | "Medium" | "High". */
enum class PeakingSensitivity(override val persisted: String, override val label: String) : PersistedOption {
    LOW("Low", "Baixa"),
    MEDIUM("Medium", "Média"),
    HIGH("High", "Alta"),
    ;

    companion object {
        val DEFAULT = MEDIUM
        fun fromPersistedOrNull(raw: String?): PeakingSensitivity? = parseOption(values(), raw)
        fun fromPersisted(raw: String?): PeakingSensitivity = fromPersistedOrNull(raw) ?: DEFAULT
    }
}

/** Preset de resolução do NDI/BSP. Persistido: "HD" | "FHD" | "QHD" | "UHD". */
enum class StreamPreset(override val persisted: String, override val label: String) : PersistedOption {
    HD("HD", "720p"),
    FHD("FHD", "1080p"),
    QHD("QHD", "1440p"),
    UHD("UHD", "4K"),
    ;

    companion object {
        val DEFAULT = FHD
        fun fromPersistedOrNull(raw: String?): StreamPreset? = parseOption(values(), raw)
        fun fromPersisted(raw: String?): StreamPreset = fromPersistedOrNull(raw) ?: DEFAULT
    }
}

/** Opções numéricas oferecidas nos diálogos (os valores gravados continuam sendo Int). */
object NumericOptions {
    val BITRATES_MBPS = listOf(25, 50, 100)
    val ZEBRA_THRESHOLDS = listOf(70, 80, 90, 100)
}

/**
 * Regras puras de validação (sem Android) — testadas em JUnit.
 */
object SettingsRules {
    const val MAX_STREAM_NAME_LENGTH = 64

    /** Remove espaços nas pontas, quebras de linha e limita o tamanho. Vazio é permitido (= usar padrão). */
    fun sanitizeStreamName(raw: String): String = raw.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_STREAM_NAME_LENGTH).trim()

    /** Nome efetivamente usado: o digitado ou, se vazio, o padrão. */
    fun effectiveStreamName(stored: String, default: String): String = stored.trim().ifEmpty { default }

    /**
     * O valor a gravar muda algo? Compara o nome já sanitizado com o efetivo
     * armazenado (nome vazio digitado equivale ao padrão).
     */
    fun streamNameChanged(typed: String, stored: String, default: String): Boolean {
        val newEffective = effectiveStreamName(sanitizeStreamName(typed), default)
        return newEffective != effectiveStreamName(stored, default)
    }

    private val HOSTNAME_REGEX = Regex(
        "^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$",
    )

    /** IPv4 decimal pontuado válido (4 octetos 0..255) ou nome de host DNS simples. */
    fun isValidHost(host: String): Boolean {
        val h = host.trim()
        if (h.isEmpty() || h.length > 253) return false
        // Só dígitos e pontos: precisa ser um IPv4 válido (rejeita "999.1.1.1" e "1.2.3").
        if (h.all { it.isDigit() || it == '.' }) {
            val parts = h.split('.')
            return parts.size == 4 &&
                parts.all { it.isNotEmpty() && it.length <= 3 && it.toInt() in 0..255 }
        }
        return HOSTNAME_REGEX.matches(h)
    }

    fun coerceBitrate(mbps: Int): Int = mbps.coerceIn(1, 400)
    fun coerceZebra(threshold: Int): Int = threshold.coerceIn(0, 100)
}

/** Texto para o bitrate do NDI: sem dado → "--" (nunca um valor inventado). */
fun formatBitrateMbps(mbps: Int?): String = if (mbps == null || mbps <= 0) "--" else "$mbps Mbps"

/** Texto para latência: sem dado → "--". */
fun formatLatencyMs(ms: Int?): String = if (ms == null || ms <= 0) "--" else "$ms ms"
