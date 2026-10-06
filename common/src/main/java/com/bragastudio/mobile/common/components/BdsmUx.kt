package com.bragastudio.mobile.common.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bragastudio.mobile.common.ui.theme.BdsmTheme

// --- UX v2: blocos de navegação, estado do sistema, busca e "desfazer" ---

/** Destino da barra inferior. */
data class BdsmNavItem(val label: String, val icon: ImageVector, val iconSelected: ImageVector = icon)

/**
 * Barra de navegação inferior com 3-5 destinos de nível superior. Rótulo sempre visível (ícone
 * sozinho confunde), alvo >= 48 dp (Material) e háptico leve ao trocar.
 */
@Composable
fun BdsmNavigationBar(
    items: List<BdsmNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberBdsmHaptics()
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            NavigationBarItem(
                selected = selected,
                onClick = {
                    if (!selected) haptics.tick()
                    onSelect(index)
                },
                icon = {
                    Icon(
                        imageVector = if (selected) item.iconSelected else item.icon,
                        contentDescription = null,
                    )
                },
                label = {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/** Gravidade de um indicador de estado; sempre acompanhada de ícone + texto (nunca só cor). */
enum class StatusLevel { Ok, Warning, Critical, Off }

/** Cor semântica: verde = ok, âmbar = atenção, vermelho = problema, neutro = desligado/sem dado. */
@Composable
fun StatusLevel.tint(): Color = when (this) {
    StatusLevel.Ok -> BdsmTheme.colors.success
    StatusLevel.Warning -> BdsmTheme.colors.warning
    StatusLevel.Critical -> MaterialTheme.colorScheme.error
    StatusLevel.Off -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Ícone do nível (formas diferentes além da cor: círculo com check, triângulo, erro, traço). */
fun StatusLevel.icon(): ImageVector = when (this) {
    StatusLevel.Ok -> Icons.Filled.CheckCircle
    StatusLevel.Warning -> Icons.Filled.Warning
    StatusLevel.Critical -> Icons.Filled.Error
    StatusLevel.Off -> Icons.Filled.RemoveCircleOutline
}

/**
 * Linha de "estado do sistema": ícone do assunto (neutro) + rótulo + valor + selo de nível
 * (ícone colorido). Lida como uma frase única pelo TalkBack ("Bateria, 82%, ok").
 */
@Composable
fun StatusRow(
    icon: ImageVector,
    label: String,
    value: String,
    level: StatusLevel,
    levelDescription: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    monoValue: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = BdsmTheme.spacing.touchTarget)
            .padding(horizontal = BdsmTheme.spacing.lg, vertical = BdsmTheme.spacing.sm)
            .semantics(mergeDescendants = true) {
                stateDescription = levelDescription
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (level == StatusLevel.Off) MaterialTheme.colorScheme.onSurfaceVariant else level.tint(),
                )
            }
        }
        Text(
            text = value,
            style = if (monoValue) BdsmTheme.type.metric else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = level.icon(),
                contentDescription = null,
                tint = level.tint(),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Campo de busca compacto (alvo >= 48 dp, botão de limpar quando há texto). */
@Composable
fun BdsmSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    clearDescription: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(BdsmTheme.shapes.item)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .heightIn(min = BdsmTheme.spacing.touchTarget)
            .padding(start = BdsmTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(BdsmTheme.spacing.sm))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .semantics { contentDescription = placeholder },
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    inner()
                },
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Filled.Close, contentDescription = clearDescription, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Spacer(modifier = Modifier.width(BdsmTheme.spacing.md))
        }
    }
}

/**
 * Mostra "mensagem + Desfazer". Devolve `true` se o usuário tocou em Desfazer; `false` se o aviso
 * expirou/foi dispensado (ação definitiva). Se a corrotina for cancelada (saiu da tela), a exceção
 * de cancelamento propaga e o chamador decide (use try/finally para efetivar).
 */
suspend fun SnackbarHostState.showUndo(message: String, actionLabel: String): Boolean {
    currentSnackbarData?.dismiss()
    val result = showSnackbar(
        message = message,
        actionLabel = actionLabel,
        withDismissAction = false,
        duration = SnackbarDuration.Long,
    )
    return result == SnackbarResult.ActionPerformed
}

/** Pastilha circular de ícone usada como avatar de linhas de lista (neutra por padrão). */
@Composable
fun CircleIcon(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}
