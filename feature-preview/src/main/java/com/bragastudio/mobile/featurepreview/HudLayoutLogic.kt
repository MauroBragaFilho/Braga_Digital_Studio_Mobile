package com.bragastudio.mobile.featurepreview

import java.util.Locale

// ============================================================================
// Lógica PURA do layout compacto do HUD (sem Compose/Android), testável em JVM.
// ============================================================================

/** Tempo sem toque para o dock de ferramentas se ocultar (parado). */
internal const val DOCK_HIDE_DELAY_MS = 5_000L

/** Tempo sem toque para o dock se ocultar durante a gravação (mais cedo). */
internal const val DOCK_HIDE_DELAY_REC_MS = 3_000L

/** Tempo sem toque para a faixa de informações esmaecer levemente. */
internal const val STRIP_DIM_DELAY_MS = 6_000L

/** Atraso até o dock se ocultar, conforme o estado de gravação. */
internal fun dockHideDelayMs(isRecording: Boolean): Long = if (isRecording) DOCK_HIDE_DELAY_REC_MS else DOCK_HIDE_DELAY_MS

/**
 * O dock está oculto? Nunca se popovers/diálogos estiverem abertos; senão, quando
 * o tempo desde a última interação alcança o atraso.
 */
internal fun isDockHidden(msSinceInteraction: Long, isRecording: Boolean, popoverOpen: Boolean): Boolean = !popoverOpen && msSinceInteraction >= dockHideDelayMs(isRecording)

/** A faixa de informações esmaece (nunca some) depois de [STRIP_DIM_DELAY_MS] sem toque. */
internal fun isStripDimmed(msSinceInteraction: Long, popoverOpen: Boolean): Boolean = !popoverOpen && msSinceInteraction >= STRIP_DIM_DELAY_MS

// ---- Rótulos dos tiles --------------------------------------------------------

/** Valor "automático" (tom discreto) nos tiles. */
internal fun isAutoValue(value: String): Boolean = value == "AUTO" || value == "--" || value.isBlank()

/** `4K · 30 · H.265`. */
internal fun formatTileLabel(resolution: String, fps: Int, codec: String): String = "$resolution · $fps · $codec"

/**
 * Ângulo do obturador em graus (`360 · fps · tempo de exposição`), ou null se o
 * tempo for desconhecido. 1/60 s a 30 fps = 180°.
 */
internal fun shutterAngleDegrees(shutterNanos: Long?, fps: Int): Int? {
    if (shutterNanos == null || shutterNanos <= 0L || fps <= 0) return null
    val angle = 360.0 * fps * (shutterNanos / 1_000_000_000.0)
    return if (angle > 360.0) null else Math.round(angle).toInt()
}

/** Valor do tile do obturador: `1/60` ou `1/60 · 180°` quando o ângulo existe. */
internal fun shutterTileValue(shutterLabel: String, shutterNanos: Long?, fps: Int): String {
    if (isAutoValue(shutterLabel)) return "AUTO"
    val angle = shutterAngleDegrees(shutterNanos, fps) ?: return shutterLabel
    return "$shutterLabel · $angle°"
}

/** Valor do tile de foco: `AF` (automático) ou `MF · 1m`. */
internal fun focusTileValue(focusLabel: String): String = if (isAutoValue(focusLabel)) "AF" else "MF · $focusLabel"

/** Descrição falada de um tile (leitores de tela). */
internal fun tileSpokenLabel(name: String, value: String): String = "$name: ${if (isAutoValue(value)) "automático" else value}"

/** Abertura Sony como `f/2.8` (ou "--" se desconhecida). */
internal fun apertureTileValue(fNumber: String): String = if (isAutoValue(fNumber)) "--" else "f/$fNumber"

/** Texto compacto da bateria do aparelho. */
internal fun batteryLabel(percentage: Int): String = String.format(Locale.US, "%d%%", percentage.coerceIn(0, 100))
