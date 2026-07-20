package com.bragastudio.mobile.coremedia.domain

import android.content.Context
import android.net.Uri
import java.io.File

object LutManager {
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
            e.printStackTrace()
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
            // Garante que o nome termine em .cube
            val safeFileName = if (fileName.endsWith(".cube", ignoreCase = true)) fileName else "$fileName.cube"
            val destFile = File(lutDir, safeFileName)
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return destFile.exists() && destFile.length() > 0
        } catch (e: Exception) {
            e.printStackTrace()
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