package com.bragastudio.mobile.coremedia.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LutRepositoryNamesTest {

    private val fallback = "fallback.cube"

    @Test
    fun sanitize_descartaDiretoriosETraversal() {
        assertEquals("evil.cube", LutRepositoryImpl.sanitizeFileName("../../evil.cube", fallback))
        assertEquals("evil.cube", LutRepositoryImpl.sanitizeFileName("/etc/evil.cube", fallback))
        assertEquals("evil.cube", LutRepositoryImpl.sanitizeFileName("C:\\Users\\x\\evil.cube", fallback))
        assertEquals("a.cube", LutRepositoryImpl.sanitizeFileName("pasta/sub/a.cube", fallback))
    }

    @Test
    fun sanitize_garanteExtensaoCubeSemDuplicar() {
        assertEquals("look.cube", LutRepositoryImpl.sanitizeFileName("look", fallback))
        assertEquals("look.cube", LutRepositoryImpl.sanitizeFileName("look.CUBE", fallback))
        assertEquals("look.cube", LutRepositoryImpl.sanitizeFileName("look.Cube", fallback))
        assertEquals("look.txt.cube", LutRepositoryImpl.sanitizeFileName("look.txt", fallback))
    }

    @Test
    fun sanitize_substituiCaracteresPerigososELimitaTamanho() {
        assertEquals("a_b_c.cube", LutRepositoryImpl.sanitizeFileName("a:b*c.cube", fallback))
        assertEquals("Cine Vibrant (v2).cube", LutRepositoryImpl.sanitizeFileName("Cine Vibrant (v2).cube", fallback))
        val long = LutRepositoryImpl.sanitizeFileName("x".repeat(500) + ".cube", fallback)
        assertTrue(long.length <= 100)
        assertTrue(long.endsWith(".cube"))
    }

    @Test
    fun sanitize_nomeVazioOuSoPontos_usaFallback() {
        assertEquals(fallback, LutRepositoryImpl.sanitizeFileName(null, fallback))
        assertEquals(fallback, LutRepositoryImpl.sanitizeFileName("", fallback))
        assertEquals(fallback, LutRepositoryImpl.sanitizeFileName("...", fallback))
        assertEquals(fallback, LutRepositoryImpl.sanitizeFileName(".cube", fallback))
        assertFalse(LutRepositoryImpl.sanitizeFileName(".hidden.cube", fallback).startsWith("."))
    }

    @Test
    fun displayName_usaCaminhoRelativo() {
        assertEquals("Cine Vibrant", LutRepositoryImpl.displayNameFromRelativePath("Cine_Vibrant.cube"))
        assertEquals("pasta / Look A", LutRepositoryImpl.displayNameFromRelativePath("pasta/Look_A.cube"))
        assertEquals("pasta / Look A", LutRepositoryImpl.displayNameFromRelativePath("pasta\\Look_A.CUBE"))
    }
}
