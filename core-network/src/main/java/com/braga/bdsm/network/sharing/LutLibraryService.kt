package com.braga.bdsm.network.sharing

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LutItemDto(
    val id: String, // Can be relative path
    val name: String,
    val relativePath: String,
    val hash: String,
    val sizeBytes: Long
)

@Serializable
data class LutSyncConflict(
    val relativePath: String,
    val existingHash: String,
    val newHash: String
)

@Singleton
class LutLibraryService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val lutsDir by lazy {
        val dir = File(context.getExternalFilesDir(null), "luts")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    fun getLutsList(): List<LutItemDto> {
        val result = mutableListOf<LutItemDto>()
        lutsDir.walkTopDown().forEach { file ->
            if (file.isFile && file.extension.equals("cube", ignoreCase = true)) {
                val relative = file.relativeTo(lutsDir).path
                result.add(
                    LutItemDto(
                        id = relative, // use relative path as unique ID for sync
                        name = file.name,
                        relativePath = relative,
                        hash = computeHash(file),
                        sizeBytes = file.length()
                    )
                )
            }
        }
        return result
    }

    fun deleteLut(relativePath: String): Boolean {
        val file = File(lutsDir, relativePath)
        if (file.exists() && file.isFile) {
            val deleted = file.delete()
            // Try to clean up empty parent directories
            var parent = file.parentFile
            while (parent != null && parent != lutsDir && parent.isDirectory && (parent.listFiles()?.isEmpty() == true)) {
                parent.delete()
                parent = parent.parentFile
            }
            return deleted
        }
        return false
    }

    /**
     * Checks if a LUT already exists and if there is a conflict.
     * Returns null if it's safe to upload, or a LutSyncConflict if a different file exists.
     */
    fun checkConflict(relativePath: String, newHash: String): LutSyncConflict? {
        val file = File(lutsDir, relativePath)
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
        val file = File(lutsDir, relativePath)
        file.parentFile?.mkdirs()
        return try {
            file.writeBytes(content)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    fun getLutFile(relativePath: String): File? {
        val file = File(lutsDir, relativePath)
        return if (file.exists() && file.isFile) file else null
    }

    private fun computeHash(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = file.readBytes()
            digest.update(bytes)
            val hashBytes = digest.digest()
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
}
