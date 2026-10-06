package com.bragastudio.mobile.common.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Vocabulário háptico do app (design system v2): um ponto único para que toggles, seletores,
 * REC e confirmações vibrem de forma consistente. Respeita a configuração global de "feedback
 * ao toque" do Android (o `HapticFeedback` do Compose já faz isso) e usa os efeitos finos do
 * Android 11+ quando existem (ToggleOn/Off, Confirm/Reject), com fallback automático.
 */
@Stable
class BdsmHaptics internal constructor(private val feedback: HapticFeedback) {
    /** Toque leve em seletores, linhas e botões comuns. */
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)

    /** Liga/desliga um interruptor. */
    fun toggle(on: Boolean) = feedback.performHapticFeedback(
        if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
    )

    /** Ação concluída/confirmada (ex.: iniciar/parar REC). */
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)

    /** Ação recusada/destrutiva. */
    fun reject() = feedback.performHapticFeedback(HapticFeedbackType.Reject)

    /** Toque longo (abre ajuste, menu de contexto). */
    fun longPress() = feedback.performHapticFeedback(HapticFeedbackType.LongPress)
}

@Composable
fun rememberBdsmHaptics(): BdsmHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { BdsmHaptics(feedback) }
}
