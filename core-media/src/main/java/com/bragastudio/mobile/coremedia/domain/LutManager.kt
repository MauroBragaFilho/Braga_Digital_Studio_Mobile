package com.bragastudio.mobile.coremedia.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * Utilitário legado (pasta interna `LUTs`). O fluxo atual de LUTs passa por `LutRepository`
 * (Room + `getExternalFilesDir/luts`); esta classe não é fonte de verdade.
 */
object LutManager {
    private const val TAG = "LutManager"
    private const val LUT_FOLDER_NAME = "LUTs"

    // Retorna uma lista com "Nenhum" + LUTs dos Assets + LUTs salvas no dispositivo
    fun getAvailableLuts(context: Context): List<String> {
        val luts = mutableListOf("Nenhum (Desativado)")

        // 1. LUTs Pré-carregados (Assets) - Lidos dinamicamente da pasta luts
        try {
            val assetLuts = context.assets.list("luts")
            if (assetLuts != null) {
                for (file in assetLuts) {
                    if (file.endsWith(".cube", ignoreCase = true)) {
                        luts.add("[Pré-carregado] $file")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao listar LUTs dos assets", e)
        }

        // 2. LUTs Salvas pelo Usuário
        val lutDir = File(context.filesDir, LUT_FOLDER_NAME)
        if (lutDir.exists() && lutDir.isDirectory) {
            lutDir.listFiles()?.filter { it.extension.equals("cube", ignoreCase = true) }
                ?.forEach { file ->
                    luts.add("[Personalizado] ${file.name}")
                }
        }

        return luts
    }

    // Salva um arquivo .cube enviado pelo usuário na pasta interna do app
    fun saveUploadedLut(context: Context, uri: Uri, fileName: String): Boolean {
        try {
            val lutDir = File(context.filesDir, LUT_FOLDER_NAME).apply { mkdirs() }
            // File(name).name descarta qualquer diretório ("../x.cube" vira "x.cube"); garante .cube
            val baseName = File(fileName).name.ifBlank { "lut" }
            val safeFileName = if (baseName.endsWith(".cube", ignoreCase = true)) baseName else "$baseName.cube"
            val destFile = File(lutDir, safeFileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return destFile.exists() && destFile.length() > 0
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao salvar LUT", e)
            return false
        }
    }

    // Obtém o caminho ou identificador para o parser carregar
    fun getLutIdentifier(context: Context, displayName: String): Pair<Boolean, String> {
        // Retorna: (isAsset, path_or_name)
        if (displayName.startsWith("[Pré-carregado] ")) {
            val fileName = displayName.replace("[Pré-carregado] ", "")
            return Pair(true, "luts/$fileName")
        } else if (displayName.startsWith("[Personalizado] ")) {
            val fileName = displayName.replace("[Personalizado] ", "")
            val file = File(context.filesDir, "$LUT_FOLDER_NAME/$fileName")
            return Pair(false, file.absolutePath)
        }
        return Pair(false, "")
    }
}
