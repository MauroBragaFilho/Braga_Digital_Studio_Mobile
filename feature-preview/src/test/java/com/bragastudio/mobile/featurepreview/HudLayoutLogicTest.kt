package com.bragastudio.mobile.featurepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudLayoutLogicTest {
    @Test
    fun dockHideDelay_isShorterWhileRecording() {
        assertEquals(5_000L, dockHideDelayMs(isRecording = false))
        assertEquals(3_000L, dockHideDelayMs(isRecording = true))
    }

    @Test
    fun dock_hidesOnlyAfterDelay_andNeverWithPopover() {
        assertFalse(isDockHidden(4_999, isRecording = false, popoverOpen = false))
        assertTrue(isDockHidden(5_000, isRecording = false, popoverOpen = false))
        assertFalse(isDockHidden(2_999, isRecording = true, popoverOpen = false))
        assertTrue(isDockHidden(3_000, isRecording = true, popoverOpen = false))
        assertFalse(isDockHidden(60_000, isRecording = true, popoverOpen = true))
    }

    @Test
    fun strip_dimsButBlockedByPopover() {
        assertFalse(isStripDimmed(5_999, popoverOpen = false))
        assertTrue(isStripDimmed(6_000, popoverOpen = false))
        assertFalse(isStripDimmed(60_000, popoverOpen = true))
    }

    @Test
    fun formatTile_joinsResolutionFpsCodec() {
        assertEquals("4K · 30 · H.265", formatTileLabel("4K", 30, "H.265"))
    }

    @Test
    fun shutterAngle_matchesCinemaConvention() {
        assertEquals(180, shutterAngleDegrees(16_666_667L, 30))
        assertEquals(360, shutterAngleDegrees(33_333_333L, 30))
        assertNull(shutterAngleDegrees(null, 30))
        assertNull(shutterAngleDegrees(16_666_667L, 0))
        assertNull(shutterAngleDegrees(100_000_000L, 30)) // > 360 graus
    }

    @Test
    fun shutterTile_showsAngleOnlyWhenManualAndKnown() {
        assertEquals("AUTO", shutterTileValue("AUTO", null, 30))
        assertEquals("1/60 · 180°", shutterTileValue("1/60", shutterLabelToNanosLocal("1/60"), 30))
        assertEquals("1/60", shutterTileValue("1/60", null, 30))
    }

    @Test
    fun focusTile_isAfOrMf() {
        assertEquals("AF", focusTileValue("AUTO"))
        assertEquals("MF · 1m", focusTileValue("1m"))
    }

    @Test
    fun valueLabels() {
        assertTrue(isAutoValue("AUTO"))
        assertTrue(isAutoValue("--"))
        assertFalse(isAutoValue("400"))
        assertEquals("ISO: automático", tileSpokenLabel("ISO", "AUTO"))
        assertEquals("ISO: 400", tileSpokenLabel("ISO", "400"))
        assertEquals("f/2.8", apertureTileValue("2.8"))
        assertEquals("--", apertureTileValue(""))
        assertEquals("100%", batteryLabel(140))
    }
}
