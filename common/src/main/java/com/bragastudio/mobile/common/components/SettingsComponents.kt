package com.bragastudio.mobile.common.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bragastudio.mobile.common.R
import com.bragastudio.mobile.common.ui.theme.BdsmTheme

// --- Componentes de UI reutilizáveis (design system v2; todos leem cores/medidas do tema) ---

private const val DISABLED_ALPHA = 0.4f

/** Padding horizontal do conteúdo de uma linha de grupo (16 dp úteis menos o recuo de 4 dp da forma da linha). */
private val GROUP_ROW_PADDING = 12.dp

/** Cartão padrão do app (One UI): contêiner de 26 dp, tonal (sem sombra e sem borda; a separação vem do contraste cartão x fundo). */
@Composable
fun BdsmCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = BdsmTheme.shapes.container,
        color = BdsmTheme.colors.card,
    ) {
        Column(content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BdsmTopBar(
    title: String,
    onNavigateUp: () -> Unit,
    actions: @Composable () -> Unit,
    showBack: Boolean = true,
) {
    val haptics = rememberBdsmHaptics()
    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            if (showBack) {
                IconButton(
                    onClick = {
                        haptics.tick()
                        onNavigateUp()
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.bdsm_back))
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

/**
 * Estrutura base das telas de ajustes/sub-telas: barra superior com "Voltar", conteúdo rolável
 * com largura máxima (tablet/paisagem) e margens de 16 dp, respeitando as barras do sistema.
 */
@Composable
fun BdsmScreen(
    title: String,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    scrollable: Boolean = true,
    showBack: Boolean = true,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { BdsmTopBar(title = title, onNavigateUp = onNavigateUp, actions = actions, showBack = showBack) },
        snackbarHost = snackbarHost,
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = BdsmTheme.spacing.contentMaxWidth)
                    .fillMaxSize()
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = BdsmTheme.spacing.screenMargin)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.xl),
            ) {
                content()
                Spacer(modifier = Modifier.size(BdsmTheme.spacing.xl))
            }
        }
    }
}

/**
 * Variante de [BdsmScreen] para telas longas: o conteúdo é uma `LazyColumn` (só os itens visíveis
 * são compostos e medidos). Cada `item` deve ter `key` e `contentType`.
 */
@Composable
fun BdsmLazyScreen(
    title: String,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    showBack: Boolean = true,
    snackbarHost: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { BdsmTopBar(title = title, onNavigateUp = onNavigateUp, actions = actions, showBack = showBack) },
        snackbarHost = snackbarHost,
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = BdsmTheme.spacing.contentMaxWidth)
                    .fillMaxSize(),
                contentPadding = PaddingValues(
                    start = BdsmTheme.spacing.screenMargin,
                    end = BdsmTheme.spacing.screenMargin,
                    bottom = BdsmTheme.spacing.xl,
                ),
                verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.xl),
            ) {
                content()
                item(key = "bottom-inset", contentType = "inset") {
                    Spacer(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars))
                }
            }
        }
    }
}

/** Seção agrupada (One UI): rótulo de grupo discreto (semântico, ícone opcional) sobre um contêiner único de 26 dp. */
@Composable
fun SettingsSection(
    title: String,
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    BdsmGroupedList(label = title, labelIcon = icon) { content() }
}

/**
 * Linha clicável de configuração. O ícone é decorativo por padrão (o título já
 * descreve a linha); passe [iconContentDescription] quando o ícone carregar
 * significado próprio que não esteja no texto. [value] é o valor atual, mostrado à
 * direita em fonte monoespaçada; [subtitle] é uma explicação opcional abaixo do título.
 */
@Composable
fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    iconColor: Color = BdsmTheme.colors.accentNeutral,
    iconContentDescription: String? = null,
    value: String? = null,
    monoValue: Boolean = false,
    // Seta à direita; use ExpandMore/ExpandLess em linhas que expandem conteúdo na própria tela.
    trailingIcon: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bdsmGroupRow()
            .defaultMinSize(minHeight = BdsmTheme.spacing.touchTarget)
            .bdsmClickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = GROUP_ROW_PADDING, vertical = BdsmTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon = icon, color = iconColor, contentDescription = iconContentDescription)
        Spacer(modifier = Modifier.width(BdsmTheme.spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (value != null) {
            Spacer(modifier = Modifier.width(BdsmTheme.spacing.sm))
            Text(
                text = value,
                style = if (monoValue) BdsmTheme.type.metric else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 168.dp),
            )
        }
        // Seta decorativa, escondida do leitor de tela.
        Icon(
            imageVector = trailingIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = BdsmTheme.spacing.xs)
                .size(20.dp)
                .clearAndSetSemantics { },
        )
    }
}

/** Ícone em pastilha tingida (32 dp, cantos retos), padrão de todas as linhas de ajustes. */
@Composable
fun IconBadge(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(BdsmTheme.shapes.item)
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) color else color.copy(alpha = DISABLED_ALPHA),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** Divisor fino (1 dp), recuado para alinhar com o texto depois do ícone ([BdsmGroupInset]). */
@Composable
fun SettingsDivider(inset: Dp = BdsmGroupInset.Icon) {
    BdsmGroupDivider(inset = inset)
}

/** Texto explicativo pequeno dentro de uma seção (ajuda/aviso). */
@Composable
fun SettingsHelpText(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = BdsmTheme.spacing.lg, vertical = BdsmTheme.spacing.sm),
    )
}

/**
 * Diálogo de escolha única com opções tipadas. [label] converte a opção em
 * texto exibido; a comparação de seleção é por igualdade da própria opção,
 * não por texto — assim renomear um rótulo nunca quebra a lógica.
 */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    enabledOf: (T) -> Boolean = { true },
    // Explicação curta sob o rótulo (ex.: "mais compatível"); null = sem linha extra.
    description: (T) -> String? = { null },
) {
    val haptics = rememberBdsmHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = BdsmTheme.shapes.container,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .selectableGroup()
                    .verticalScroll(rememberScrollState()),
            ) {
                options.forEach { option ->
                    val isEnabled = enabledOf(option)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = BdsmTheme.spacing.touchTarget)
                            .selectable(
                                selected = option == selected,
                                enabled = isEnabled,
                                role = Role.RadioButton,
                                onClick = {
                                    haptics.tick()
                                    onSelect(option)
                                },
                            )
                            .padding(vertical = BdsmTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // onClick = null: a linha inteira trata o clique e o
                        // TalkBack anuncia um único item "selecionado".
                        RadioButton(
                            selected = option == selected,
                            onClick = null,
                            enabled = isEnabled,
                        )
                        Spacer(modifier = Modifier.width(BdsmTheme.spacing.md))
                        Column(modifier = Modifier.alpha(if (isEnabled) 1f else DISABLED_ALPHA)) {
                            Text(
                                text = label(option),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            description(option)?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            BdsmTextButton(onClick = onDismiss) { Text(stringResource(R.string.bdsm_cancel)) }
        },
    )
}

/** Versão baseada em strings (compatibilidade com chamadas existentes). */
@Composable
fun OptionsDialog(
    title: String,
    options: List<String>,
    currentSelection: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ChoiceDialog(
        title = title,
        options = options,
        selected = currentSelection,
        label = { it },
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

/** Diálogo de confirmação para ações destrutivas ou irreversíveis. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelText: String = stringResource(R.string.bdsm_cancel),
    destructive: Boolean = true,
) {
    val haptics = rememberBdsmHaptics()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = BdsmTheme.shapes.container,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            BdsmTextButton(
                onClick = {
                    if (destructive) haptics.reject() else haptics.confirm()
                    onConfirm()
                },
            ) {
                Text(
                    confirmText,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            BdsmTextButton(onClick = onDismiss) { Text(cancelText) }
        },
    )
}

/**
 * Linha com Switch. A linha inteira é um único alvo "toggleable" (papel
 * Switch) e o Switch interno fica sem onCheckedChange para não duplicar o
 * foco do TalkBack. Vibra (ToggleOn/ToggleOff) ao alternar.
 */
@Composable
fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    iconTint: Color = BdsmTheme.colors.accentNeutral,
    // Parâmetros opcionais (com default) para não quebrar chamadas existentes:
    // subtítulo explicativo e estado desabilitado (ex.: HDR depende do codec).
    subtitle: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptics = rememberBdsmHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bdsmGroupRow()
            .defaultMinSize(minHeight = BdsmTheme.spacing.touchTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = {
                    haptics.toggle(it)
                    onCheckedChange(it)
                },
            )
            .padding(vertical = BdsmTheme.spacing.md, horizontal = GROUP_ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon = icon, color = iconTint, enabled = enabled)
        Spacer(modifier = Modifier.width(BdsmTheme.spacing.md))
        Column(modifier = Modifier.weight(1f).alpha(if (enabled) 1f else DISABLED_ALPHA)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(BdsmTheme.spacing.md))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedBorderColor = MaterialTheme.colorScheme.primary,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

/**
 * Seletor segmentado de escolha única (ex.: Sistema | Claro | Escuro). Cada segmento tem
 * alvo >= 48 dp e papel RadioButton; [label] gera o texto de cada opção. A cor do segmento
 * selecionado transiciona suavemente e a troca vibra levemente.
 */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: (T) -> ImageVector? = { null },
) {
    val haptics = rememberBdsmHaptics()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(BdsmTheme.shapes.item)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, BdsmTheme.colors.cardBorder, BdsmTheme.shapes.item)
            .padding(BdsmTheme.spacing.xs)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.xs),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val background by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                animationSpec = tween(durationMillis = 140),
                label = "segmentBackground",
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = BdsmTheme.spacing.touchTarget)
                    .clip(BdsmTheme.shapes.chip)
                    .background(background)
                    .selectable(selected = isSelected, role = Role.RadioButton) {
                        if (!isSelected) haptics.tick()
                        onSelect(option)
                    }
                    .padding(horizontal = BdsmTheme.spacing.sm, vertical = BdsmTheme.spacing.sm),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                // Estado nunca só por cor: a opção selecionada sem ícone próprio ganha um "check".
                val optionIcon = icon(option) ?: if (isSelected) Icons.Filled.Check else null
                if (optionIcon != null) {
                    Icon(optionIcon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(BdsmTheme.spacing.xs))
                }
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Intenção semântica de um [StatusChip]. */
enum class StatusKind { Success, Warning, Error, Info, Neutral }

/** Chip de status (online, gravando, erro...) com LED e cores semânticas de contraste AA. */
@Composable
fun StatusChip(text: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val tokens = BdsmTheme.colors
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (kind) {
        StatusKind.Success -> tokens.successContainer to tokens.onSuccessContainer
        StatusKind.Warning -> tokens.warningContainer to tokens.onWarningContainer
        StatusKind.Error -> scheme.errorContainer to scheme.onErrorContainer
        StatusKind.Info -> scheme.primaryContainer to scheme.onPrimaryContainer
        StatusKind.Neutral -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    val led = when (kind) {
        StatusKind.Success -> tokens.success
        StatusKind.Warning -> tokens.warning
        StatusKind.Error -> scheme.error
        StatusKind.Info -> scheme.primary
        StatusKind.Neutral -> tokens.ledOff
    }
    Row(
        modifier = modifier
            .clip(BdsmTheme.shapes.chip)
            .background(bg)
            .padding(horizontal = BdsmTheme.spacing.sm, vertical = BdsmTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.xs + BdsmTheme.spacing.xxs),
    ) {
        LedDot(color = led, size = 6.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Moldura de ícone dos estados vazio/erro (quadro fino, cantos retos). */
@Composable
private fun StateIcon(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(BdsmTheme.shapes.card)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, BdsmTheme.colors.cardBorder, BdsmTheme.shapes.card),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
    }
}

/** Estado vazio: ícone, título, explicação e ação opcional. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(BdsmTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm, Alignment.CenterVertically),
    ) {
        StateIcon(icon = icon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.size(BdsmTheme.spacing.sm))
            BdsmPrimaryButton(text = actionLabel, onClick = onAction)
        }
    }
}

/** Estado de erro com ação "Tentar novamente" opcional. */
@Composable
fun ErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    retryLabel: String = stringResource(R.string.bdsm_retry),
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(BdsmTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm, Alignment.CenterVertically),
    ) {
        StateIcon(icon = Icons.Filled.ErrorOutline, tint = MaterialTheme.colorScheme.error)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(modifier = Modifier.size(BdsmTheme.spacing.sm))
            BdsmSecondaryButton(text = retryLabel, onClick = onRetry)
        }
    }
}

/** Estado de carregamento centrado, com rótulo opcional (anunciado ao leitor de tela). */
@Composable
fun LoadingState(modifier: Modifier = Modifier, message: String? = null) {
    val description = message ?: stringResource(R.string.bdsm_loading)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(BdsmTheme.spacing.xl)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
