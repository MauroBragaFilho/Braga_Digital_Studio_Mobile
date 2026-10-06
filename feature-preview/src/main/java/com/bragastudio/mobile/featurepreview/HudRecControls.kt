package com.bragastudio.mobile.featurepreview

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bragastudio.mobile.common.components.rememberBdsmHaptics
import com.bragastudio.mobile.corecapture.domain.CameraInfoModel

/**
 * Botão REC (60 dp) + chips de lente em fila discreta ao lado.
 * Paisagem ([landscape]): chips em coluna à esquerda do REC (lado direito da tela).
 * Retrato: REC centralizado e chips à direita dele. REC bloqueado em transição de RecState.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecAndLensControls(
    landscape: Boolean,
    isHudVisible: Boolean,
    isRecording: Boolean,
    currentLens: CameraInfoModel?,
    availableLenses: List<CameraInfoModel>,
    onRecordClick: () -> Unit,
    onLensSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    // Preparando/Finalizando o take: o toque seria ignorado pelo MediaGraph, então o
    // botão fica desabilitado e esmaecido para não parecer travado.
    isRecTransitioning: Boolean = false,
) {
    val recButton: @Composable () -> Unit = {
        // Botão REC com "bounce" no toque + transição suave entre o círculo (parado)
        // e o quadrado (gravando). combinedClickable com semântica de botão + estado.
        val interactionSource = remember { MutableInteractionSource() }
        val haptics = rememberBdsmHaptics()
        val isPressed by interactionSource.collectIsPressedAsState()
        val recScale by animateFloatAsState(
            targetValue = if (isPressed) 0.88f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessHigh,
            ),
            label = "recButtonScale",
        )
        val recAlpha by animateFloatAsState(
            targetValue = if (isRecTransitioning) 0.45f else 1f,
            animationSpec = tween(150),
            label = "recButtonAlpha",
        )
        val innerSize by animateDpAsState(
            targetValue = if (isRecording) 24.dp else 48.dp,
            animationSpec = tween(220),
            label = "recInnerSize",
        )
        val innerCornerRadius by animateDpAsState(
            targetValue = if (isRecording) 8.dp else 24.dp, // 24dp = metade de 48dp -> círculo perfeito
            animationSpec = tween(220),
            label = "recInnerCorner",
        )

        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .graphicsLayer {
                    scaleX = recScale
                    scaleY = recScale
                    alpha = recAlpha
                }
                .size(60.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(4.dp, Color.Black.copy(alpha = 0.3f), CircleShape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = !isRecTransitioning,
                    role = Role.Button,
                    onClickLabel = if (isRecording) HudStrings.REC_STOP else HudStrings.REC_START,
                    onClick = {
                        haptics.confirm()
                        onRecordClick()
                    },
                )
                .semantics {
                    contentDescription = if (isRecording) HudStrings.REC_STOP else HudStrings.REC_START
                    stateDescription = when {
                        isRecTransitioning -> "Aguarde"
                        isRecording -> HudStrings.REC_STATE_ON
                        else -> HudStrings.REC_STATE_OFF
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(innerSize)
                    .clip(RoundedCornerShape(innerCornerRadius))
                    .background(Color(0xFFD32F2F)),
            )
        }
    }
    val chips: @Composable () -> Unit = {
        if (isHudVisible) {
            LensChips(availableLenses, currentLens, onLensSelect, vertical = landscape)
        }
    }
    if (landscape) {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chips()
            recButton()
        }
    } else {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            recButton()
            Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) { chips() }
        }
    }
}

/** Fila discreta de lentes (0.5x 1x 2x ...): toque seleciona direto; a atual em âmbar. */
@Composable
private fun LensChips(
    availableLenses: List<CameraInfoModel>,
    currentLens: CameraInfoModel?,
    onLensSelect: (String) -> Unit,
    vertical: Boolean,
) {
    if (availableLenses.size < 2) {
        // Uma só lente: apenas o rótulo atual, sem ação.
        val only = availableLenses.firstOrNull() ?: return
        LensChip(getLensLabel(only), selected = true, onClick = {})
        return
    }
    val content: @Composable () -> Unit = {
        availableLenses.forEach { lens ->
            LensChip(getLensLabel(lens), selected = lens.id == currentLens?.id, onClick = { onLensSelect(lens.id) })
        }
    }
    if (vertical) {
        Column(
            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    } else {
        Row(
            modifier = Modifier.widthIn(max = 150.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

@Composable
private fun LensChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .hudClickable(onClick = onClick, role = Role.RadioButton, onClickLabel = "Usar lente $label")
            .semantics {
                contentDescription = "Lente $label"
                stateDescription = if (selected) "Selecionada" else ""
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else Color.White.copy(alpha = 0.9f),
            fontWeight = FontWeight.Bold,
            fontSize = HudTheme.fontSizeNormal,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) ModernHudTheme.accent else Color.Black.copy(alpha = 0.4f))
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}
