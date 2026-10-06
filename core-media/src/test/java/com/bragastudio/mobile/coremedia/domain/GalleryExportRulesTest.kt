package com.bragastudio.mobile.coremedia.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryExportRulesTest {

    @Test
    fun autoExportSomenteDoAndroid10() {
        assertFalse(GalleryExportRules.isAutoExportSupported(26))
        assertFalse(GalleryExportRules.isAutoExportSupported(28))
        assertTrue(GalleryExportRules.isAutoExportSupported(29))
        assertTrue(GalleryExportRules.isAutoExportSupported(34))
    }

    @Test
    fun decisaoDeExportar() {
        assertTrue(GalleryExportRules.shouldAutoExport(false, true, false, 33))
        // pasta SAF tem precedência
        assertFalse(GalleryExportRules.shouldAutoExport(true, true, false, 33))
        // destino não é Galeria
        assertFalse(GalleryExportRules.shouldAutoExport(false, false, false, 33))
        // take corrompido nunca é exportado
        assertFalse(GalleryExportRules.shouldAutoExport(false, true, true, 33))
        // API 26–28: sem permissão de escrita
        assertFalse(GalleryExportRules.shouldAutoExport(false, true, false, 28))
    }

    @Test
    fun mimePelaExtensao() {
        assertEquals("video/mp4", GalleryExportRules.mimeFor("BDSM_20260101_120000_000.mp4"))
        assertEquals("video/quicktime", GalleryExportRules.mimeFor("a.MOV"))
        assertEquals("video/x-matroska", GalleryExportRules.mimeFor("a.mkv"))
        assertEquals("video/mp4", GalleryExportRules.mimeFor("sem_extensao"))
        assertEquals("video/mp4", GalleryExportRules.mimeFor("a.xyz"))
    }

    @Test
    fun mimeDoSistemaTemPrioridade() {
        assertEquals("video/x-custom", GalleryExportRules.mimeFor("a.mp4") { "video/x-custom" })
        // lookup que não conhece a extensão cai na tabela interna
        assertEquals("video/webm", GalleryExportRules.mimeFor("a.webm") { null })
        assertEquals("video/webm", GalleryExportRules.mimeFor("a.webm") { "" })
    }

    @Test
    fun nomeDeExibicao() {
        assertEquals("BDSM_1.mp4", GalleryExportRules.displayNameFor("BDSM_1.mp4"))
        assertEquals("BDSM_1.mp4", GalleryExportRules.displayNameFor("/sdcard/Movies/BDSM_1.mp4"))
        assertEquals("BDSM_1.mp4", GalleryExportRules.displayNameFor("C:\\x\\BDSM_1.mp4"))
        assertEquals("BDSM_video.mp4", GalleryExportRules.displayNameFor("  "))
    }

    @Test
    fun caminhoRelativo() {
        assertEquals("Movies/BDSM", GalleryExportRules.relativePath())
    }
}
