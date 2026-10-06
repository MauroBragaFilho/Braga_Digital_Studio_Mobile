package com.bragastudio.mobile.common.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneUiAppBarTest {
    @Test
    fun expandedFractionFollowsTheOfficialGuide() {
        assertEquals(0.3967f, OneUiAppBar.expandedFraction(tablet = false), 0f)
        assertEquals(0.1878f, OneUiAppBar.expandedFraction(tablet = true), 0f)
    }

    @Test
    fun tabletIsDecidedByTheSmallestSide() {
        assertFalse(OneUiAppBar.isTablet(widthDp = 411, heightDp = 914))
        assertFalse(OneUiAppBar.isTablet(widthDp = 914, heightDp = 411))
        assertTrue(OneUiAppBar.isTablet(widthDp = 800, heightDp = 1280))
        assertTrue(OneUiAppBar.isTablet(widthDp = 1280, heightDp = 800))
    }

    @Test
    fun phoneLandscapeIsAlwaysCompact() {
        assertTrue(OneUiAppBar.alwaysCompact(widthDp = 914, heightDp = 411))
        assertFalse(OneUiAppBar.alwaysCompact(widthDp = 411, heightDp = 914))
        // Tablet na horizontal e multi-janela alta mantêm o título grande (altura > 580 dp).
        assertFalse(OneUiAppBar.alwaysCompact(widthDp = 1280, heightDp = 800))
        assertFalse(OneUiAppBar.alwaysCompact(widthDp = 700, heightDp = 600))
    }

    @Test
    fun expandedHeightIsAPercentageOfTheScreenMinusTheStatusBar() {
        // A51 em retrato: ~914 dp de altura; barra de status ~24 dp.
        val h = OneUiAppBar.expandedHeightDp(screenHeightDp = 914f, tablet = false, topInsetDp = 24f, collapsedDp = 56f)
        assertEquals(914f * 0.3967f - 24f, h, 0.001f)
        // Nunca menor que a barra recolhida (tela muito baixa).
        assertEquals(56f, OneUiAppBar.expandedHeightDp(screenHeightDp = 200f, tablet = false, topInsetDp = 24f, collapsedDp = 56f), 0f)
    }

    @Test
    fun scrollingUpCollapsesAndDownExpandsWithinTheRange() {
        val range = 200f
        assertEquals(0.25f, OneUiAppBar.applyScroll(0f, scrollDeltaPx = -50f, rangePx = range), 1e-6f)
        assertEquals(0.5f, OneUiAppBar.applyScroll(0.75f, scrollDeltaPx = 50f, rangePx = range), 1e-6f)
        // Limites 0..1.
        assertEquals(1f, OneUiAppBar.applyScroll(0.9f, scrollDeltaPx = -500f, rangePx = range), 0f)
        assertEquals(0f, OneUiAppBar.applyScroll(0.1f, scrollDeltaPx = 500f, rangePx = range), 0f)
    }

    @Test
    fun emptyRangeKeepsTheCurrentState() {
        assertEquals(1f, OneUiAppBar.applyScroll(1f, scrollDeltaPx = 40f, rangePx = 0f), 0f)
        assertEquals(0.3f, OneUiAppBar.applyScroll(0.3f, scrollDeltaPx = -40f, rangePx = -10f), 0f)
    }

    @Test
    fun releaseSnapsByThresholdWhenSlow() {
        assertEquals(0f, OneUiAppBar.snapTarget(collapse = 0.49f, velocityPxPerSecond = 0f), 0f)
        assertEquals(1f, OneUiAppBar.snapTarget(collapse = 0.5f, velocityPxPerSecond = 0f), 0f)
        assertEquals(1f, OneUiAppBar.snapTarget(collapse = 0.8f, velocityPxPerSecond = 100f), 0f)
        assertEquals(0f, OneUiAppBar.snapTarget(collapse = 0.2f, velocityPxPerSecond = -100f), 0f)
    }

    @Test
    fun releaseSnapsByDirectionWhenFlung() {
        // Para cima (negativo) recolhe mesmo perto do topo; para baixo expande mesmo quase recolhida.
        assertEquals(1f, OneUiAppBar.snapTarget(collapse = 0.1f, velocityPxPerSecond = -1500f), 0f)
        assertEquals(0f, OneUiAppBar.snapTarget(collapse = 0.9f, velocityPxPerSecond = 1500f), 0f)
    }

    @Test
    fun stateIsClamped() {
        assertEquals(0f, LargeTitleState(-3f).collapse, 0f)
        assertEquals(1f, LargeTitleState(5f).collapse, 0f)
        assertTrue(LargeTitleState(1f).isCollapsed)
        assertFalse(LargeTitleState(0.99f).isCollapsed)
    }
}
