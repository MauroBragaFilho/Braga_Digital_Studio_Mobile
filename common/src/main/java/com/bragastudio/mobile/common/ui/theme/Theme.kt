package com.bragastudio.mobile.common.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Tipografia v2: técnica e densa (escala 11/12/13/14/16/18/22/28 sp), fonte do sistema com pesos
 * calibrados; métricas numéricas usam [BdsmType] (monoespaçada). Nenhum texto abaixo de 11 sp.
 */
private val BdsmTypography = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.25).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.15.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.3.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 17.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.8.sp),
)

/** Formas retas/técnicas (raios 2-8 dp). */
private val BdsmMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

/**
 * Tema do app. [darkTheme] já vem resolvido (ver `ThemeMode.resolveDark`); [dynamicColor]
 * (Material You, Android 12+) é opcional e desligado por padrão. Os tokens [BdsmColors]
 * continuam vindo do [appTheme] para manter status/gravação/tally consistentes.
 */
@Composable
fun BragaStudioMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    appTheme: AppTheme = BdsmDefaultTheme,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        appTheme.colorScheme(darkTheme)
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            // Android 15+ (targetSdk 35) forca edge-to-edge e ignora estas cores; so API < 35 as usa.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                window.statusBarColor = Color.Transparent.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = Color.Transparent.toArgb()
            }
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalBdsmColors provides appTheme.tokens(darkTheme),
        LocalBdsmSpacing provides BdsmSpacing(),
        LocalBdsmShapes provides BdsmShapes(),
        LocalBdsmType provides BdsmType(),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BdsmTypography,
            shapes = BdsmMaterialShapes,
            content = content,
        )
    }
}
