package com.bragastudio.mobile.coremedia.domain

import com.bragastudio.mobile.corecapture.domain.CaptureState

/*
 * Lógica PURA do MediaGraph (sem Android), extraída para teste na JVM.
 */

/** Classificação do estado da fonte para decidir se uma gravação em curso foi "perdida" (L1). */
object SourceHealth {
    /** Estados em que a fonte funciona ou está se (re)abrindo normalmente. */
    fun isHealthy(state: CaptureState): Boolean = state == CaptureState.READY || state == CaptureState.RECORDING || state == CaptureState.INITIALIZING

    /** Estados que, se persistirem além da tolerância, caracterizam perda da fonte. */
    fun isBad(state: CaptureState): Boolean = state == CaptureState.ERROR || state == CaptureState.IDLE

    /**
     * Decide, ao fim da tolerância, se a gravação deve ser finalizada por perda da fonte.
     * [recording] = há take em andamento; [stateNow] = estado da fonte após a espera.
     */
    fun shouldFinalizeAfterGrace(recording: Boolean, stateNow: CaptureState): Boolean = recording && isBad(stateNow)
}

/** FPS efetivo: o que a câmera realmente entrega quando conhecido (>0), senão o configurado. */
object FpsResolver {
    fun effective(deviceEffective: Int, configured: Int): Int = if (deviceEffective > 0) deviceEffective else configured.coerceAtLeast(1)

    /** Taxa NDI como fração (numerador/denominador) a partir de um fps inteiro. */
    fun ndiFrameRate(fps: Int): Pair<Int, Int> = effective(fps, 30) * 1000 to 1000
}
