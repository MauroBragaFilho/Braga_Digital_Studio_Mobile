package com.bragastudio.mobile.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    @Test
    fun `sistema segue o estado do sistema`() {
        assertTrue(ThemeMode.SYSTEM.resolveDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.resolveDark(systemDark = false))
    }

    @Test
    fun `claro e escuro ignoram o sistema`() {
        assertFalse(ThemeMode.LIGHT.resolveDark(systemDark = true))
        assertTrue(ThemeMode.DARK.resolveDark(systemDark = false))
    }

    @Test
    fun `persistencia por nome faz round trip`() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromName(it.name)) }
    }

    @Test
    fun `valor ausente ou corrompido cai no padrao Sistema`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName("dark"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromName("SEPIA"))
    }
}
