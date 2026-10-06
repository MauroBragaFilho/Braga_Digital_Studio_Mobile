package com.bragastudio.mobile.featuresettings

import com.bragastudio.mobile.coremedia.domain.GalleryExportRules

/**
 * Destino da gravação (M44) — um único seletor no lugar de "Local de
 * salvamento" + "Salvar na Galeria" (este último não tinha efeito algum).
 *
 * Mapeamento sobre as duas preferências já existentes (sem mudar o DataStore):
 *  - FOLDER  = recordingDirectoryUri != null  (RecordManager copia para a pasta SAF ao parar)
 *  - GALLERY = saveToGallery == true e sem pasta (cópia no MediaStore ao parar; Android 10+)
 *  - APP     = sem pasta e sem galeria (arquivo só em Android/data/<pacote>/files/Movies)
 */
enum class StorageDestination { APP, GALLERY, FOLDER }

enum class FolderStatus { NONE, OK, UNAVAILABLE }

object StorageDestinationRules {

    /**
     * O RecordManager exporta o take para a Galeria (MediaStoreExporter, em segundo plano)
     * quando o destino é Galeria. A exportação manual em Gravações (GalleryExporter)
     * continua existindo.
     */
    const val GALLERY_AUTO_EXPORT_SUPPORTED = true

    /**
     * "Galeria" só é oferecida onde a exportação automática funciona: Android 10+
     * (em 26–28 o MediaStore exigiria WRITE_EXTERNAL_STORAGE, removida do manifesto).
     * Mesma regra usada pelo RecordManager ao decidir exportar.
     */
    fun galleryAvailable(sdkInt: Int): Boolean = GALLERY_AUTO_EXPORT_SUPPORTED && GalleryExportRules.isAutoExportSupported(sdkInt)

    fun resolve(
        directoryUri: String?,
        saveToGallery: Boolean,
        galleryAutoExport: Boolean = galleryAvailable(android.os.Build.VERSION.SDK_INT),
    ): StorageDestination = when {
        !directoryUri.isNullOrBlank() -> StorageDestination.FOLDER
        galleryAutoExport && saveToGallery -> StorageDestination.GALLERY
        else -> StorageDestination.APP
    }

    /** Valores a gravar (uri, saveToGallery) ao escolher um destino. */
    data class Persisted(val directoryUri: String?, val saveToGallery: Boolean)

    fun persistedFor(destination: StorageDestination, folderUri: String?): Persisted = when (destination) {
        StorageDestination.APP -> Persisted(null, false)
        StorageDestination.GALLERY -> Persisted(null, true)
        StorageDestination.FOLDER -> Persisted(folderUri, false)
    }

    /**
     * Rótulo legível de um document id de árvore SAF ("primary:Movies/BDSM").
     * volume "primary" = armazenamento interno; outros = cartão SD / USB.
     */
    fun describeTreeDocumentId(documentId: String?): String {
        if (documentId.isNullOrBlank()) return "Pasta escolhida"
        val volume = documentId.substringBefore(':', missingDelimiterValue = documentId)
        val path = if (documentId.contains(':')) documentId.substringAfter(':') else ""
        val volumeLabel = if (volume.equals("primary", ignoreCase = true)) "Armazenamento interno" else "Cartão SD / USB ($volume)"
        return if (path.isBlank()) volumeLabel else "$volumeLabel › $path"
    }
}
