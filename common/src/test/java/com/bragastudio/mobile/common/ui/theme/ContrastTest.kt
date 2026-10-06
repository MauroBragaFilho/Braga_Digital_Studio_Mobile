package com.bragastudio.mobile.common.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contraste WCAG 2.x dos tokens de texto (guia One UI: texto pequeno >= 4,5:1). */
class ContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private val darkBackgrounds = listOf(
        "fundo" to Color(0xFF000000),
        "cartao" to Color(0xFF121212),
        "elevado" to Color(0xFF1C1C1C),
        "campo" to Color(0xFF262626),
    )

    @Test
    fun darkPrimaryTextPassesOnEveryDarkSurface() {
        val text = BdsmDefaultTheme.tokens(dark = true).primaryText
        darkBackgrounds.forEach { (name, bg) ->
            assertTrue("primaryText escuro sobre $name = ${contrast(text, bg)}", contrast(text, bg) >= 4.5)
        }
    }

    @Test
    fun brandAccentIsNotUsableAsSmallTextOnCards() {
        // Documenta o motivo do token: o vermelho da marca falha em texto pequeno sobre o cartão escuro.
        assertTrue(contrast(BdsmAccent, Color(0xFF121212)) < 4.5)
    }

    @Test
    fun lightPrimaryTextPassesOnLightSurfaces() {
        val text = BdsmDefaultTheme.tokens(dark = false).primaryText
        val surfaces = listOf(
            Color(0xFFFFFFFF),
            Color(0xFFF1F2F4),
            Color(0xFFEEF0F3),
            Color(0xFFE8EBEF),
            Color(0xFFE1E4E9),
        )
        surfaces.forEach { bg -> assertTrue("primaryText claro sobre $bg = ${contrast(text, bg)}", contrast(text, bg) >= 4.5) }
    }

    @Test
    fun lightBrandAccentPassesOnWhiteAndBackground() {
        val accent = Color(0xFFD50032)
        assertTrue(contrast(accent, Color(0xFFFFFFFF)) >= 4.5)
        assertTrue(contrast(accent, Color(0xFFF1F2F4)) >= 4.5)
    }

    @Test
    fun secondaryTextPassesOnDarkSurfaces() {
        val scheme = BdsmDefaultTheme.colorScheme(dark = true)
        darkBackgrounds.forEach { (name, bg) ->
            assertTrue("onSurfaceVariant sobre $name", contrast(scheme.onSurfaceVariant, bg) >= 4.5)
            assertTrue("onSurface sobre $name", contrast(scheme.onSurface, bg) >= 4.5)
        }
    }
}
