package com.bragastudio.mobile.common.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bragastudio.mobile.common.module.ModuleCategory
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import com.bragastudio.mobile.common.ui.theme.bdsmIsShortLandscape

// --- UX v3 (One UI, uma mão): blocos de navegação adaptativa, cartões de módulo e botões grandes ---

/** Barra lateral para telas largas/paisagem (equivalente da barra inferior, mesmos itens). */
@Composable
fun BdsmNavigationRail(
    items: List<BdsmNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = NavigationRailDefaults.windowInsets,
) {
    val haptics = rememberBdsmHaptics()
    NavigationRail(
        modifier = modifier,
        windowInsets = windowInsets,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Spacer(Modifier.weight(1f))
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            NavigationRailItem(
                selected = selected,
                onClick = {
                    if (!selected) haptics.tick()
                    onSelect(index)
                },
                icon = { Icon(imageVector = item.icon, contentDescription = null) },
                label = { Text(text = item.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                alwaysShowLabel = true,
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

/** Intenção de um botão grande: define só a cor (cor com significado). */
enum class BigButtonTone { Primary, Danger, Neutral }

/**
 * Botão grande (altura mínima 56 dp, cantos de 20 dp) para a ação principal da tela, pensado para o
 * polegar. O texto segue a regra do guia One UI (só a primeira letra maiúscula). Vermelho = ação
 * principal; [BigButtonTone.Danger] = interromper/excluir (vermelho de erro, com ícone e texto).
 */
@Composable
fun BdsmBigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: BigButtonTone = BigButtonTone.Primary,
    enabled: Boolean = true,
) {
    val haptics = rememberBdsmHaptics()
    val content: @Composable () -> Unit = {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(BdsmTheme.spacing.sm))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val sizeModifier = modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 56.dp)
    if (tone == BigButtonTone.Neutral) {
        OutlinedButton(
            onClick = {
                haptics.tick()
                onClick()
            },
            enabled = enabled,
            shape = BdsmTheme.shapes.card,
            modifier = sizeModifier,
        ) { content() }
    } else {
        val container = if (tone == BigButtonTone.Danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        val onContainer = if (tone == BigButtonTone.Danger) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
        Button(
            onClick = {
                haptics.confirm()
                onClick()
            },
            enabled = enabled,
            shape = BdsmTheme.shapes.card,
            colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = onContainer),
            modifier = sizeModifier,
        ) { content() }
    }
}

/**
 * Cartão de módulo da grade da Home (One UI: 20 dp, tonal, sem borda; os contêineres de 26 dp são
 * os grupos e o hero): ícone grande colorido, nome, uma linha de estado/descrição e seta. Altura mínima fixa (sem IntrinsicSize), alvo de toque inteiro, descrito como um único
 * botão para o TalkBack (ícone e seta são decorativos).
 */
@Composable
fun ModuleCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    status: String? = null,
    accent: Color = BdsmTheme.colors.accentNeutral,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(BdsmTheme.shapes.card)
            .background(BdsmTheme.colors.card)
            .bdsmClickable(onClick = onClick, role = Role.Button)
            .heightIn(min = 120.dp)
            .padding(BdsmTheme.spacing.lg)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(32.dp))
        Row(
            modifier = Modifier.padding(top = BdsmTheme.spacing.md),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.xs),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (status != null) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Linha de categoria de configurações (estilo One UI): ícone redondo, título, descrição curta e
 * seta. Sem interruptores; tocar abre a tela da categoria.
 */
@Composable
fun SettingsCategoryRow(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    accent: Color = BdsmTheme.colors.accentNeutral,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .bdsmGroupRow()
            .heightIn(min = 72.dp)
            .bdsmClickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = BdsmTheme.spacing.md, vertical = BdsmTheme.spacing.md)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.lg),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (description.isNotEmpty()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(20.dp)
                .clearAndSetSemantics { },
        )
    }
}

/** Folha inferior padrão (ações ao alcance do polegar): título opcional e conteúdo com margens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BdsmBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        // Folha com os cantos de cima do contêiner grande (26 dp).
        shape = BdsmTheme.shapes.container.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        // Paisagem de celular: folha mais larga (o padrão de 640 dp deixava o conteúdo apertado).
        sheetMaxWidth = if (bdsmIsShortLandscape()) 880.dp else 640.dp,
        modifier = modifier,
    ) {
        // Rolável: em paisagem (altura ~360 dp) o conteúdo não cabe e ficava cortado/inalcançável.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = BdsmTheme.spacing.lg, end = BdsmTheme.spacing.lg, bottom = BdsmTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { contentDescription = title },
                )
            }
            content()
        }
    }
}

/** Par rótulo/valor para folhas de detalhes (valor à direita, monoespaçado quando técnico). */
@Composable
fun DetailLine(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = if (mono) BdsmTheme.type.metric else MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** Cor de acento da categoria de um módulo (ícone dos cartões; nunca decide estado). */
@Composable
fun ModuleCategory.accent(): Color = when (this) {
    ModuleCategory.CAPTURE -> BdsmTheme.colors.accentVideo
    ModuleCategory.LIBRARY -> BdsmTheme.colors.accentLibrary
    ModuleCategory.NETWORK -> BdsmTheme.colors.accentNetwork
    ModuleCategory.SYSTEM -> BdsmTheme.colors.accentSystem
}
