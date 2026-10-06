package com.bragastudio.mobile.core.recording

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Tipo da cópia exportada de uma gravação, decidido pela authority da content URI.
 * Cópias da Galeria usam o MediaStore (authority "media"); cópias SAF usam um DocumentsProvider.
 */
enum class ExportedCopyKind {
    MEDIA_STORE,
    DOCUMENT,
    INVALID,
    ;

    companion object {
        private const val MEDIA_AUTHORITY = "media"
        private const val CONTENT_PREFIX = "content://"

        /** Decisão pura (sem Android) a partir da string da URI. */
        fun fromUriString(uri: String?): ExportedCopyKind {
            if (uri.isNullOrBlank() || !uri.startsWith(CONTENT_PREFIX)) return INVALID
            val authority = uri.removePrefix(CONTENT_PREFIX).substringBefore('/').substringBefore('?').substringAfter('@')
            return when {
                authority.isBlank() -> INVALID
                authority == MEDIA_AUTHORITY -> MEDIA_STORE
                else -> DOCUMENT
            }
        }
    }
}

/** Operações sobre a cópia exportada, escolhendo a API correta por authority (MediaStore x SAF). */
object ExportedCopy {
    /** Apaga a cópia. Lança exceção em falha de I/O/permissão; devolve false se nada foi apagado. */
    fun delete(resolver: ContentResolver, uriString: String): Boolean {
        val uri = Uri.parse(uriString)
        return when (ExportedCopyKind.fromUriString(uriString)) {
            ExportedCopyKind.MEDIA_STORE -> resolver.delete(uri, null, null) > 0
            ExportedCopyKind.DOCUMENT -> DocumentsContract.deleteDocument(resolver, uri)
            ExportedCopyKind.INVALID -> false
        }
    }

    /** true se a cópia ainda existe (MediaStore ou SAF). */
    fun exists(resolver: ContentResolver, uriString: String?): Boolean {
        val kind = ExportedCopyKind.fromUriString(uriString)
        if (kind == ExportedCopyKind.INVALID) return false
        val column = if (kind == ExportedCopyKind.MEDIA_STORE) {
            android.provider.MediaStore.MediaColumns._ID
        } else {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID
        }
        return try {
            resolver.query(Uri.parse(uriString), arrayOf(column), null, null, null)?.use { it.moveToFirst() } == true
        } catch (e: Exception) {
            false
        }
    }
}
