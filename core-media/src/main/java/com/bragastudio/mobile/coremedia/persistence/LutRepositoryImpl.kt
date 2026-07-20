package com.bragastudio.mobile.coremedia.persistence

import android.content.Context
import com.bragastudio.mobile.core.model.Lut
import com.bragastudio.mobile.core.repository.LutRepository
import com.bragastudio.mobile.core.database.LutEntity
import com.bragastudio.mobile.core.database.LutDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LutRepositoryImpl @Inject constructor(
    private val lutDao: LutDao,
    @ApplicationContext private val context: Context
) : LutRepository {

    override fun getAllLuts(): Flow<List<Lut>> {
        return lutDao.getAllLuts().map { entities -> entities.map { it.toLut() } }
    }

    override fun getBuiltInLuts(): Flow<List<Lut>> {
        return lutDao.getBuiltInLuts().map { entities -> entities.map { it.toLut() } }
    }

    override fun getUserImportedLuts(): Flow<List<Lut>> {
        return lutDao.getUserImportedLuts().map { entities -> entities.map { it.toLut() } }
    }

    override suspend fun getLutById(id: String): Lut? {
        return lutDao.getLutById(id)?.toLut()
    }

    override suspend fun insertLut(lut: Lut, file: File) {
        lutDao.insertLut(lut.toEntity())
    }

    override suspend fun updateLut(lut: Lut) {
        lutDao.updateLut(lut.toEntity())
    }

    override suspend fun deleteLut(lutId: String) {
        val lut = lutDao.getLutById(lutId)
        if (lut != null) {
            if (!lut.isBuiltIn) {
                val file = File(lut.filePath)
                if (file.exists()) {
                    file.delete()
                }
            }
            lutDao.deleteLutById(lutId)
        }
    }

    override suspend fun setActiveLut(lutId: String?) {
        lutDao.clearActiveLut()
        if (lutId != null) {
            lutDao.setActiveLut(lutId)
        }
    }

    override fun getActiveLut(): Flow<Lut?> {
        return lutDao.getActiveLut().map { it?.toLut() }
    }

    override suspend fun validateLutFile(file: File): Boolean {
        return file.exists() && file.extension.equals("cube", ignoreCase = true)
    }

    override fun generateUniqueId(fileName: String): String {
        return UUID.randomUUID().toString()
    }

    override fun generateDisplayName(fileName: String): String {
        return fileName.removeSuffix(".cube").replace("_", " ")
    }
    
    override suspend fun syncLutsFromDisk() = withContext(Dispatchers.IO) {
        // Sync Assets (Built-in)
        val assetsDir = "luts"
        val assetLuts = try {
            context.assets.list(assetsDir)?.filter { it.endsWith(".cube", ignoreCase = true) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        
        // Sync User files
        val appLutDir = File(context.getExternalFilesDir(null), "luts")
        if (!appLutDir.exists()) appLutDir.mkdirs()
        
        val physicalFiles = appLutDir.listFiles { file -> file.extension.equals("cube", ignoreCase = true) }?.toList() ?: emptyList()
        val physicalFileNames = physicalFiles.map { it.name }
        
        val dbLuts = lutDao.getAllLutsSync()
        
        // Remove from DB if physical file is missing
        dbLuts.forEach { dbLut ->
            if (dbLut.isBuiltIn) {
                if (!assetLuts.contains(dbLut.fileName)) {
                    lutDao.deleteLutById(dbLut.id)
                }
            } else {
                if (!physicalFileNames.contains(dbLut.fileName)) {
                    lutDao.deleteLutById(dbLut.id)
                }
            }
        }
        
        // Add to DB if physical file exists but not in DB
        val dbFileNames = dbLuts.map { it.fileName }
        
        assetLuts.forEach { assetName ->
            if (!dbFileNames.contains(assetName)) {
                val newLut = LutEntity(
                    id = UUID.randomUUID().toString(),
                    fileName = assetName,
                    displayName = generateDisplayName(assetName),
                    filePath = "luts/$assetName", // Virtual path for assets
                    type = "3D LUT",
                    sizeBytes = 0L, // Might need to check size if needed, but not critical for assets
                    creationDate = System.currentTimeMillis(),
                    modificationDate = System.currentTimeMillis(),
                    isActive = false,
                    isBuiltIn = true
                )
                lutDao.insertLut(newLut)
            }
        }
        
        physicalFiles.forEach { file ->
            if (!dbFileNames.contains(file.name)) {
                val newLut = LutEntity(
                    id = UUID.randomUUID().toString(),
                    fileName = file.name,
                    displayName = generateDisplayName(file.name),
                    filePath = file.absolutePath,
                    type = "3D LUT",
                    sizeBytes = file.length(),
                    creationDate = file.lastModified(),
                    modificationDate = file.lastModified(),
                    isActive = false,
                    isBuiltIn = false
                )
                lutDao.insertLut(newLut)
            }
        }
    }

    private fun LutEntity.toLut() = Lut(
        id = id,
        fileName = fileName,
        displayName = displayName,
        description = description,
        filePath = filePath,
        type = type,
        sizeBytes = sizeBytes,
        creationDate = creationDate,
        modificationDate = modificationDate,
        isActive = isActive,
        isBuiltIn = isBuiltIn,
        category = category,
        author = author,
        version = version,
        checksumSha256 = checksumSha256
    )

    private fun Lut.toEntity() = LutEntity(
        id = id,
        fileName = fileName,
        displayName = displayName,
        description = description,
        filePath = filePath,
        type = type,
        sizeBytes = sizeBytes,
        creationDate = creationDate,
        modificationDate = modificationDate,
        isActive = isActive,
        isBuiltIn = isBuiltIn,
        category = category,
        author = author,
        version = version,
        checksumSha256 = checksumSha256
    )
}
