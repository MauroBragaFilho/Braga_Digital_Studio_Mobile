package com.bragastudio.mobile.featurehome

import androidx.compose.runtime.Immutable
import com.bragastudio.mobile.core.domain.VideoSettings
import com.bragastudio.mobile.corecapture.status.CameraStatus

/** Estado imutável da Home (um único objeto: só recompõe quando algo muda de fato). */
@Immutable
data class HomeUiState(
    /** "1080p · 30 fps" do formato configurado (cartão do Monitor). */
    val formatSummary: String = "",
    /** Início (epoch ms) de uma gravação em andamento, ou null. */
    val recordingStartedAt: Long? = null,
    /** Há uma câmera aberta (Monitor, gravação ou NDI em andamento). */
    val cameraActive: Boolean = false,
    /** Câmera sondada no start (sem abrir a câmera): pronta, sem permissão, indisponível ou carregando. */
    val camera: CameraStatus = CameraStatus.Loading,
    /** Nome da saudação (escolhido em Ajustes ou nome do aparelho); null = só a saudação. */
    val greetingName: String? = null,
    /** false até a primeira emissão de todas as fontes (a tela mostra o estado de carregamento). */
    val loaded: Boolean = false,
)

/** Período do dia para a saudação da Home. */
enum class DayPeriod { MORNING, AFTERNOON, NIGHT }

/** Regras puras (testadas em JVM) da Home. */
object HomeStatus {
    /** Gravação "viva" = em andamento e iniciada há menos de [STALE_RECORDING_MS] (ignora linhas órfãs de uma queda). */
    const val STALE_RECORDING_MS = 6L * 60 * 60 * 1000

    fun activeRecordingStart(inProgressCreatedAt: List<Long>, now: Long): Long? = inProgressCreatedAt.filter { now - it in 0..STALE_RECORDING_MS }.maxOrNull()

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

    /** "Bom dia, Maria"; sem nome (nulo/vazio), só "Bom dia". */
    fun greeting(salutation: String, name: String?): String = name?.trim()?.takeIf { it.isNotEmpty() }?.let { "$salutation, $it" } ?: salutation

    /** "1080p · 30 fps": o que a pessoa reconhece, sem codec. */
    fun plainFormat(video: VideoSettings): String = "${video.resolution} · ${video.fps} fps"
}
