package com.bragastudio.mobile.corecapture.domain

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Lógica PURA de captura (sem tipos do framework Android), extraída para poder
 * ser testada com JUnit na JVM. Camera2Device, CameraDiscoveryEngine e
 * UvcCaptureDevice delegam a estas funções.
 */

/** Telemetria do último CaptureResult (atualizada com limitação de taxa). */
data class CaptureMetadata(
    val iso: Int? = null,
    val exposureTimeNs: Long? = null,
    val frameDurationNs: Long? = null,
    val focusDiopters: Float? = null,
    /** true quando a exposição está em automático (AE ligado). */
    val aeAuto: Boolean = true,
)

/** Limites dos controles manuais da câmera ATUAL (SENSOR_INFO_*_RANGE etc.). */
data class ManualLimits(
    val isoMin: Int? = null,
    val isoMax: Int? = null,
    val exposureMinNs: Long? = null,
    val exposureMaxNs: Long? = null,
    /** LENS_INFO_MINIMUM_FOCUS_DISTANCE em dioptrias; 0 = foco fixo. */
    val minFocusDiopters: Float = 0f,
)

data class FpsRange(val lower: Int, val upper: Int)

/** Resultado do planejamento de FPS. [aeRange] é null quando a HAL não anuncia faixas. */
data class FpsPlan(val aeRange: FpsRange?, val effectiveFps: Int)

object FpsPlanner {

    /**
     * Maior FPS sustentável dado o menor tempo de quadro da saída
     * (StreamConfigurationMap.getOutputMinFrameDuration). Tolerância de 0,5 %
     * para que 33 366 700 ns (29,97 fps) continue valendo "30".
     */
    fun maxFpsFromMinFrameDuration(minFrameDurationNs: Long): Int {
        if (minFrameDurationNs <= 0L) return Int.MAX_VALUE
        return floor(1_000_000_000.0 / minFrameDurationNs * 1.005).toInt().coerceAtLeast(1)
    }

    /** Duração de quadro (ns) para um FPS (usada com AE_MODE_OFF). */
    fun frameDurationNs(fps: Int): Long = 1_000_000_000L / fps.coerceAtLeast(1)

    /** FPS efetivo a partir de uma duração de quadro. */
    fun fpsFromFrameDuration(frameDurationNs: Long): Int = if (frameDurationNs <= 0L) 0 else (1_000_000_000.0 / frameDurationNs).roundToInt()

    /**
     * Escolhe a faixa de AE para [targetFps]. Preferência: faixa fixa [t,t];
     * depois a faixa que contém t com menor "folga" superior e maior piso;
     * por fim, se todas têm piso acima de t, a de menor piso (FPS efetivo = piso).
     * O alvo é limitado pelo maior FPS anunciado e pelo [minFrameDurationNs].
     */
    fun plan(ranges: List<FpsRange>, targetFps: Int, minFrameDurationNs: Long? = null): FpsPlan {
        val requested = targetFps.coerceAtLeast(1)
        if (ranges.isEmpty()) {
            val cap = minFrameDurationNs?.let { maxFpsFromMinFrameDuration(it) } ?: Int.MAX_VALUE
            return FpsPlan(null, min(requested, cap))
        }
        val cap = minFrameDurationNs?.let { maxFpsFromMinFrameDuration(it) } ?: Int.MAX_VALUE
        val maxAvailable = ranges.maxOf { it.upper }
        val wanted = min(min(requested, cap), maxAvailable).coerceAtLeast(1)

        ranges.firstOrNull { it.lower == wanted && it.upper == wanted }
            ?.let { return FpsPlan(it, wanted) }

        val containing = ranges.filter { it.lower <= wanted && it.upper >= wanted }
        if (containing.isNotEmpty()) {
            val best = containing.minWithOrNull(
                compareBy<FpsRange>({ it.upper - wanted }, { -it.lower }),
            )!!
            return FpsPlan(best, wanted)
        }

        // Todas as faixas com upper >= wanted têm lower > wanted: usa a de menor piso.
        val above = ranges.filter { it.upper >= wanted }
        val best = above.minWithOrNull(compareBy<FpsRange>({ it.lower }, { it.upper - it.lower }))
            ?: ranges.maxByOrNull { it.upper }!!
        return FpsPlan(best, max(best.lower, 1).coerceAtMost(best.upper))
    }
}

object ManualControls {

    fun coerceIso(iso: Int, min: Int?, max: Int?): Int = if (min != null && max != null && min <= max) iso.coerceIn(min, max) else iso

    fun coerceExposureNs(ns: Long, min: Long?, max: Long?): Long = if (min != null && max != null && min <= max) ns.coerceIn(min, max) else ns

    /** Foco manual em dioptrias limitado a [0, mínimo]. Retorna null se a lente tem foco fixo. */
    fun coerceFocus(diopters: Float, minFocusDistance: Float): Float? = if (minFocusDistance > 0f) diopters.coerceIn(0f, minFocusDistance) else null

    data class Exposure(val iso: Int, val exposureNs: Long)

    /**
     * Completa o par ISO/obturador quando só um deles é manual: o outro vem do
     * último CaptureResult (AE já convergido) ou de um valor de reserva.
     * Retorna null se nenhum dos dois é manual (AE automático).
     */
    fun resolveExposure(
        manualIso: Int?,
        manualShutterNs: Long?,
        lastIso: Int?,
        lastExposureNs: Long?,
        isoMin: Int? = null,
        isoMax: Int? = null,
        exposureMinNs: Long? = null,
        exposureMaxNs: Long? = null,
        fallbackIso: Int = 100,
        fallbackExposureNs: Long = 1_000_000_000L / 60,
    ): Exposure? {
        if (manualIso == null && manualShutterNs == null) return null
        val iso = coerceIso(manualIso ?: lastIso ?: fallbackIso, isoMin, isoMax)
        val exp = coerceExposureNs(manualShutterNs ?: lastExposureNs ?: fallbackExposureNs, exposureMinNs, exposureMaxNs)
        return Exposure(iso, exp)
    }
}

object LensClassifier {

    private const val DIAGONAL_35MM_MM = 43.27f

    /** Distância focal equivalente em 35 mm; 0 se faltar dado. */
    fun equivalentFocal35mm(focalMm: Float, sensorWidthMm: Float, sensorHeightMm: Float): Float {
        if (focalMm <= 0f || sensorWidthMm <= 0f || sensorHeightMm <= 0f) return 0f
        val diagonal = sqrt(sensorWidthMm * sensorWidthMm + sensorHeightMm * sensorHeightMm)
        return focalMm * DIAGONAL_35MM_MM / diagonal
    }

    /**
     * Classifica uma lente traseira física pela focal equivalente. Retorna null
     * quando [equivalentMm] não é conhecido (<= 0), para o chamador usar a
     * heurística antiga.
     *
     * Macro dedicada: foco mínimo muito curto (>= 20 dioptrias, ou seja <= 5 cm)
     * em sensor pequeno (<= 5 MP) e que não seja teleobjetiva.
     */
    fun classify(equivalentMm: Float, minFocusDiopters: Float = 0f, megapixels: Float = 0f): LensType? {
        if (equivalentMm <= 0f) return null
        if (minFocusDiopters >= 20f && megapixels in 0.01f..5f && equivalentMm < 60f) return LensType.MACRO
        return when {
            equivalentMm < 19f -> LensType.ULTRAWIDE
            equivalentMm < 42f -> LensType.MAIN
            equivalentMm < 100f -> LensType.TELEPHOTO
            else -> LensType.SUPER_TELEPHOTO
        }
    }
}

/** Modo (formato/tamanho/fps) anunciado por uma câmera UVC. */
data class UvcMode(val isMjpeg: Boolean, val width: Int, val height: Int, val fps: Int = 0)

object UvcFormatPicker {

    /**
     * Escolhe o modo mais próximo de [targetWidth]x[targetHeight]: nunca maior
     * que o alvo se houver algum menor/igual; MJPEG preferido (menos banda USB)
     * em 720p ou mais; YUYV só se for o único formato para aquele tamanho ou
     * se o tamanho for pequeno. Retorna null para lista vazia.
     */
    fun pick(modes: List<UvcMode>, targetWidth: Int = 1920, targetHeight: Int = 1080): UvcMode? {
        if (modes.isEmpty()) return null
        val targetArea = targetWidth.toLong() * targetHeight
        fun area(m: UvcMode) = m.width.toLong() * m.height

        val formatPref = compareBy<UvcMode>(
            {
                if (it.isMjpeg && it.height >= 720) {
                    1
                } else if (!it.isMjpeg && it.height < 720) {
                    1
                } else {
                    0
                }
            },
            { it.fps },
        )

        val notAbove = modes.filter { area(it) <= targetArea }
        if (notAbove.isNotEmpty()) {
            // Maior área possível dentro do alvo; empate resolvido pela preferência de formato.
            val bestArea = notAbove.maxOf { area(it) }
            return notAbove.filter { area(it) == bestArea }.maxWithOrNull(formatPref)
        }
        // Tudo acima do alvo: o menor disponível.
        val smallest = modes.minOf { area(it) }
        return modes.filter { area(it) == smallest }.maxWithOrNull(formatPref)
    }
}
