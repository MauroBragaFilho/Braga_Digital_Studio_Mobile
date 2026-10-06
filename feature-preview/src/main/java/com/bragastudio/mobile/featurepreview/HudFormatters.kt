package com.bragastudio.mobile.featurepreview

import com.bragastudio.mobile.corecapture.domain.CameraInfoModel
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.log10

// ============================================================================
// UTILITÁRIOS PUROS (sem Compose/Android) — cobertos por testes JUnit
// ============================================================================
fun formatTimecode(milliseconds: Long, fps: Int): String {
    val ms = milliseconds.coerceAtLeast(0L)
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    val msRemainder = ms % 1000
    val frames = if (fps > 0) (msRemainder * fps) / 1000 else 0
    return String.format(Locale.US, "%02d:%02d:%02d:%02d", hours, minutes, secs, frames)
}

/**
 * Estima o tempo de gravação restante a partir do espaço livre e do bitrate de vídeo
 * atualmente configurado (assume-se ~10% adicional para o stream de áudio, prática comum
 * em calculadoras de gravação de câmeras profissionais).
 */
fun formatRecordingTimeRemaining(storageFreeGB: Float, bitrateMbps: Int): String {
    if (bitrateMbps <= 0) return "--:--"
    val effectiveMbps = bitrateMbps * 1.1f // margem para faixa de áudio
    val storageMegabits = storageFreeGB * 1024f * 8f
    val secondsRemaining = (storageMegabits / effectiveMbps).toLong()

    if (secondsRemaining <= 0) return "00:00"

    val hours = secondsRemaining / 3600
    val minutes = (secondsRemaining % 3600) / 60

    return if (hours > 0) {
        String.format(Locale.US, "%dh%02dm", hours, minutes)
    } else {
        String.format(Locale.US, "%02dm", minutes)
    }
}

// ----------------------------------------------------------------------------
// VU / ÁUDIO (M32)
// ----------------------------------------------------------------------------
// O nível que chega ao HUD é uma FRAÇÃO 0..1 linear em dB sobre a faixa
// [VU_FLOOR_DB, 0] dBFS (AudioCaptureService: (dB + 60) / 60). Portanto
// -12 dBFS = 0,80; -3 dBFS = 0,95; 0 dBFS = 1,00. As constantes antigas
// (0,68 / 0,90 / 0,98) valiam -19,2 / -6 / -1,2 dBFS.

/** Piso do medidor, em dBFS (nível 0,0 da barra). */
const val VU_FLOOR_DB = -60f

/** Converte dBFS (-inf..0) em fração 0..1 da barra. -inf e NaN viram 0. */
fun dbToFraction(db: Float, floorDb: Float = VU_FLOOR_DB): Float {
    if (db.isNaN()) return 0f
    return ((db - floorDb) / (0f - floorDb)).coerceIn(0f, 1f)
}

/** Inverso de [dbToFraction]: fração 0..1 da barra para dBFS. */
fun fractionToDb(fraction: Float, floorDb: Float = VU_FLOOR_DB): Float = floorDb + normalizeAudioLevel(fraction) * (0f - floorDb)

/** Amplitude linear (0..1, 1 = fundo de escala) para dBFS; 0 vira -infinito. */
fun linearToDb(amplitude: Float): Float = if (amplitude <= 0f || amplitude.isNaN()) Float.NEGATIVE_INFINITY else 20f * log10(amplitude)

/** Amplitude linear para fração da barra (atalho de [linearToDb] + [dbToFraction]). */
fun linearToFraction(amplitude: Float): Float = dbToFraction(linearToDb(amplitude))

fun normalizeAudioLevel(level: Float): Float {
    if (level.isNaN()) return 0f
    return level.coerceIn(0f, 1f)
}

/** Fim da zona verde da escala: -12 dBFS. */
val VU_GREEN_END_FRACTION: Float = dbToFraction(-12f)

/** Fim da zona amarela da escala: -3 dBFS. */
val VU_YELLOW_END_FRACTION: Float = dbToFraction(-3f)

// ----------------------------------------------------------------------------
// DIALS (M31)
// ----------------------------------------------------------------------------

/**
 * Índice da opção para um ângulo (graus, 0..360, sentido horário como o atan2 da
 * tela) sobre o anel que vai de [startAngle] e varre [sweep] graus. Ângulos na
 * "zona morta" do anel (abaixo do início ou além do fim) vão para a extremidade
 * mais próxima: a metade da zona morta pertence a cada lado.
 */
fun dialIndexForAngle(
    angleDegrees: Double,
    optionCount: Int,
    startAngle: Double = 150.0,
    sweep: Double = 240.0,
): Int {
    if (optionCount <= 1) return 0
    val relative = ((angleDegrees - startAngle) % 360.0 + 360.0) % 360.0
    val normalized = when {
        relative <= sweep -> relative / sweep

        // zona morta: metade final cai no início do anel, metade inicial no fim
        relative > sweep + (360.0 - sweep) / 2.0 -> 0.0

        else -> 1.0
    }
    return (normalized * (optionCount - 1) + 0.5).toInt().coerceIn(0, optionCount - 1)
}

/** Índice da opção para uma posição horizontal normalizada (0..1) da régua. */
fun sliderIndexForFraction(fraction: Float, optionCount: Int): Int {
    if (optionCount <= 1) return 0
    val f = fraction.coerceIn(0f, 1f)
    return (f * (optionCount - 1) + 0.5f).toInt().coerceIn(0, optionCount - 1)
}

// ----------------------------------------------------------------------------
// ROTA DE LENTE
// ----------------------------------------------------------------------------

/**
 * Rótulo de zoom da lente. Usa `CameraInfoModel.lensType`, que já é calculado pelo
 * CameraDiscoveryEngine a partir de dados reais de hardware (distância focal
 * via CameraCharacteristics, não o nome), e cai para a distância focal
 * diretamente quando o tipo vem como UNKNOWN — nunca faz correspondência por
 * dígito solto em texto. (A versão antiga por String foi removida: era código
 * morto e foi a causa do bug da lente 1x rotulada como "3x".)
 */
fun getLensLabel(lens: CameraInfoModel): String = when (lens.lensType) {
    com.bragastudio.mobile.corecapture.domain.LensType.ULTRAWIDE -> "0.5x"

    com.bragastudio.mobile.corecapture.domain.LensType.MAIN -> "1x"

    com.bragastudio.mobile.corecapture.domain.LensType.TELEPHOTO -> "2x"

    com.bragastudio.mobile.corecapture.domain.LensType.SUPER_TELEPHOTO -> "3x"

    com.bragastudio.mobile.corecapture.domain.LensType.MACRO -> "Macro"

    com.bragastudio.mobile.corecapture.domain.LensType.FRONT -> "Frontal"

    com.bragastudio.mobile.corecapture.domain.LensType.EXTERNAL -> "EXT"

    else -> {
        // Fallback só quando o tipo não foi classificado: usa a distância
        // focal real (mesmo critério do CameraDiscoveryEngine), nunca o nome.
        val focal = lens.focalLengths.firstOrNull() ?: 0f
        when {
            focal in 0.1f..3.0f -> "0.5x"
            focal in 3.0f..7.0f -> "1x"
            focal in 7.0f..10.0f -> "2x"
            focal > 10.0f -> "3x"
            else -> "1x"
        }
    }
}

/** Índice (0-based) do segmento de uma escala de [segmentCount] que contém [fraction]; -1 se zero. */
internal fun segmentIndexForFraction(fraction: Float, segmentCount: Int): Int {
    val f = normalizeAudioLevel(fraction)
    if (f <= 0f || segmentCount <= 0) return -1
    return (ceil((f * segmentCount - 1e-4f).toDouble()).toInt() - 1).coerceIn(0, segmentCount - 1)
}
