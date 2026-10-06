package com.bragastudio.mobile.featuresettings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSourceLicensesTest {

    @Test
    fun camposNaoVazios() {
        OpenSourceLicenses.all.forEach {
            assertTrue("name vazio", it.name.isNotBlank())
            assertTrue("version vazia em ${it.name}", it.version.isNotBlank())
            assertTrue("license vazia em ${it.name}", it.license.isNotBlank())
            assertTrue("notice vazio em ${it.name}", it.notice.isNotBlank())
            assertTrue("url inválida em ${it.name}", it.url.startsWith("https://"))
        }
    }

    @Test
    fun ordemAlfabetica() {
        val names = OpenSourceLicenses.all.map { it.name }
        assertEquals(names.sortedWith(String.CASE_INSENSITIVE_ORDER), names)
    }

    @Test
    fun semNomesDuplicados() {
        val names = OpenSourceLicenses.all.map { it.name.lowercase() }
        assertEquals(names.distinct(), names)
    }

    @Test
    fun avisosObrigatorios() {
        val byName = OpenSourceLicenses.all.associateBy { it.name }
        assertTrue(byName.getValue("NDI SDK").notice.contains("NDI® is a registered trademark of Vizrt Group"))
        assertEquals("LGPL-2.1", byName.getValue("libusb").license)
        assertTrue(OpenSourceLicenses.LGPL_NOTE.contains("substituí-la"))
        assertTrue(OpenSourceLicenses.LGPL_NOTE.contains("https://"))
    }
}
