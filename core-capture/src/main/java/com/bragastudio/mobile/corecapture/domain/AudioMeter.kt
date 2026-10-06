package com.bragastudio.mobile.corecapture.domain

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Medidas de um bloco de PCM 16-bit. Valores lineares em 0..1 (fundo de escala = 1,0),
 * dB em dBFS (<= 0, limitado a [AudioMeter.FLOOR_DB]).
 */
data class AudioMeterResult(
    val rmsLeft: Float = 0f,
    val rmsRight: Float = 0f,
    val peakLeft: Float = 0f,
    val peakRight: Float = 0f,
    val peakDbLeft: Float = AudioMeter.FLOOR_DB,
    val peakDbRight: Float = AudioMeter.FLOOR_DB,
    /** Pico retido (peak-hold, ~1 s) em linear. */
    val holdLeft: Float = 0f,
    val holdRight: Float = 0f,
    /** Clip: pelo menos [AudioMeter.CLIP_RUN] amostras consecutivas >= [AudioMeter.CLIP_LEVEL] no bloco. */
    val clipLeft: Boolean = false,
    val clipRight: Boolean = false,
)

/**
 * Medidor de áudio (RMS + pico + peak-hold + clip). Lógica pura, sem Android, testável.
 * Não é thread-safe: use a partir da thread de leitura do AudioRecord.
 */
class AudioMeter(private val holdMs: Long = 1000L) {

    companion object {
        const val FLOOR_DB = -120f
        const val CLIP_LEVEL = 0.999f
        const val CLIP_RUN = 3
        private const val FULL_SCALE = 32768f

        fun toDb(linear: Float): Float = if (linear <= 0f) FLOOR_DB else (20f * log10(linear)).coerceIn(FLOOR_DB, 0f)
    }

    private var holdL = 0f
    private var holdR = 0f
    private var holdTimeL = 0L
    private var holdTimeR = 0L

    fun reset() {
        holdL = 0f
        holdR = 0f
        holdTimeL = 0L
        holdTimeR = 0L
    }

    /**
     * @param buffer amostras intercaladas (L,R,L,R...) ou mono
     * @param count total de shorts válidos em [buffer]
     * @param channels 1 ou 2 (mono é espelhado nos dois canais)
     * @param nowMs relógio monotônico em ms (injetável para teste)
     */
    fun process(buffer: ShortArray, count: Int, channels: Int, nowMs: Long): AudioMeterResult {
        val ch = if (channels >= 2) 2 else 1
        val frames = count / ch
        if (frames <= 0) return decay(nowMs)

        var sumL = 0.0
        var sumR = 0.0
        var peakL = 0f
        var peakR = 0f
        var runL = 0
        var runR = 0
        var clipL = false
        var clipR = false

        for (f in 0 until frames) {
            val l = abs(buffer[f * ch].toInt()) / FULL_SCALE
            sumL += l.toDouble() * l
            if (l > peakL) peakL = l
            if (l >= CLIP_LEVEL) {
                if (++runL >= CLIP_RUN) clipL = true
            } else {
                runL = 0
            }

            if (ch == 2) {
                val r = abs(buffer[f * ch + 1].toInt()) / FULL_SCALE
                sumR += r.toDouble() * r
                if (r > peakR) peakR = r
                if (r >= CLIP_LEVEL) {
                    if (++runR >= CLIP_RUN) clipR = true
                } else {
                    runR = 0
                }
            }
        }

        val rmsL = sqrt(sumL / frames).toFloat()
        val rmsR: Float
        if (ch == 2) {
            rmsR = sqrt(sumR / frames).toFloat()
        } else {
            rmsR = rmsL
            peakR = peakL
            clipR = clipL
        }

        // Peak-hold: sobe na hora; segura por holdMs e então cai para o pico atual.
        if (peakL >= holdL || nowMs - holdTimeL > holdMs) {
            holdL = peakL
            holdTimeL = nowMs
        }
        if (peakR >= holdR || nowMs - holdTimeR > holdMs) {
            holdR = peakR
            holdTimeR = nowMs
        }

        return AudioMeterResult(
            rmsLeft = rmsL, rmsRight = rmsR,
            peakLeft = peakL, peakRight = peakR,
            peakDbLeft = toDb(peakL), peakDbRight = toDb(peakR),
            holdLeft = holdL, holdRight = holdR,
            clipLeft = clipL, clipRight = clipR,
        )
    }

    private fun decay(nowMs: Long): AudioMeterResult {
        if (nowMs - holdTimeL > holdMs) holdL = 0f
        if (nowMs - holdTimeR > holdMs) holdR = 0f
        return AudioMeterResult(holdLeft = holdL, holdRight = holdR)
    }
}
