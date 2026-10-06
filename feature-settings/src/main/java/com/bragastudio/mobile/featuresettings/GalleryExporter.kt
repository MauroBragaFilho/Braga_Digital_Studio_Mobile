package com.bragastudio.mobile.featuresettings

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exporta gravações para a Galeria (MediaStore). Toda a E/S roda em Dispatchers.IO.
 */
object GalleryExporter {

    private const val TAG = "GalleryExporter"
    private const val BUFFER_SIZE = 256 * 1024

    /** Exporta [files] e mostra um Toast ao final ("N de M salvos" ou erro). */
    suspend fun exportAndNotify(context: Context, files: List<File>) {
        val appContext = context.applicationContext
        var saved = 0
        for (file in files) {
            if (exportOne(appContext, file)) saved++
        }
        val message = when {
            files.isEmpty() -> return
            saved == 0 -> "Erro ao salvar na Galeria"
            else -> "$saved de ${files.size} salvos na Galeria"
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Retorna true se salvou com sucesso. Apaga a Uri parcial em erro/cancelamento. */
    suspend fun exportOne(context: Context, file: File): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/BDSM")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        var uri: Uri? = null
        try {
            uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            val out = resolver.openOutputStream(uri)
                ?: throw java.io.IOException("openOutputStream retornou null")
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
            true
        } catch (e: CancellationException) {
            deleteQuietly(context, uri)
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao exportar ${file.name} para a Galeria", e)
            deleteQuietly(context, uri)
            false
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
