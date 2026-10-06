package com.bragastudio.mobile.featuresettings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile

/**
 * Acesso à pasta de gravação via SAF (M44). Toda chamada é segura contra
 * exceções (URI revogada, provedor removido, cartão SD ejetado). Funções
 * bloqueantes: chame em Dispatchers.IO.
 */
object StorageAccess {
    private const val TAG = "StorageAccess"
    private const val RW_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** Persiste a permissão da pasta. false se o provedor não permitir (nunca lança). */
    fun takePersistable(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(uri, RW_FLAGS)
        true
    } catch (e: Exception) {
        Log.w(TAG, "takePersistableUriPermission falhou para $uri", e)
        false
    }

    /** Libera a permissão persistida de UMA uri (a antiga), sem tocar nas demais. */
    fun release(context: Context, uriString: String) {
        try {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(uriString), RW_FLAGS)
        } catch (e: Exception) {
            // Já não estava concedida ou provedor ausente: nada a fazer.
            Log.w(TAG, "releasePersistableUriPermission falhou para $uriString", e)
        }
    }

    /** A pasta existe, ainda temos permissão persistida de escrita e canWrite() é verdadeiro? */
    fun isWritable(context: Context, uriString: String): Boolean = try {
        val uri = Uri.parse(uriString)
        val hasGrant = context.contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isWritePermission }
        val doc = DocumentFile.fromTreeUri(context, uri)
        hasGrant && doc != null && doc.exists() && doc.canWrite()
    } catch (e: Exception) {
        Log.w(TAG, "Falha ao validar pasta $uriString", e)
        false
    }

    /** Rótulo legível do destino real, ex.: "Armazenamento interno › Movies/BDSM". */
    fun describeFolder(uriString: String): String = try {
        StorageDestinationRules.describeTreeDocumentId(
            DocumentsContract.getTreeDocumentId(Uri.parse(uriString)),
        )
    } catch (_: Exception) {
        "Pasta escolhida"
    }

    /**
     * Diretório privado onde o RecordManager SEMPRE grava o arquivo primeiro
     * (getExternalFilesDir(Movies)). É apagado ao desinstalar o app.
     */
    fun appDirLabel(context: Context): String {
        val dir = try {
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)?.absolutePath
        } catch (_: Exception) {
            null
        }
        return dir?.substringAfter("/Android/", missingDelimiterValue = "")
            ?.takeIf { it.isNotEmpty() }
            ?.let { "Android/$it" }
            ?: "Android/data/${context.packageName}/files/Movies"
    }
}
