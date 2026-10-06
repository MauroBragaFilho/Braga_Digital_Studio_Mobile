package com.bragastudio.mobile.core.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingNamingTest {
    @Test
    fun removesForbiddenCharactersAndCollapsesSpaces() {
        assertEquals("Take 1 final", RecordingNaming.sanitizeBaseName("  Take/1:  *final?  "))
    }

    @Test
    fun trimsDotsAndRejectsEmpty() {
        assertEquals("", RecordingNaming.sanitizeBaseName("...  "))
        assertEquals("", RecordingNaming.sanitizeBaseName("///"))
        assertEquals("nome", RecordingNaming.sanitizeBaseName(".nome."))
    }

    @Test
    fun limitsLength() {
        assertEquals(RecordingNaming.MAX_BASE_LENGTH, RecordingNaming.sanitizeBaseName("a".repeat(300)).length)
    }

    @Test
    fun keepsOriginalExtension() {
        assertEquals("Ensaio.mp4", RecordingNaming.fileName("Ensaio", "mp4"))
        assertEquals("Ensaio.mov", RecordingNaming.fileName("Ensaio", ".mov"))
        assertEquals("Ensaio.mp4", RecordingNaming.fileName("Ensaio", ""))
    }
}
