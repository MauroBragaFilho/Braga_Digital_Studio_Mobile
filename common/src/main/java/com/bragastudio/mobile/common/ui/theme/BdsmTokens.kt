package com.bragastudio.mobile.common.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Tokens semânticos de cor que o [androidx.compose.material3.ColorScheme] não cobre
 * (status, gravação, tally, acentos de categoria e cartões). Cada [AppTheme] fornece
 * uma instância clara e uma escura; as telas só leem [BdsmTheme.colors].
 */
@Immutable
data class BdsmColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    /**
     * Vermelho de destaque para TEXTO pequeno (TextButton, links, rótulos). O `primary` continua
     * sendo o vermelho da marca para ícones/preenchimentos; este tom é mais claro no escuro (e um
     * pouco mais fechado no claro) para atingir 4,5:1 sobre preto, cartão e campos.
     */
    val primaryText: Color,
    val recording: Color,
    val tallyProgram: Color,
    val tallyPreview: Color,
    /** Fundo de cartões/seções agrupadas e sua borda. */
    val card: Color,
    val cardBorder: Color,
    /** Superfície um nível acima de [card] (elevação por tonalidade, sem sombra). */
    val raised: Color,
    /** Divisor fino (1 dp) entre linhas de um cartão. */
    val divider: Color,
    /** LED apagado (indicador sem sinal). */
    val ledOff: Color,
    /** Acentos de ícone por categoria (contraste não-textual >= 3:1 sobre [card]). */
    val accentVideo: Color,
    val accentAudio: Color,
    val accentStorage: Color,
    val accentMonitor: Color,
    val accentNetwork: Color,
    val accentLibrary: Color,
    val accentSystem: Color,
    val accentNeutral: Color,
)

@Immutable
data class BdsmSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    /** Margem lateral padrão das telas (mínimo oficial One UI), dentro da área segura. */
    val screenMargin: Dp = 24.dp,
    /** Espaço entre grupos de conteúdo. */
    val groupGap: Dp = 24.dp,
    /** Alvo mínimo de toque (Material/WCAG). */
    val touchTarget: Dp = 48.dp,
    /** Largura máxima de conteúdo em telas largas (tablet/paisagem). */
    val contentMaxWidth: Dp = 720.dp,
)

@Immutable
data class BdsmShapes(
    /** Contêineres grandes: diálogos, folhas e cartões principais (guia One UI: 26 dp). */
    val container: RoundedCornerShape = RoundedCornerShape(26.dp),
    /** Cartões intermediários, botões grandes e blocos de ícone (20 dp). */
    val card: RoundedCornerShape = RoundedCornerShape(20.dp),
    /** Itens/linhas dentro de contêineres, campos de texto (16 dp). */
    val item: RoundedCornerShape = RoundedCornerShape(16.dp),
    /** Chips, etiquetas e miniaturas (12 dp). */
    val chip: RoundedCornerShape = RoundedCornerShape(12.dp),
    val pill: RoundedCornerShape = RoundedCornerShape(percent = 50),
)

/**
 * Estilos tipográficos extras do design system v2. Métricas (tempo, bitrate, fps, GB, IP) usam
 * fonte monoespaçada com numerais tabulares, para que os valores não "dancem" ao atualizar.
 */
@Immutable
data class BdsmType(
    val metricLarge: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontFeatureSettings = "tnum",
    ),
    val metric: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontFeatureSettings = "tnum",
    ),
    val metricSmall: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        fontFeatureSettings = "tnum",
    ),
    /** Título grande de tela (só Ajustes; Etapa 2). */
    val screenTitle: TextStyle = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.sp,
    ),
    /** Rótulo de grupo: pequeno, cor secundária aplicada pelo chamador, sem caixa alta forçada. */
    val groupLabel: TextStyle = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
    ),
    /** Rótulo de seção/campo (11 sp, semibold; sem caixa alta, conforme o guia). */
    val overline: TextStyle = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp,
    ),
)

val LocalBdsmColors = staticCompositionLocalOf<BdsmColors> { error("BragaStudioMobileTheme ausente") }
val LocalBdsmSpacing = staticCompositionLocalOf { BdsmSpacing() }
val LocalBdsmShapes = staticCompositionLocalOf { BdsmShapes() }
val LocalBdsmType = staticCompositionLocalOf { BdsmType() }

/** Ponto único de acesso aos tokens do tema atual: `BdsmTheme.colors.success`, `BdsmTheme.spacing.lg`. */
object BdsmTheme {
    val colors: BdsmColors
        @Composable @ReadOnlyComposable
        get() = LocalBdsmColors.current
    val spacing: BdsmSpacing
        @Composable @ReadOnlyComposable
        get() = LocalBdsmSpacing.current
    val shapes: BdsmShapes
        @Composable @ReadOnlyComposable
        get() = LocalBdsmShapes.current
    val type: BdsmType
        @Composable @ReadOnlyComposable
        get() = LocalBdsmType.current
}

/** Classes de largura (equivalente leve ao WindowSizeClass: Compact < 600 dp, Medium < 840 dp). */
enum class BdsmWidthClass { Compact, Medium, Expanded }

/**
 * Celular na horizontal: mais largo que alto e altura <= 580 dp (mesmo limiar do app bar compacto).
 * Use para trocar coluna única por conteúdo lado a lado; decisão por dimensões, nunca só orientação.
 */
@Composable
@ReadOnlyComposable
fun bdsmIsShortLandscape(): Boolean {
    val config = LocalConfiguration.current
    return config.screenWidthDp > config.screenHeightDp && config.screenHeightDp <= 580
}

/** Classe de largura atual; reativa a rotação/dobra (a Activity trata `configChanges`). */
@Composable
@ReadOnlyComposable
fun bdsmWidthClass(): BdsmWidthClass {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp < 600 -> BdsmWidthClass.Compact
        widthDp < 840 -> BdsmWidthClass.Medium
        else -> BdsmWidthClass.Expanded
    }
}
