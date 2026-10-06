package com.bragastudio.mobile.featurepreview

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bragastudio.mobile.common.components.rememberBdsmHaptics

// ============================================================================
// TEMA E CONSTANTES
// ============================================================================
// Os toggles avançados de captura (lanterna, estabilização OIS/EIS e HDR) são
// renderizados como botões de ícone dentro de TopBarMinimal (ver MinimalIconToggle).
// O antigo selo de qualidade (CameraQualityBadge) foi removido a pedido do usuário:

object HudTheme {
    val recordColor = Color.Red
    val recordActiveColor = Color(0xFFFF3D00)
    val ndiActiveColor = Color(0xFF00C853)
    val ndiInactiveColor = Color.White.copy(alpha = 0.1f)
    val buttonActiveColor = Color(0xFF2979FF)
    val buttonInactiveColor = Color.White.copy(alpha = 0.1f)
    val sidebarBackgroundColor = Color.Black.copy(alpha = 0.5f)

    val textColorPrimary = Color.White
    val textColorSecondary = Color.White.copy(alpha = 0.6f)
    val textColorMuted = Color.Gray

    val toolButtonSize = 34.dp
    val recordButtonSize = 72.dp
    val iconSizeSmall = 18.dp
    val iconSizeMedium = 24.dp

    /** Alvo de toque mínimo recomendado (Material/WCAG) para qualquer controle interativo. */
    val minTouchTarget = 48.dp

    val spacingSmall = 4.dp
    val spacingMedium = 8.dp
    val spacingLarge = 16.dp
    val spacingXLarge = 24.dp

    // M33: nenhuma fonte do HUD fica abaixo de 11 sp (legibilidade/acessibilidade).
    val fontSizeMin = 11.sp
    val fontSizeSmall = 11.sp
    val fontSizeMedium = 11.sp
    val fontSizeNormal = 12.sp
    val fontSizeLarge = 14.sp
}

/**
 * Textos de acessibilidade e de diálogos do HUD. Ficam em português fixo como o
 * resto da UI (a migração para strings.xml é o item B41) mas concentrados aqui
 * para facilitar essa migração depois.
 */
internal object HudStrings {
    const val REC_START = "Iniciar gravação"
    const val REC_STOP = "Parar gravação"
    const val REC_STATE_ON = "Gravando"
    const val REC_STATE_OFF = "Parado"
    const val STATE_ON = "Ativo"
    const val STATE_OFF = "Inativo"

    const val SONY_CHIP_CONNECTED = "Sony conectada"
    const val SONY_CHIP_SEARCHING = "Procurando câmera Sony"
    const val SONY_SHUTTER = "Disparar obturador da Sony"
}

/**
 * Clique acessível do HUD: `combinedClickable` (toque + toque longo) sem ripple —
 * o HUD usa feedback próprio (bounce, cor) — e com `role` semântico. Os
 * callbacks são lidos via rememberUpdatedState internamente pelo
 * combinedClickable, então lambdas recriadas a cada recomposição não reiniciam
 * o detector de gestos.
 *
 * Combine com [androidx.compose.material3.minimumInteractiveComponentSize]
 * ANTES deste modificador para garantir o alvo de 48 dp.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.hudClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onLongClickLabel: String? = null,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    // Háptico do design system v2: toque leve no clique e vibração de "toque longo" ao abrir ajustes.
    val haptics = rememberBdsmHaptics()
    Modifier.combinedClickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onLongClickLabel = onLongClickLabel,
        onLongClick = onLongClick?.let { action ->
            {
                haptics.longPress()
                action()
            }
        },
        onClick = {
            haptics.tick()
            onClick()
        },
    )
}

/**
 * Contorno sutil atrás de ícones "soltos" (sem fundo próprio, como os da
 * topbar/menu inferior que ficam direto sobre o preview) — um halo escuro
 * leve para garantir contraste caso a imagem por trás fique branca/estourada.
 * Ícones que já vivem dentro de um botão circular com fundo (LensDialButton,
 * REC etc.) não precisam disso, pois já têm contraste garantido pelo fundo.
 */
fun Modifier.drawIconOutline(): Modifier = this.drawBehind {
    drawCircle(
        color = Color.Black.copy(alpha = 0.35f),
        radius = size.minDimension * 0.62f,
    )
}

internal object ModernHudTheme {
    val accent = Color(0xFFFF9F0A)
    val recRed = Color(0xFFFF453A)
    val panelBg = Color.Black.copy(alpha = 0.94f)
    val panelBorder = Color.White.copy(alpha = 0.12f)
}

/**
 * Observa (sem consumir) cada toque/solta no componente — usado para reiniciar os
 * timers de ocultar/esmaecer do HUD sem interferir nos cliques dos filhos.
 */
fun Modifier.onAnyPress(callback: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.any { it.pressed != it.previousPressed }) callback()
        }
    }
}
