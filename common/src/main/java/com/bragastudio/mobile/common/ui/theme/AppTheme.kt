package com.bragastudio.mobile.common.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Um tema do app = par de paletas (clara/escura) + tokens semânticos. Novos temas
 * (ex.: alto contraste, "Estúdio") implementam esta interface e ficam isolados em
 * seu próprio arquivo; o modo claro/escuro é ortogonal e decidido por `ThemeMode`.
 */
interface AppTheme {
    val id: String

    fun colorScheme(dark: Boolean): ColorScheme

    fun tokens(dark: Boolean): BdsmColors

    /** Paleta escura FIXA do Monitor (sobre vídeo); independe do tema escuro do app. */
    fun monitorColorScheme(): ColorScheme = colorScheme(true)

    fun monitorTokens(): BdsmColors = tokens(true)
}

/** Tema padrão BDSM (design system v2 "Pro de vídeo"): escuro profundo, vermelho de destaque, elevação por tonalidade; claro com contraste AA. */
object BdsmDefaultTheme : AppTheme {
    override val id = "bdsm"

    override fun colorScheme(dark: Boolean) = if (dark) DarkScheme else LightScheme

    override fun tokens(dark: Boolean) = if (dark) DarkTokens else LightTokens

    override fun monitorColorScheme() = MonitorScheme

    override fun monitorTokens() = MonitorTokens

    private val DarkScheme = darkColorScheme(
        primary = BdsmAccent,
        onPrimary = Color.White,
        primaryContainer = Color(0xFF7A0029),
        onPrimaryContainer = Color(0xFFFFD9E2),
        secondary = BdsmCyan,
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF004F58),
        onSecondaryContainer = Color(0xFFB8F1FF),
        tertiary = BdsmGreen,
        onTertiary = Color.Black,
        tertiaryContainer = Color(0xFF00522A),
        onTertiaryContainer = Color(0xFFB6F5CF),
        background = BdsmBackground,
        onBackground = BdsmOnBackground,
        surface = BdsmBackground,
        onSurface = BdsmOnSurface,
        surfaceVariant = BdsmSurfaceVariant,
        onSurfaceVariant = Color(0xFFA8A8A8),
        surfaceTint = BdsmAccent,
        inverseSurface = Color(0xFFE6E8EB),
        inverseOnSurface = Color(0xFF14171B),
        inversePrimary = Color(0xFFD50032),
        error = Color(0xFFFF5252),
        onError = Color.Black,
        errorContainer = Color(0xFF7A0A1C),
        onErrorContainer = Color(0xFFFFDAD9),
        outline = Color(0xFF808080),
        outlineVariant = BdsmOutline,
        scrim = Color.Black,
        surfaceBright = Color(0xFF2C2C2C),
        surfaceDim = Color(0xFF000000),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF0A0A0A),
        surfaceContainer = Color(0xFF121212),
        surfaceContainerHigh = BdsmSurfaceVariant,
        surfaceContainerHighest = Color(0xFF262626),
    )

    private val MonitorScheme = darkColorScheme(
        primary = BdsmAccent,
        onPrimary = Color.White,
        primaryContainer = Color(0xFF7A0029),
        onPrimaryContainer = Color(0xFFFFD9E2),
        secondary = BdsmCyan,
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF004F58),
        onSecondaryContainer = Color(0xFFB8F1FF),
        tertiary = BdsmGreen,
        onTertiary = Color.Black,
        tertiaryContainer = Color(0xFF00522A),
        onTertiaryContainer = Color(0xFFB6F5CF),
        background = Color(0xFF08090B),
        onBackground = BdsmOnBackground,
        surface = Color(0xFF0F1114),
        onSurface = BdsmOnSurface,
        surfaceVariant = Color(0xFF171A1F),
        onSurfaceVariant = Color(0xFFA0A7B1),
        surfaceTint = BdsmAccent,
        inverseSurface = Color(0xFFE6E8EB),
        inverseOnSurface = Color(0xFF14171B),
        inversePrimary = Color(0xFFD50032),
        error = Color(0xFFFF5252),
        onError = Color.Black,
        errorContainer = Color(0xFF7A0A1C),
        onErrorContainer = Color(0xFFFFDAD9),
        outline = Color(0xFF7B838E),
        outlineVariant = Color(0xFF232830),
        scrim = Color.Black,
        surfaceBright = Color(0xFF242930),
        surfaceDim = Color(0xFF08090B),
        surfaceContainerLowest = Color(0xFF050608),
        surfaceContainerLow = Color(0xFF0C0E11),
        surfaceContainer = Color(0xFF111419),
        surfaceContainerHigh = Color(0xFF171A1F),
        surfaceContainerHighest = Color(0xFF1E232A),
    )

    private val LightScheme = lightColorScheme(
        primary = Color(0xFFD50032),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFD9E2),
        onPrimaryContainer = Color(0xFF40000F),
        secondary = Color(0xFF00677A),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFB8EEFF),
        onSecondaryContainer = Color(0xFF001F26),
        tertiary = Color(0xFF0F7B3F),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFB6F5CF),
        onTertiaryContainer = Color(0xFF00210F),
        background = Color(0xFFF1F2F4),
        onBackground = Color(0xFF111418),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF111418),
        surfaceVariant = Color(0xFFE4E7EB),
        onSurfaceVariant = Color(0xFF444C57),
        surfaceTint = Color(0xFFD50032),
        inverseSurface = Color(0xFF2B3038),
        inverseOnSurface = Color(0xFFF1F3F7),
        inversePrimary = BdsmAccent,
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF69717D),
        outlineVariant = Color(0xFFD5D9DF),
        scrim = Color.Black,
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFDCDFE4),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF7F8FA),
        surfaceContainer = Color(0xFFEEF0F3),
        surfaceContainerHigh = Color(0xFFE8EBEF),
        surfaceContainerHighest = Color(0xFFE1E4E9),
    )

    private val DarkTokens = BdsmColors(
        success = Color(0xFF00E676),
        onSuccess = Color.Black,
        successContainer = Color(0xFF00522A),
        onSuccessContainer = Color(0xFFB6F5CF),
        warning = Color(0xFFFFC107),
        onWarning = Color.Black,
        warningContainer = Color(0xFF5C4400),
        onWarningContainer = Color(0xFFFFE08A),
        info = Color(0xFF64B5F6),
        primaryText = Color(0xFFFF4D73),
        recording = Color(0xFFFF2D55),
        tallyProgram = Color(0xFFFF2D55),
        tallyPreview = Color(0xFF00E676),
        card = BdsmSurface,
        cardBorder = Color(0xFF2A2A2A),
        raised = Color(0xFF1C1C1C),
        divider = Color(0xFF2A2A2A),
        ledOff = Color(0xFF6E6E6E),
        accentVideo = Color(0xFF5C9DFF),
        accentAudio = Color(0xFF4CD27A),
        accentStorage = Color(0xFF66D9A0),
        accentMonitor = Color(0xFFC77DFF),
        accentNetwork = Color(0xFF26D0E0),
        accentLibrary = Color(0xFFFF8A65),
        accentSystem = Color(0xFFFFB74D),
        accentNeutral = Color(0xFFA8A8A8),
    )

    private val MonitorTokens = BdsmColors(
        success = Color(0xFF00E676),
        onSuccess = Color.Black,
        successContainer = Color(0xFF00522A),
        onSuccessContainer = Color(0xFFB6F5CF),
        warning = Color(0xFFFFC107),
        onWarning = Color.Black,
        warningContainer = Color(0xFF5C4400),
        onWarningContainer = Color(0xFFFFE08A),
        info = Color(0xFF64B5F6),
        primaryText = Color(0xFFFF4D73),
        recording = Color(0xFFFF1744),
        tallyProgram = Color(0xFFFF1744),
        tallyPreview = Color(0xFF00E676),
        card = Color(0xFF0F1114),
        cardBorder = Color(0xFF232830),
        raised = Color(0xFF171B21),
        divider = Color(0xFF1B2027),
        ledOff = Color(0xFF3A414B),
        accentVideo = Color(0xFF5C9DFF),
        accentAudio = Color(0xFF4CD27A),
        accentStorage = Color(0xFF66D9A0),
        accentMonitor = Color(0xFFC77DFF),
        accentNetwork = Color(0xFF26D0E0),
        accentLibrary = Color(0xFFFF8A65),
        accentSystem = Color(0xFFFFB74D),
        accentNeutral = Color(0xFFA0A7B1),
    )

    private val LightTokens = BdsmColors(
        success = Color(0xFF0F7B3F),
        onSuccess = Color.White,
        successContainer = Color(0xFFCDF5DB),
        onSuccessContainer = Color(0xFF00210F),
        warning = Color(0xFF8A5A00),
        onWarning = Color.White,
        warningContainer = Color(0xFFFFE9B0),
        onWarningContainer = Color(0xFF2B1B00),
        info = Color(0xFF0B5ED7),
        primaryText = Color(0xFFC70030),
        recording = Color(0xFFD50032),
        tallyProgram = Color(0xFFD50032),
        tallyPreview = Color(0xFF0F7B3F),
        card = Color.White,
        cardBorder = Color(0xFFD5D9DF),
        raised = Color(0xFFF7F8FA),
        divider = Color(0xFFE4E7EB),
        ledOff = Color(0xFFB4BAC4),
        accentVideo = Color(0xFF0B5ED7),
        accentAudio = Color(0xFF1B7F3B),
        accentStorage = Color(0xFF00796B),
        accentMonitor = Color(0xFF7B1FA2),
        accentNetwork = Color(0xFF00677A),
        accentLibrary = Color(0xFFC2410C),
        accentSystem = Color(0xFF9A5B00),
        accentNeutral = Color(0xFF5C6470),
    )
}
