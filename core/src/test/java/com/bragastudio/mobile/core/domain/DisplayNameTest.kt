package com.bragastudio.mobile.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayNameTest {
    @Test
    fun sanitize_removesLineBreaksAndCollapsesSpaces() {
        assertEquals("Maria Silva", DisplayName.sanitize("  Maria \n\t  Silva  "))
        assertEquals("", DisplayName.sanitize(null))
        assertEquals("", DisplayName.sanitize(" \n "))
    }

    @Test
    fun sanitize_limitsLength() {
        val long = "A".repeat(60)
        assertEquals(DisplayName.MAX_LENGTH, DisplayName.sanitize(long).length)
        assertEquals("Ana x", DisplayName.sanitize("Ana" + " ".repeat(40) + "x"))
        assertEquals("Ana", DisplayName.sanitize("Ana" + " ".repeat(40)))
    }

    @Test
    fun resolve_prefersCustomThenDevice() {
        assertEquals("Mauro", DisplayName.resolve("Mauro", "Galaxy A51"))
        assertEquals("Galaxy A51", DisplayName.resolve("", "Galaxy A51"))
        assertEquals("Galaxy A51", DisplayName.resolve("   ", "Galaxy A51"))
    }

    @Test
    fun resolve_genericDeviceNameIsOmitted() {
        assertNull(DisplayName.resolve("", "Android"))
        assertNull(DisplayName.resolve("", " android "))
        assertNull(DisplayName.resolve(null, ""))
    }

    @Test
    fun isGeneric() {
        assertTrue(DisplayName.isGeneric("Android"))
        assertTrue(DisplayName.isGeneric(""))
        assertFalse(DisplayName.isGeneric("Pixel 7"))
    }
}
