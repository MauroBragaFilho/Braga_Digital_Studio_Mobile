package com.bragastudio.mobile.featurehome

import androidx.compose.runtime.Immutable
import com.bragastudio.mobile.core.domain.HardwareMetrics
import com.bragastudio.mobile.core.domain.VideoSettings
import com.bragastudio.mobile.corecapture.status.CameraStatus
import kotlin.math.roundToInt

/** Situação geral do equipamento no painel da Home. */
enum class EquipmentHealth { Ok, Warning, Critical }

/** Nível de um indicador individual (cor do LED). */
enum class Level { Ok, Warning, Critical }

/** Fonte de vídeo configurada (rótulo localizado na UI). */
enum class SourceKind { Camera, Usb, Sony }

/** Estado do servidor Link para o painel. */
enum class LinkPhase { Off, Starting, Running }

/** Estado imutável da Home (um único objeto: só recompõe quando algo muda de fato). */
@Immutable
data class HomeUiState(
    val metrics: HardwareMetrics = HardwareMetrics(),
    val ipAddress: String? = null,
    val formatSummary: String = "",
    val source: SourceKind = SourceKind.Camera,
    val ndiEnabled: Boolean = false,
    val link: LinkPhase = LinkPhase.Off,
    val activeLutName: String? = null,
    /** Início (epoch ms) de uma gravação em andamento, ou null. */
    val recordingStartedAt: Long? = null,
    /** Bitrate de gravação (Mbps), para estimar o tempo restante de gravação. */
    val bitrateMbps: Int = 50,
    /** Há uma câmera aberta (Monitor, gravação ou NDI em andamento). */
    val cameraActive: Boolean = false,
    /** Câmera sondada no start (sem abrir a câmera): pronta, sem permissão, indisponível ou carregando. */
    val camera: CameraStatus = CameraStatus.Loading,
    /** false até a primeira emissão de todas as fontes (a tela mostra o estado de carregamento). */
    val loaded: Boolean = false,
)

/** Indicadores da faixa de métricas do aparelho. */
enum class MetricKind { TEMPERATURE, STORAGE, BATTERY, WIFI }

/** Período do dia para a saudação da Home. */
enum class DayPeriod { MORNING, AFTERNOON, NIGHT }

/** Frase de estado da Home: uma só, a mais importante. */
enum class HomeHeadline { RECORDING, ATTENTION, READY }

/** O que merece atenção (ordem de prioridade: armazenamento, bateria, temperatura). */
enum class AttentionReason { STORAGE, BATTERY, TEMPERATURE }

/** Regras puras (testadas em JVM) que traduzem métricas em níveis, saúde e textos do painel. */
object HomeStatus {
    const val TEMP_WARN_C = 40f
    const val TEMP_CRIT_C = 45f
    const val BATTERY_WARN_PCT = 20
    const val BATTERY_CRIT_PCT = 10
    const val STORAGE_WARN_GB = 10f
    const val STORAGE_CRIT_GB = 3f

    fun temperatureLevel(celsius: Float): Level = when {
        celsius >= TEMP_CRIT_C -> Level.Critical
        celsius >= TEMP_WARN_C -> Level.Warning
        else -> Level.Ok
    }

    /** Bateria baixa só preocupa se não estiver carregando. */
    fun batteryLevel(percent: Int, charging: Boolean): Level = when {
        charging -> Level.Ok
        percent <= BATTERY_CRIT_PCT -> Level.Critical
        percent <= BATTERY_WARN_PCT -> Level.Warning
        else -> Level.Ok
    }

    /** Armazenamento total desconhecido (0) não gera alerta. */
    fun storageLevel(freeGb: Float, totalGb: Float): Level = when {
        totalGb <= 0f -> Level.Ok
        freeGb <= STORAGE_CRIT_GB -> Level.Critical
        freeGb <= STORAGE_WARN_GB -> Level.Warning
        else -> Level.Ok
    }

    fun health(metrics: HardwareMetrics): EquipmentHealth {
        val levels = listOf(
            temperatureLevel(metrics.temperatureCelsius),
            batteryLevel(metrics.batteryPercentage, metrics.isCharging),
            storageLevel(metrics.storageFreeGB, metrics.storageTotalGB),
        )
        return when {
            Level.Critical in levels -> EquipmentHealth.Critical
            Level.Warning in levels -> EquipmentHealth.Warning
            else -> EquipmentHealth.Ok
        }
    }

    /** "1080p · 30 · H.264" (mesmo padrão do HUD do Monitor: resolução · fps · codec). */
    fun formatSummary(video: VideoSettings): String = "${video.resolution} · ${video.fps} · ${video.codec}"

    /** Gravação "viva" = em andamento e iniciada há menos de [STALE_RECORDING_MS] (ignora linhas órfãs de uma queda). */
    const val STALE_RECORDING_MS = 6L * 60 * 60 * 1000

    fun activeRecordingStart(inProgressCreatedAt: List<Long>, now: Long): Long? = inProgressCreatedAt.filter { now - it in 0..STALE_RECORDING_MS }.maxOrNull()

    fun attentionReason(m: HardwareMetrics): AttentionReason? = when {
        storageLevel(m.storageFreeGB, m.storageTotalGB) != Level.Ok -> AttentionReason.STORAGE
        batteryLevel(m.batteryPercentage, m.isCharging) != Level.Ok -> AttentionReason.BATTERY
        temperatureLevel(m.temperatureCelsius) != Level.Ok -> AttentionReason.TEMPERATURE
        else -> null
    }

    /** Nível de um indicador da faixa; Wi-Fi desligado é atenção (NDI e Link dependem da rede). */
    fun metricLevel(kind: MetricKind, m: HardwareMetrics): Level = when (kind) {
        MetricKind.TEMPERATURE -> temperatureLevel(m.temperatureCelsius)
        MetricKind.STORAGE -> storageLevel(m.storageFreeGB, m.storageTotalGB)
        MetricKind.BATTERY -> batteryLevel(m.batteryPercentage, m.isCharging)
        MetricKind.WIFI -> if (m.isWifiConnected) Level.Ok else Level.Warning
    }

    /** Indicadores que pedem atenção agora (a faixa só destaca estes). */
    fun metricAlerts(m: HardwareMetrics): Set<MetricKind> = MetricKind.entries.filterTo(mutableSetOf()) { metricLevel(it, m) != Level.Ok }

    /** "38°C"; "--" quando o sensor não informou. */
    fun formatTemperature(celsius: Float): String = if (celsius <= 0f) "--" else "${celsius.roundToInt()}°C"

    /** Espaço livre, "21 GB"; "--" quando o total é desconhecido. */
    fun formatStorageFree(freeGb: Float, totalGb: Float): String = if (totalGb <= 0f) "--" else "${freeGb.roundToInt()} GB"

    fun headline(m: HardwareMetrics, recordingStartedAt: Long?): HomeHeadline = when {
        recordingStartedAt != null -> HomeHeadline.RECORDING
        attentionReason(m) != null -> HomeHeadline.ATTENTION
        else -> HomeHeadline.READY
    }

    /** Minutos de gravação que cabem no espaço livre (vídeo apenas). 0 = sem dado. */
    fun remainingMinutes(freeGb: Float, bitrateMbps: Int): Int {
        if (freeGb <= 0f || bitrateMbps <= 0) return 0
        val bytesPerSecond = bitrateMbps * 1_000_000.0 / 8.0
        return (freeGb * 1024.0 * 1024.0 * 1024.0 / bytesPerSecond / 60.0).toInt()
    }

    /** "3 h 20 min" ou "45 min". */
    fun formatRemaining(minutes: Int): String = when {
        minutes >= 60 * 100 -> "99+ h"
        minutes >= 60 -> "${minutes / 60} h ${"%02d".format(minutes % 60)} min"
        else -> "$minutes min"
    }

    /** "00:12" ou "1:02:03". */
    fun formatElapsed(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000).toInt()
        val h = total / 3600
        val m = total % 3600 / 60
        val sec = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    /** Hora (0..23) -> período da saudação: bom dia até 11h59, boa tarde até 17h59, boa noite depois. */
    fun dayPeriod(hour: Int): DayPeriod = when (hour) {
        in 5..11 -> DayPeriod.MORNING
        in 12..17 -> DayPeriod.AFTERNOON
        else -> DayPeriod.NIGHT
    }

    /** "1080p · 30 fps": o que a pessoa reconhece, sem codec. */
    fun plainFormat(video: VideoSettings): String = "${video.resolution} · ${video.fps} fps"

    fun sourceKind(raw: String?): SourceKind = when (raw?.trim()?.uppercase()) {
        "USB" -> SourceKind.Usb
        "SONY" -> SourceKind.Sony
        else -> SourceKind.Camera
    }
}
