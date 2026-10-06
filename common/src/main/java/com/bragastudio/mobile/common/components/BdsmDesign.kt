package com.bragastudio.mobile.common.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bragastudio.mobile.common.ui.theme.BdsmTheme

// --- Design system v2 ("Pro de vídeo"): blocos reutilizáveis além dos de SettingsComponents ---

/**
 * Clique do design system: ripple padrão, háptico leve e (opcional) micro-escala ao pressionar.
 * A escala é lida só na fase de desenho (graphicsLayer), sem recompor o conteúdo.
 */
fun Modifier.bdsmClickable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    pressScale: Float = 1f,
    haptic: Boolean = true,
): Modifier = composed {
    val haptics = rememberBdsmHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && pressScale < 1f) pressScale else 1f,
        animationSpec = tween(durationMillis = 90),
        label = "pressScale",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
        ) {
            if (haptic) haptics.tick()
            onClick()
        }
}

/** Indicador tipo LED: ponto sólido com halo discreto quando [glow]. Decorativo (o estado vai no texto). */
@Composable
fun LedDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    glow: Boolean = false,
) {
    Spacer(
        modifier = modifier
            .size(size)
            .then(
                if (glow) {
                    Modifier.border(width = 3.dp, color = color.copy(alpha = 0.22f), shape = CircleShape)
                } else {
                    Modifier
                },
            )
            .background(color, CircleShape),
    )
}

/** Cabeçalho de seção: rótulo em caixa alta, fino, com semântica de título. */
@Composable
fun BdsmSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .padding(bottom = BdsmTheme.spacing.sm)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(BdsmTheme.spacing.sm))
        }
        // UX v2: título em caixa normal (14 sp), mais legível que o antigo rótulo em CAIXA ALTA de 11 sp.
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Cartão de métrica: rótulo (overline) + valor monoespaçado, opcionalmente com LED de estado.
 * Pensado para grades de 2-4 colunas (equipamento, NDI, gravação).
 */
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    led: Color? = null,
    icon: ImageVector? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.xxs)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.xs),
        ) {
            if (led != null) LedDot(color = led, size = 6.dp)
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = label,
                style = BdsmTheme.type.overline,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = BdsmTheme.type.metricLarge,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (unit != null) {
                Spacer(modifier = Modifier.width(BdsmTheme.spacing.xs))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

/** Botão primário (preenchido com a cor de destaque), reto, alvo >= 48 dp e háptico. */
@Composable
fun BdsmPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberBdsmHaptics()
    Button(
        onClick = {
            haptics.confirm()
            onClick()
        },
        enabled = enabled,
        shape = BdsmTheme.shapes.item,
        modifier = modifier.defaultMinSize(minHeight = BdsmTheme.spacing.touchTarget),
    ) {
        ButtonContent(text, icon)
    }
}

/** Botão secundário (contorno fino). */
@Composable
fun BdsmSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberBdsmHaptics()
    OutlinedButton(
        onClick = {
            haptics.tick()
            onClick()
        },
        enabled = enabled,
        shape = BdsmTheme.shapes.item,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = modifier.defaultMinSize(minHeight = BdsmTheme.spacing.touchTarget),
    ) {
        ButtonContent(text, icon)
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(BdsmTheme.spacing.sm))
    }
    Text(text = text, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** Etiqueta tonal pequena com valor monoespaçado (ex.: "ON", "4K · 30"). */
@Composable
fun TonalTag(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        style = BdsmTheme.type.metricSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(BdsmTheme.shapes.chip)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = BdsmTheme.spacing.sm, vertical = BdsmTheme.spacing.xxs),
    )
}

/**
 * Botão de texto do app. Usa `BdsmTheme.colors.primaryText` (vermelho mais claro no escuro, 4,5:1 ou
 * mais sobre preto, cartão e campos) em vez do `primary` da marca, que só serve para ícones e
 * preenchimentos (3,96:1 sobre o cartão escuro).
 */
@Composable
fun BdsmTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        contentPadding = contentPadding,
        colors = ButtonDefaults.textButtonColors(contentColor = BdsmTheme.colors.primaryText),
        content = content,
    )
}

/** Cores de campos de texto: rótulo focado em `primaryText` (texto pequeno) e borda/cursor no vermelho da marca. */
@Composable
fun bdsmTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    focusedLabelColor = BdsmTheme.colors.primaryText,
    cursorColor = BdsmTheme.colors.primaryText,
)
