package com.bragastudio.mobile.core

import com.bragastudio.mobile.core.recording.ExportedCopyKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportedCopyKindTest {
    @Test
    fun mediaStore() {
        assertEquals(ExportedCopyKind.MEDIA_STORE, ExportedCopyKind.fromUriString("content://media/external/video/media/42"))
    }

    @Test
    fun saf() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2Fa.mp4"
        assertEquals(ExportedCopyKind.DOCUMENT, ExportedCopyKind.fromUriString(uri))
    }

    @Test
    fun invalidos() {
        assertEquals(ExportedCopyKind.INVALID, ExportedCopyKind.fromUriString(null))
        assertEquals(ExportedCopyKind.INVALID, ExportedCopyKind.fromUriString(""))
        assertEquals(ExportedCopyKind.INVALID, ExportedCopyKind.fromUriString("file:///sdcard/a.mp4"))
        assertEquals(ExportedCopyKind.INVALID, ExportedCopyKind.fromUriString("content:///x"))
    }
}
