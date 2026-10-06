package com.bragastudio.mobile.featurepreview

import com.bragastudio.mobile.corecapture.domain.CaptureMetadata
import com.bragastudio.mobile.corecapture.domain.ManualLimits
import com.bragastudio.mobile.coremedia.domain.RecordingEvent
import com.bragastudio.mobile.network.TallyState

// ============================================================================
// Lógica PURA do Preview/HUD (sem Compose/Android), testável em JVM.
// ============================================================================

/** Mensagem exibida quando há take sendo finalizado em segundo plano. */
internal const val FINALIZING_MESSAGE = "Finalizando gravação…"

/**
 * Traduz um [RecordingEvent] numa mensagem para o operador (Snackbar/Toast).
 * `null` = silencioso (Stopped: o próprio fim do REC já é feedback suficiente).
 */
internal fun recordingEventMessage(event: RecordingEvent): String? = when (event) {
    is RecordingEvent.SourceLost -> "Fonte perdida — gravação finalizada"

    is RecordingEvent.DiskFull -> "Disco cheio — gravação finalizada"

    is RecordingEvent.EncoderError -> {
        val detail = event.message.trim()
        if (detail.isEmpty()) {
            "Erro no codificador — gravação finalizada"
        } else {
            "Erro no codificador — gravação finalizada ($detail)"
        }
    }

    is RecordingEvent.Stopped -> null
}

/** "Finalizando gravação…" enquanto houver finalizações pendentes; null caso contrário. */
internal fun finalizingMessage(pendingFinalizations: Int): String? = if (pendingFinalizations > 0) FINALIZING_MESSAGE else null

/** Cor lógica da borda de tally (a UI mapeia para Color). */
internal enum class TallyIndicator { NONE, RED, GREEN }

/**
 * Tally do OBS + REC local: vermelho para PROGRAM (ou gravando), verde para
 * PREVIEW, nenhum para OFF. REC local sempre vence (comportamento anterior).
 */
internal fun tallyIndicator(tally: TallyState, isRecording: Boolean): TallyIndicator = when {
    isRecording -> TallyIndicator.RED
    tally == TallyState.PROGRAM -> TallyIndicator.RED
    tally == TallyState.PREVIEW -> TallyIndicator.GREEN
    else -> TallyIndicator.NONE
}

// ---- Exposição manual: estado inicial a partir da metadata (L2) -------------

/** ISO/obturador manuais (null = AUTO). */
internal data class ManualExposure(val iso: Int?, val shutterNs: Long?)

/**
 * Estado manual inicial do ViewModel a partir da telemetria real da câmera: AE
 * ligado = AUTO; AE desligado = os valores aplicados pelo sensor.
 */
internal fun manualExposureFrom(metadata: CaptureMetadata): ManualExposure = if (metadata.aeAuto) {
    ManualExposure(null, null)
} else {
    ManualExposure(metadata.iso, metadata.exposureTimeNs)
}

// ---- Faixas dos controles manuais -------------------------------------------

/** True quando a câmera ativa anunciou faixas de ISO/obturador. */
internal fun ManualLimits.isKnown(): Boolean = isoMin != null || exposureMinNs != null

private fun coerceBounded(value: Long, min: Long?, max: Long?): Long {
    var v = value
    if (min != null) v = maxOf(v, min)
    if (max != null) v = minOf(v, max)
    return v
}

/** Limita o ISO à faixa do sensor (null = AUTO continua null). */
internal fun coerceIsoToLimits(iso: Int?, limits: ManualLimits): Int? {
    if (iso == null) return null
    return coerceBounded(iso.toLong(), limits.isoMin?.toLong(), limits.isoMax?.toLong()).toInt()
}

/** Limita o tempo de exposição (ns) à faixa do sensor. */
internal fun coerceShutterToLimits(shutterNs: Long?, limits: ManualLimits): Long? {
    if (shutterNs == null) return null
    return coerceBounded(shutterNs, limits.exposureMinNs, limits.exposureMaxNs)
}

/**
 * Limita o foco (dioptrias) a 0..minFocusDiopters. Foco fixo (min <= 0) só aceita
 * infinito (0). Sem faixas conhecidas não mexe no valor.
 */
internal fun coerceFocusToLimits(diopters: Float?, limits: ManualLimits): Float? {
    if (diopters == null) return null
    if (!limits.isKnown()) return diopters.coerceAtLeast(0f)
    return if (limits.minFocusDiopters <= 0f) 0f else diopters.coerceIn(0f, limits.minFocusDiopters)
}

/** True se a lente tem foco manual (distância mínima > 0). Sem faixas conhecidas, assume que sim. */
internal fun supportsManualFocus(limits: ManualLimits): Boolean = !limits.isKnown() || limits.minFocusDiopters > 0f

/** Mantém "AUTO" e só os ISOs dentro da faixa. Sem faixa conhecida, devolve tudo. */
internal fun filterIsoOptions(options: List<String>, limits: ManualLimits): List<String> {
    if (!limits.isKnown()) return options
    return options.filter { label ->
        val iso = label.toIntOrNull() ?: return@filter true // AUTO
        coerceIsoToLimits(iso, limits) == iso
    }
}

/** Mantém "AUTO" e só os obturadores dentro da faixa. */
internal fun filterShutterOptions(options: List<String>, limits: ManualLimits): List<String> {
    if (!limits.isKnown()) return options
    return options.filter { label ->
        val ns = shutterLabelToNanosLocal(label) ?: return@filter true // AUTO
        coerceShutterToLimits(ns, limits) == ns
    }
}

/** Mantém "AUTO", infinito e as distâncias que a lente alcança. */
internal fun filterFocusOptions(options: List<String>, limits: ManualLimits): List<String> {
    if (!limits.isKnown()) return options
    return options.filter { label ->
        val d = focusLabelToDiopter(label) ?: return@filter true // AUTO
        coerceFocusToLimits(d, limits) == d
    }
}
