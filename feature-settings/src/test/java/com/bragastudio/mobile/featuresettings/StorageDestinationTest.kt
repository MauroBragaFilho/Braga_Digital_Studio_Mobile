package com.bragastudio.mobile.featuresettings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageDestinationTest {

    @Test
    fun folderWinsWhenUriPresent() {
        assertEquals(
            StorageDestination.FOLDER,
            StorageDestinationRules.resolve("content://tree/x", saveToGallery = true, galleryAutoExport = true),
        )
    }

    @Test
    fun galleryOnlyWhenAutoExportExists() {
        // Sem exportação automática, "salvar na galeria = true" (padrão do domínio)
        // NÃO pode aparecer como destino Galeria: o arquivo fica só no app.
        assertEquals(
            StorageDestination.APP,
            StorageDestinationRules.resolve(null, saveToGallery = true, galleryAutoExport = false),
        )
        assertEquals(
            StorageDestination.GALLERY,
            StorageDestinationRules.resolve(null, saveToGallery = true, galleryAutoExport = true),
        )
        assertEquals(
            StorageDestination.APP,
            StorageDestinationRules.resolve("  ", saveToGallery = false, galleryAutoExport = true),
        )
    }

    @Test
    fun galleryAvailableDependsOnSdk() {
        assertEquals(false, StorageDestinationRules.galleryAvailable(28))
        assertEquals(true, StorageDestinationRules.galleryAvailable(29))
        assertEquals(true, StorageDestinationRules.GALLERY_AUTO_EXPORT_SUPPORTED)
    }

    @Test
    fun persistedMapping() {
        assertEquals(
            StorageDestinationRules.Persisted(null, false),
            StorageDestinationRules.persistedFor(StorageDestination.APP, "ignored"),
        )
        assertEquals(
            StorageDestinationRules.Persisted(null, true),
            StorageDestinationRules.persistedFor(StorageDestination.GALLERY, null),
        )
        assertEquals(
            StorageDestinationRules.Persisted("content://tree/x", false),
            StorageDestinationRules.persistedFor(StorageDestination.FOLDER, "content://tree/x"),
        )
        assertNull(StorageDestinationRules.persistedFor(StorageDestination.APP, "x").directoryUri)
    }

    @Test
    fun describeTreeDocumentId() {
        assertEquals("Armazenamento interno › Movies/BDSM", StorageDestinationRules.describeTreeDocumentId("primary:Movies/BDSM"))
        assertEquals("Armazenamento interno", StorageDestinationRules.describeTreeDocumentId("primary:"))
        assertEquals("Cartão SD / USB (1A2B-3C4D) › Gravacoes", StorageDestinationRules.describeTreeDocumentId("1A2B-3C4D:Gravacoes"))
        assertEquals("Pasta escolhida", StorageDestinationRules.describeTreeDocumentId(null))
        assertEquals("Pasta escolhida", StorageDestinationRules.describeTreeDocumentId(""))
    }
}
