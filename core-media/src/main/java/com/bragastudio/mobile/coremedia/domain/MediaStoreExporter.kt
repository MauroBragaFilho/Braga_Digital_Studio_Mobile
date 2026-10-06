package com.bragastudio.mobile.coremedia.domain

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Regras puras (testáveis na JVM) da exportação para a Galeria: decisão de destino, MIME e
 * nome de exibição. Nada aqui toca em APIs do Android.
 */
object GalleryExportRules {

    /** Subpasta dentro de Movies/ onde as cópias ficam (Android 10+; antes não há RELATIVE_PATH). */
    const val GALLERY_SUBDIR = "BDSM"

    private const val FALLBACK_MIME = "video/mp4"
    private const val Q = 29

    /**
     * A exportação automática só é oferecida no Android 10+: em 26–28 gravar no MediaStore
     * exige WRITE_EXTERNAL_STORAGE, que o manifesto deliberadamente remove.
     */
    fun isAutoExportSupported(sdkInt: Int): Boolean = sdkInt >= Q

    /**
     * Decide se um take recém-finalizado deve ser exportado: a pasta SAF tem precedência
     * (destino único), e o take não pode estar corrompido.
     */
    fun shouldAutoExport(
        hasFolderDestination: Boolean,
        saveToGallery: Boolean,
        corrupt: Boolean,
        sdkInt: Int,
    ): Boolean = !hasFolderDestination && saveToGallery && !corrupt && isAutoExportSupported(sdkInt)

    /**
     * MIME pela extensão. [lookup] costuma ser o MimeTypeMap do sistema; se ele não
     * conhecer a extensão, cai na tabela interna e, por fim, em video/mp4.
     */
    fun mimeFor(fileName: String, lookup: (String) -> String? = { null }): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return FALLBACK_MIME
        lookup(ext)?.takeIf { it.isNotBlank() }?.let { return it }
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "ts" -> "video/mp2t"
            else -> FALLBACK_MIME
        }
    }

    /** Nome exibido na Galeria: só o nome-base do arquivo, sem separadores de caminho. */
    fun displayNameFor(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        return base.ifEmpty { "BDSM_video.mp4" }
    }

    /** Valor de RELATIVE_PATH (Android 10+). */
    fun relativePath(moviesDir: String = "Movies"): String = "$moviesDir/$GALLERY_SUBDIR"
}

/** Resultado da exportação: Uri criada no MediaStore ou o motivo da falha. */
sealed class MediaStoreExportResult {
    data class Success(val uri: Uri) : MediaStoreExportResult()
    data class Failure(val reason: Reason, val message: String) : MediaStoreExportResult()

    enum class Reason { NO_PERMISSION, SOURCE_MISSING, INSERT_FAILED, IO_ERROR }
}

/**
 * Exporta um arquivo de vídeo para a Galeria (MediaStore, Movies/BDSM). Toda a E/S roda em
 * Dispatchers.IO, com buffer de 256 KiB; em erro ou cancelamento a Uri parcial é apagada.
 */
object MediaStoreExporter {

    private const val TAG = "MediaStoreExporter"
    private const val BUFFER_SIZE = 256 * 1024

    suspend fun export(context: Context, file: File): MediaStoreExportResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        if (!file.exists() || file.length() == 0L) {
            return@withContext MediaStoreExportResult.Failure(
                MediaStoreExportResult.Reason.SOURCE_MISSING, "Arquivo de origem inexistente ou vazio",
            )
        }
        // API 26–28: sem a permissão de escrita o insert falha com SecurityException.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext MediaStoreExportResult.Failure(
                MediaStoreExportResult.Reason.NO_PERMISSION,
                "Sem permissão de escrita para salvar na Galeria neste Android",
            )
        }

        val resolver = appContext.contentResolver
        val mime = GalleryExportRules.mimeFor(file.name) { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, GalleryExportRules.displayNameFor(file.name))
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    GalleryExportRules.relativePath(Environment.DIRECTORY_MOVIES),
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        var uri: Uri? = null
        try {
            uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext MediaStoreExportResult.Failure(
                    MediaStoreExportResult.Reason.INSERT_FAILED, "O MediaStore não criou o item",
                )
            val out = resolver.openOutputStream(uri)
                ?: throw IOException("openOutputStream retornou null")
            out.use { output ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            MediaStoreExportResult.Success(uri)
        } catch (e: CancellationException) {
            deleteQuietly(appContext, uri)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao exportar ${file.name} para a Galeria", e)
            deleteQuietly(appContext, uri)
            val reason = if (e is SecurityException) {
                MediaStoreExportResult.Reason.NO_PERMISSION
            } else {
                MediaStoreExportResult.Reason.IO_ERROR
            }
            MediaStoreExportResult.Failure(reason, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun deleteQuietly(context: Context, uri: Uri?) {
        if (uri == null) return
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao remover Uri parcial $uri", e)
        }
    }
}
