package com.bragastudio.mobile.network.sharing

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable

@Serializable
data class LutItemDto(
    val name: String,
    val relativePath: String,
    val size: Long,
    val hash: String,
)

@Serializable
data class LutSyncConflict(
    val relativePath: String,
    val existingHash: String,
    val newHash: String,
)

@Singleton
class LutLibraryService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lutsDir by lazy {
        // Mesmo diretório usado pelo restante do app; sem armazenamento externo cai no interno
        // (nunca num caminho relativo ao diretório de trabalho).
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "luts")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    private val lutsRoot by lazy { lutsDir.canonicalFile }

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Emite após um upload ou exclusão BEM-SUCEDIDO via Link (L3). Quem mantém o índice
     * de LUTs (Room) reage a este evento para sincronizar sem depender de abrir a tela.
     */
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /**
     * Resolve [rel] dentro de `luts/` de forma segura: rejeita caminhos absolutos,
     * `..`, `.`, segmentos vazios, barra invertida, caracteres de controle (inclui NUL)
     * e extensões diferentes de `.cube`. Retorna null se o caminho for inválido.
     */
    fun resolveSafe(rel: String): File? {
        if (rel.isBlank() || rel.length > 255 || rel.startsWith("/")) return null
        if (rel.any { it == '\\' || it.isISOControl() }) return null
        val seg = rel.split('/')
        if (seg.size > 4 || seg.any { it.isEmpty() || it == "." || it == ".." }) return null
        return try {
            val f = File(lutsRoot, rel).canonicalFile
            if (f == lutsRoot || !f.toPath().startsWith(lutsRoot.toPath()) ||
                !f.extension.equals("cube", ignoreCase = true)
            ) {
                return null
            }
            // canonicalFile nem sempre segue symlink de diretório quando o arquivo ainda não existe
            // (ex.: Windows): resolve o ancestral existente mais próximo com toRealPath().
            var existing: File? = f
            while (existing != null && !existing.exists()) existing = existing.parentFile
            val real = existing?.toPath()?.toRealPath() ?: return null
            if (!real.startsWith(lutsRoot.toPath().toRealPath())) return null
            f
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }
    }

    fun getLutsList(): List<LutItemDto> {
        val result = mutableListOf<LutItemDto>()
        lutsDir.walkTopDown().forEach { file ->
            if (file.isFile && file.extension.equals("cube", ignoreCase = true)) {
                val relative = file.relativeTo(lutsDir).path.replace('\\', '/')
                result.add(
                    LutItemDto(
                        name = file.name,
                        relativePath = relative,
                        size = file.length(),
                        hash = computeHash(file),
                    ),
                )
            }
        }
        return result
    }

    fun deleteLut(relativePath: String): Boolean {
        val file = resolveSafe(relativePath) ?: return false
        if (file.exists() && file.isFile) {
            val deleted = file.delete()
            // Limpa pastas vazias que sobraram (sem nunca remover a raiz `luts/`).
            var parent = file.parentFile
            while (parent != null && parent.path != lutsRoot.path && parent.isDirectory && (parent.listFiles()?.isEmpty() == true)) {
                parent.delete()
                parent = parent.parentFile
            }
            if (deleted) _changes.tryEmit(Unit)
            return deleted
        }
        return false
    }

    /**
     * Checks if a LUT already exists and if there is a conflict.
     * Returns null if it's safe to upload, or a LutSyncConflict if a different file exists.
     */
    fun checkConflict(relativePath: String, newHash: String): LutSyncConflict? {
        val file = resolveSafe(relativePath) ?: return null
        if (file.exists() && file.isFile) {
            val existingHash = computeHash(file)
            if (existingHash != newHash) {
                return LutSyncConflict(relativePath, existingHash, newHash)
            }
        }
        return null
    }

    /**
     * Saves the LUT file. Assumes conflict check was already resolved by the client.
     */
    fun saveLut(relativePath: String, content: ByteArray): Boolean {
        val file = resolveSafe(relativePath) ?: return false
        file.parentFile?.mkdirs()
        // Grava num temporário e renomeia: um upload interrompido nunca deixa uma LUT pela metade.
        val tmp = File(file.parentFile, file.name + ".part")
        return try {
            tmp.writeBytes(content)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) throw IOException("rename falhou")
            _changes.tryEmit(Unit)
            true
        } catch (e: Exception) {
            Log.w("LutLibrary", "Falha ao salvar LUT: ${e.message}")
            tmp.delete()
            false
        }
    }

    fun getLutFile(relativePath: String): File? {
        val file = resolveSafe(relativePath) ?: return null
        return if (file.exists() && file.isFile) file else null
    }

    private fun computeHash(file: File): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        ""
    }
}
