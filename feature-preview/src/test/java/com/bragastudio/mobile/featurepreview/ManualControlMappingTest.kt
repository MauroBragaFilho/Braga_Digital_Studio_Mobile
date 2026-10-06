package com.bragastudio.mobile.featurepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManualControlMappingTest {

    @Test
    fun shutterLabel_fractions() {
        assertEquals(1_000_000L, shutterLabelToNanosLocal("1/1000"))
        assertEquals(8_000_000L, shutterLabelToNanosLocal("1/125"))
    }

    @Test
    fun shutterLabel_wholeSeconds() {
        assertEquals(1_000_000_000L, shutterLabelToNanosLocal("1\""))
    }

    @Test
    fun shutterLabel_autoAndInvalidAreNull() {
        assertNull(shutterLabelToNanosLocal("AUTO"))
        assertNull(shutterLabelToNanosLocal("abc"))
        assertNull(shutterLabelToNanosLocal("1/0"))
        assertNull(shutterLabelToNanosLocal("1/2/3"))
    }

    @Test
    fun shutterNanosToLabel_roundsToNiceDenominators() {
        assertEquals("AUTO", nativeShutterNanosToLabel(null))
        assertEquals("AUTO", nativeShutterNanosToLabel(0L))
        assertEquals("1/60", nativeShutterNanosToLabel(16_666_666L))
        assertEquals("1/125", nativeShutterNanosToLabel(8_000_000L))
        assertEquals("1/1000", nativeShutterNanosToLabel(1_000_000L))
        assertEquals("1\"", nativeShutterNanosToLabel(1_000_000_000L))
    }

    @Test
    fun shutter_roundTripForListedOptions() {
        for (label in listOf("1/1000", "1/500", "1/250", "1/125", "1/60", "1/30", "1/15", "1\"")) {
            val nanos = shutterLabelToNanosLocal(label)
            assertEquals(label, nativeShutterNanosToLabel(nanos))
        }
    }

    @Test
    fun focusLabels() {
        assertNull(focusLabelToDiopter("AUTO"))
        assertEquals(0f, focusLabelToDiopter("∞")!!, 0f)
        assertEquals(1f, focusLabelToDiopter("1m")!!, 0f)
        assertEquals(2f, focusLabelToDiopter("0.5m")!!, 0f)
        assertNull(focusLabelToDiopter("qualquer"))
    }

    @Test
    fun focusDiopterToLabel() {
        assertEquals("AUTO", nativeFocusDiopterToLabel(null))
        assertEquals("∞", nativeFocusDiopterToLabel(0f))
        assertEquals("1m", nativeFocusDiopterToLabel(1f))
        assertEquals("2m", nativeFocusDiopterToLabel(0.5f))
        assertEquals("0.5m", nativeFocusDiopterToLabel(2f))
    }
}
