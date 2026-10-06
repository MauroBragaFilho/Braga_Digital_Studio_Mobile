package com.bragastudio.mobile.featurepreview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.InvertColors
import androidx.compose.material.icons.filled.Texture
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup

private val GridOptions = listOf("OFF", "3x3", "4x4", "Centro")

/**
 * Dock de ferramentas de monitoramento: SÓ ícones (visual 40 dp, alvo 48 dp), fundo
 * translúcido, estado ativo por cor. Mostra as 4 mais usadas (Scopes, Zebra, Peak,
 * LUT) e um "+" que expande o resto (False Color, Grid, Aspect). Vertical (paisagem)
 * ou horizontal (retrato). Interações:
 *  - Scopes / False Color: toggles diretos.
 *  - Zebra / Peak / LUT: toque liga/desliga; SEGURAR abre o popover de ajuste.
 *  - Grid: toque liga/desliga; SEGURAR escolhe o tipo. Aspect: toque cicla; SEGURAR lista.
 * O auto-ocultar do dock é feito pelo chamador; [onInteraction] avisa cada toque aqui
 * e [onPopoverOpenChanged] impede o ocultamento enquanto algum popover estiver aberto.
 */
// onNavigateToLuts é usado dentro do LutListPopover (item fixo "Gerenciar LUTs..." no rodapé).
@Composable
fun ToolsDock(
    vertical: Boolean,
    isScopesVisible: Boolean,
    isZebraEnabled: Boolean,
    zebraThreshold: Int,
    onSetZebraThreshold: (Int) -> Unit,
    isFocusPeakingEnabled: Boolean,
    focusPeakingSensitivity: Float,
    onSetFocusPeakingSensitivity: (Float) -> Unit,
    focusPeakingColor: String,
    onSetFocusPeakingColor: (String) -> Unit,
    isFalseColorEnabled: Boolean,
    isLutEnabled: Boolean,
    allLuts: List<com.bragastudio.mobile.core.model.Lut>,
    activeLut: com.bragastudio.mobile.core.model.Lut?,
    onSelectLut: (String?) -> Unit,
    onNavigateToLuts: () -> Unit,
    currentAspectRatio: String,
    onSetAspectRatio: (String) -> Unit,
    currentGrid: String,
    onToggleGrid: () -> Unit,
    onSetGrid: (String) -> Unit,
    onToggleScopes: () -> Unit,
    onToggleZebra: () -> Unit,
    onToggleFocusPeaking: () -> Unit,
    onToggleFalseColor: () -> Unit,
    onToggleLut: () -> Unit,
    modifier: Modifier = Modifier,
    onInteraction: () -> Unit = {},
    onPopoverOpenChanged: (String, Boolean) -> Unit = { _, _ -> },
) {
    var openDial by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    PopoverReporter("dock", openDial != null, onPopoverOpenChanged)

    val zebraOptions = remember { (0..100 step 10).map { it.toString() } }
    val peakingOptions = remember { listOf("BAIXA", "MED", "ALTA") }
    // "Nenhum (Desativado)" fica de fora: o toque no botão de LUT liga/desliga.
    val aspectOptions = remember { listOf("OFF", "4:3", "16:9", "2.35:1", "1:1") }
    val peakingValueLabel = when {
        focusPeakingSensitivity < 0.34f -> "BAIXA"
        focusPeakingSensitivity < 0.67f -> "MED"
        else -> "ALTA"
    }
    val gridActive = currentGrid != "OFF"
    val aspectActive = currentAspectRatio != "OFF"
    val extrasActive = isFalseColorEnabled || gridActive || aspectActive

    val gapPx = with(LocalDensity.current) { 12.dp.roundToPx() }
    val popupPosition = remember(gapPx, vertical) {
        HudPopupPositionProvider(if (vertical) HudPopupSide.END else HudPopupSide.ABOVE, gapPx)
    }

    Box(
        modifier = modifier.onAnyPress(onInteraction),
    ) {
        openDial?.let { param ->
            Popup(popupPositionProvider = popupPosition, onDismissRequest = { openDial = null }) {
                when (param) {
                    "zebra" -> CircularDialPopover(
                        title = "ZEBRA · LIMIAR",
                        currentValueLabel = zebraThreshold.toString(),
                        options = zebraOptions,
                        isManual = true,
                        onSelect = { onSetZebraThreshold(it.toIntOrNull() ?: zebraThreshold) },
                        onDismiss = { openDial = null },
                    )

                    "peaking" -> FocusPeakingPanel(
                        sensitivityValueLabel = peakingValueLabel,
                        sensitivityOptions = peakingOptions,
                        onSelectSensitivity = { selected ->
                            onSetFocusPeakingSensitivity(
                                when (selected) {
                                    "BAIXA" -> 0.15f
                                    "ALTA" -> 0.85f
                                    else -> 0.5f
                                },
                            )
                        },
                        currentColor = focusPeakingColor,
                        onSelectColor = onSetFocusPeakingColor,
                        onDismiss = { openDial = null },
                    )

                    "lut" -> LutListPopover(
                        luts = allLuts,
                        activeLutId = activeLut?.id,
                        onSelect = { lutId -> onSelectLut(lutId) },
                        onDismiss = { openDial = null },
                        onNavigateToLuts = {
                            onNavigateToLuts()
                            openDial = null
                        },
                    )

                    "aspect" -> SimpleListPopover(
                        title = "ASPECT RATIO",
                        options = aspectOptions,
                        activeOption = currentAspectRatio,
                        onSelect = { onSetAspectRatio(it) },
                        onDismiss = { openDial = null },
                    )

                    "grid" -> SimpleListPopover(
                        title = "GRADE",
                        options = GridOptions,
                        activeOption = currentGrid,
                        onSelect = {
                            onSetGrid(it)
                            openDial = null
                        },
                        onDismiss = { openDial = null },
                    )
                }
            }
        }

        val primary: @Composable () -> Unit = {
            DockButton(Icons.Filled.BarChart, "Scopes", isScopesVisible, onClick = onToggleScopes)
            DockButton(
                Icons.Filled.Texture,
                "Zebra",
                isZebraEnabled,
                detail = if (isZebraEnabled) "$zebraThreshold%" else null,
                onClick = onToggleZebra,
                onLongClick = { openDial = "zebra" },
            )
            DockButton(
                Icons.Filled.CenterFocusStrong,
                "Focus peaking",
                isFocusPeakingEnabled,
                detail = if (isFocusPeakingEnabled) peakingValueLabel else null,
                onClick = onToggleFocusPeaking,
                onLongClick = { openDial = "peaking" },
            )
            DockButton(
                Icons.Filled.ColorLens,
                "LUT",
                isLutEnabled,
                detail = if (isLutEnabled) activeLut?.displayName else null,
                onClick = onToggleLut,
                onLongClick = { openDial = "lut" },
            )
            DockButton(
                if (expanded) Icons.Filled.Close else Icons.Filled.Add,
                if (expanded) "Menos ferramentas" else "Mais ferramentas",
                isActive = !expanded && extrasActive,
                onClick = { expanded = !expanded },
                stateful = false,
            )
        }
        val extras: @Composable () -> Unit = {
            DockButton(Icons.Filled.InvertColors, "False color", isFalseColorEnabled, onClick = onToggleFalseColor)
            DockButton(
                Icons.Filled.GridOn,
                "Grade",
                gridActive,
                detail = if (gridActive) currentGrid else null,
                onClick = onToggleGrid,
                onLongClick = { openDial = "grid" },
            )
            DockButton(
                Icons.Filled.AspectRatio,
                "Aspect ratio",
                aspectActive,
                detail = if (aspectActive) currentAspectRatio else null,
                onClick = {
                    val next = (aspectOptions.indexOf(currentAspectRatio).coerceAtLeast(0) + 1) % aspectOptions.size
                    onSetAspectRatio(aspectOptions[next])
                },
                onLongClick = { openDial = "aspect" },
            )
        }

        val shape = RoundedCornerShape(24.dp)
        val panel = Modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.34f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
        if (vertical) {
            Row(modifier = panel) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { primary() }
                if (expanded) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.align(Alignment.CenterVertically)) { extras() }
            }
        } else {
            Column(modifier = panel, horizontalAlignment = Alignment.CenterHorizontally) {
                if (expanded) Row { extras() }
                Row { primary() }
            }
        }
    }
}

/**
 * Botão-ícone do dock: visual de 40 dp dentro de um alvo de 48 dp, sem rótulo
 * (contentDescription + stateDescription para leitores de tela); ativo = âmbar.
 */
@Composable
private fun DockButton(
    icon: ImageVector,
    spokenLabel: String,
    isActive: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    detail: String? = null,
    stateful: Boolean = true,
) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .hudClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "Ajustar $spokenLabel" else null,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = if (detail != null) "$spokenLabel, $detail" else spokenLabel
                if (stateful) stateDescription = if (isActive) HudStrings.STATE_ON else HudStrings.STATE_OFF
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isActive) ModernHudTheme.accent.copy(alpha = 0.18f) else Color.Transparent)
                .border(1.dp, if (isActive) ModernHudTheme.accent else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (isActive) ModernHudTheme.accent else Color.White.copy(alpha = 0.92f),
                modifier = Modifier.size(22.dp),
            )
        }
        if (isActive && stateful) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(ModernHudTheme.accent),
            )
        }
    }
}
