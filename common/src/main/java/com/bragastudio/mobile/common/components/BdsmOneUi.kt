package com.bragastudio.mobile.common.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.bragastudio.mobile.common.R
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// --- One UI (Etapa 2): app bar expansível com título grande e listas agrupadas ---

/**
 * Regras puras (testáveis em JVM) do app bar expansível do guia One UI: dois estados (expandida e
 * recolhida), altura expandida proporcional à tela, encaixe (snap) pelo limiar ao soltar o dedo e
 * app bar sempre compacta em celular na horizontal.
 *
 * `collapse` é a fração de recolhimento: 0 = totalmente expandida, 1 = totalmente recolhida.
 */
object OneUiAppBar {
    /** Altura expandida = 39,67% da altura da tela no celular (guia oficial). */
    const val PHONE_EXPANDED_FRACTION = 0.3967f

    /** Altura expandida = 18,78% da altura da tela no tablet (guia oficial). */
    const val TABLET_EXPANDED_FRACTION = 0.1878f

    /** Ao soltar o dedo: abaixo do limiar volta a expandir, a partir dele recolhe. */
    const val SNAP_THRESHOLD = 0.5f

    /** Altura (dp) até a qual a janela não comporta o título grande (celular na horizontal). */
    const val COMPACT_ALWAYS_MAX_HEIGHT_DP = 580

    /** Menor lado (dp) a partir do qual a tela é tratada como tablet. */
    const val TABLET_MIN_SIDE_DP = 600

    /** Velocidade (px/s) a partir da qual um arremesso decide o estado, ignorando o limiar. */
    const val FLING_DECIDES_PX_PER_SECOND = 600f

    /** Altura do app bar recolhido (barra de ferramentas compacta). */
    val CollapsedHeight: Dp = 56.dp

    fun isTablet(widthDp: Int, heightDp: Int): Boolean = min(widthDp, heightDp) >= TABLET_MIN_SIDE_DP

    fun expandedFraction(tablet: Boolean): Float = if (tablet) TABLET_EXPANDED_FRACTION else PHONE_EXPANDED_FRACTION

    /**
     * Em celular na horizontal (largura > altura e altura <= 580 dp) o título grande não se aplica:
     * a barra é SEMPRE compacta. Janelas altas (tablet, multi-janela com altura > 580 dp) mantêm o recurso.
     */
    fun alwaysCompact(widthDp: Int, heightDp: Int): Boolean = widthDp > heightDp && heightDp <= COMPACT_ALWAYS_MAX_HEIGHT_DP

    /**
     * Altura expandida (dp) abaixo da barra de status. Nunca menor que a recolhida; [topInsetDp] é
     * descontado porque o guia mede a proporção sobre a tela inteira.
     */
    fun expandedHeightDp(screenHeightDp: Float, tablet: Boolean, topInsetDp: Float, collapsedDp: Float): Float = max(collapsedDp, screenHeightDp * expandedFraction(tablet) - topInsetDp)

    fun clamp(collapse: Float): Float = collapse.coerceIn(0f, 1f)

    /**
     * Nova fração depois de rolar [scrollDeltaPx] (negativo = conteúdo sobe = recolhe; positivo =
     * conteúdo desce = expande) numa faixa de [rangePx] pixels. Faixa vazia mantém o valor atual.
     */
    fun applyScroll(collapse: Float, scrollDeltaPx: Float, rangePx: Float): Float {
        if (rangePx <= 0f) return clamp(collapse)
        return clamp(collapse - scrollDeltaPx / rangePx)
    }

    /**
     * Estado final (0 ou 1) ao soltar o dedo. Um arremesso rápido decide pela direção (para cima
     * recolhe, para baixo expande); senão vale o limiar [SNAP_THRESHOLD]. [velocityPxPerSecond]
     * segue o sinal do eixo Y da tela (negativo = para cima).
     */
    fun snapTarget(collapse: Float, velocityPxPerSecond: Float): Float = when {
        velocityPxPerSecond <= -FLING_DECIDES_PX_PER_SECOND -> 1f
        velocityPxPerSecond >= FLING_DECIDES_PX_PER_SECOND -> 0f
        collapse >= SNAP_THRESHOLD -> 1f
        else -> 0f
    }
}

/** Estado do app bar expansível; [collapse] é lido só nas fases de layout/desenho (sem recompor a lista). */
@Stable
class LargeTitleState(initialCollapse: Float = 0f) {
    var collapse by mutableFloatStateOf(OneUiAppBar.clamp(initialCollapse))
        internal set

    val isCollapsed: Boolean get() = collapse >= 1f

    /** Anima até o estado expandido (ex.: ao tocar na busca). */
    suspend fun expand() = animateTo(0f)

    /** Anima até o estado recolhido. */
    suspend fun collapseNow() = animateTo(1f)

    /** Encaixa em um dos dois estados depois de soltar o dedo. */
    internal suspend fun settle(velocityPxPerSecond: Float) {
        val current = collapse
        if (current > 0f && current < 1f) animateTo(OneUiAppBar.snapTarget(current, velocityPxPerSecond))
    }

    private suspend fun animateTo(target: Float) {
        if (collapse == target) return
        animate(initialValue = collapse, targetValue = target, animationSpec = tween(durationMillis = SNAP_MS)) { value, _ ->
            collapse = value
        }
    }

    companion object {
        private const val SNAP_MS = 200
        val Saver: Saver<LargeTitleState, Float> = Saver(save = { it.collapse }, restore = { LargeTitleState(it) })
    }
}

/** Lembra o estado do app bar (sobrevive à rotação); subtelas começam recolhidas ([startCollapsed]). */
@Composable
fun rememberLargeTitleState(startCollapsed: Boolean = false): LargeTitleState = rememberSaveable(saver = LargeTitleState.Saver) { LargeTitleState(if (startCollapsed) 1f else 0f) }

private class LargeTitleConnection(
    private val state: LargeTitleState,
    private val rangePx: () -> Float,
) : NestedScrollConnection {
    // Rolar para cima: o app bar recolhe ANTES de a lista andar.
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val range = rangePx()
        if (range <= 0f || available.y >= 0f || state.collapse >= 1f) return Offset.Zero
        val before = state.collapse
        val after = OneUiAppBar.applyScroll(before, available.y, range)
        state.collapse = after
        return Offset(0f, -(after - before) * range)
    }

    // Rolar para baixo: só expande o que sobrou depois de a lista chegar ao topo.
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val range = rangePx()
        if (range <= 0f || available.y <= 0f || state.collapse <= 0f) return Offset.Zero
        val before = state.collapse
        val after = OneUiAppBar.applyScroll(before, available.y, range)
        state.collapse = after
        return Offset(0f, -(after - before) * range)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        state.settle(consumed.y + available.y)
        return Velocity.Zero
    }
}

/**
 * Tela com app bar expansível (guia One UI), usada em Ajustes. Dois estados: expandida (título
 * grande centralizado, 39,67% da altura da tela no celular e 18,78% no tablet) e recolhida (barra
 * compacta com [collapsedTitle]). A barra acompanha o dedo e ENCAIXA em um dos dois estados ao soltar.
 * Rolar para cima recolhe; rolar para baixo no topo da lista expande. Em celular na horizontal a barra
 * é sempre compacta. [startCollapsed] vale para subtelas. Use no máximo 3 [actions] (ícones).
 *
 * Desempenho: a fração de recolhimento é lida só em `layout`/`graphicsLayer` (nada de recomposição
 * da lista por quadro); a animação mexe apenas na altura do cabeçalho e no deslocamento/opacidade do título.
 */
@Composable
fun BdsmLargeTitleScaffold(
    title: String,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    collapsedTitle: String = title,
    showBack: Boolean = true,
    startCollapsed: Boolean = false,
    state: LargeTitleState = rememberLargeTitleState(startCollapsed),
    listState: LazyListState = rememberLazyListState(),
    maxContentWidth: Dp = BdsmTheme.spacing.contentMaxWidth,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val widthDp = configuration.screenWidthDp
    val heightDp = configuration.screenHeightDp
    val compactAlways = OneUiAppBar.alwaysCompact(widthDp, heightDp)
    val tablet = OneUiAppBar.isTablet(widthDp, heightDp)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = snackbarHost,
    ) { padding ->
        val collapsedPx = with(density) { OneUiAppBar.CollapsedHeight.toPx() }
        val expandedPx = with(density) {
            OneUiAppBar.expandedHeightDp(
                screenHeightDp = heightDp.toFloat(),
                tablet = tablet,
                topInsetDp = padding.calculateTopPadding().value,
                collapsedDp = OneUiAppBar.CollapsedHeight.value,
            ).dp.toPx()
        }
        val rangePx = if (compactAlways) 0f else expandedPx - collapsedPx
        val currentRange by rememberUpdatedState(rangePx)
        val connection = remember(state) { LargeTitleConnection(state) { currentRange } }

        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = maxContentWidth)
                    .fillMaxSize()
                    .nestedScroll(connection),
            ) {
                LargeTitleHeader(
                    state = state,
                    compactAlways = compactAlways,
                    expandedPx = expandedPx,
                    collapsedPx = collapsedPx,
                    title = title,
                    collapsedTitle = collapsedTitle,
                    showBack = showBack,
                    onNavigateUp = onNavigateUp,
                    actions = actions,
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(
                        start = BdsmTheme.spacing.screenMargin,
                        end = BdsmTheme.spacing.screenMargin,
                        top = BdsmTheme.spacing.sm,
                        bottom = BdsmTheme.spacing.xl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.groupGap),
                ) {
                    content()
                    item(key = "bottom-inset", contentType = "inset") {
                        Spacer(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars))
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("LongParameterList", "LongMethod")
private fun LargeTitleHeader(
    state: LargeTitleState,
    compactAlways: Boolean,
    expandedPx: Float,
    collapsedPx: Float,
    title: String,
    collapsedTitle: String,
    showBack: Boolean,
    onNavigateUp: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val haptics = rememberBdsmHaptics()
    val backDescription = stringResource(R.string.bdsm_back)

    // Lido apenas em layout/graphicsLayer: mudar a fração NÃO recompõe este composable.
    fun fraction(): Float = if (compactAlways) 1f else state.collapse

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val height = (expandedPx + (collapsedPx - expandedPx) * fraction()).roundToInt()
                val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, height))
                layout(placeable.width, height) { placeable.place(0, 0) }
            },
    ) {
        if (!compactAlways) {
            // Título grande centralizado no espaço abaixo da barra; some na primeira metade do recolhimento.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = OneUiAppBar.CollapsedHeight, start = BdsmTheme.spacing.screenMargin, end = BdsmTheme.spacing.screenMargin)
                    .graphicsLayer { alpha = (1f - fraction() * 2f).coerceIn(0f, 1f) }
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    style = BdsmTheme.type.screenTitle,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(OneUiAppBar.CollapsedHeight)
                .padding(end = BdsmTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                IconButton(
                    onClick = {
                        haptics.tick()
                        onNavigateUp()
                    },
                    modifier = Modifier.padding(start = BdsmTheme.spacing.xs),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = backDescription)
                }
            } else {
                Spacer(Modifier.width(BdsmTheme.spacing.screenMargin))
            }
            Text(
                text = collapsedTitle,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (showBack) BdsmTheme.spacing.sm else 0.dp)
                    // Título semântico (cabeçalho) para o TalkBack; o título grande é só visual.
                    .semantics { heading() }
                    .graphicsLayer { alpha = ((fraction() - 0.5f) * 2f).coerceIn(0f, 1f) },
            )
            actions()
        }
    }
}

/** Recuos padrão dos divisores de um grupo (alinham o traço ao texto, depois do ícone). */
object BdsmGroupInset {
    /** Linha com ícone de 32 dp (`IconBadge`): 16 + 32 + 12. */
    val Icon: Dp = 60.dp

    /** Linha de categoria com círculo de 44 dp: 16 + 44 + 16. */
    val Category: Dp = 76.dp

    /** Linha sem ícone (só texto). */
    val Text: Dp = 16.dp
}

/** Rótulo de grupo do One UI: pequeno (12 sp), discreto, com semântica de título. */
@Composable
fun BdsmGroupLabel(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier = modifier
            .padding(start = BdsmTheme.spacing.lg, end = BdsmTheme.spacing.lg, bottom = BdsmTheme.spacing.sm)
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
        Text(
            text = text,
            style = BdsmTheme.type.groupLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Grupo do One UI: UM contêiner único arredondado (26 dp) com as linhas dentro, separadas por
 * divisores recuados ([SettingsDivider] com [BdsmGroupInset]); [label] opcional acima. O espaço de
 * 24 dp entre grupos vem do `verticalArrangement` da lista (`BdsmTheme.spacing.groupGap`).
 * Tonal (sem sombra nem borda): a separação vem do contraste cartão x fundo.
 */
@Composable
fun BdsmGroupedList(
    modifier: Modifier = Modifier,
    label: String? = null,
    labelIcon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (label != null) BdsmGroupLabel(text = label, icon = labelIcon)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(BdsmTheme.shapes.container)
                .background(BdsmTheme.colors.card),
        ) {
            content()
        }
    }
}

/** Divisor de grupo com o recuo escolhido (por padrão, o de linha com ícone). */
@Composable
fun BdsmGroupDivider(inset: Dp = BdsmGroupInset.Icon) {
    HorizontalDivider(
        color = BdsmTheme.colors.divider,
        thickness = 1.dp,
        modifier = Modifier.padding(start = inset),
    )
}

/**
 * Forma de uma linha dentro de um grupo: recuo lateral de 4 dp e cantos de 16 dp para o destaque
 * de toque/ripple (o contêiner externo continua com 26 dp). Aplique ANTES do clique.
 */
@Composable
fun Modifier.bdsmGroupRow(): Modifier = this
    .padding(horizontal = GROUP_ROW_INSET)
    .clip(BdsmTheme.shapes.item)

/** Recuo lateral das linhas do grupo (o conteúdo compensa com padding de 12 dp para manter 16 dp úteis). */
internal val GROUP_ROW_INSET: Dp = 4.dp
