package com.bragastudio.mobile.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete

/**
 * Data Access Object (DAO) para operações com [LutEntity].
 * Define as consultas SQL para interagir com a tabela 'lut_table'.
 */
@Dao
interface LutDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLut(lut: LutEntity)

    @Update
    suspend fun updateLut(lut: LutEntity)

    @Query("DELETE FROM lut_table WHERE id = :id")
    suspend fun deleteLutById(id: String)

    @Query("SELECT * FROM lut_table WHERE id = :id")
    suspend fun getLutById(id: String): LutEntity?

    @Query("SELECT * FROM lut_table ORDER BY modificationDate DESC")
    fun getAllLuts(): kotlinx.coroutines.flow.Flow<List<LutEntity>>

    @Query("SELECT * FROM lut_table ORDER BY modificationDate DESC")
    suspend fun getAllLutsSync(): List<LutEntity>

    @Query("SELECT * FROM lut_table WHERE isBuiltIn = 1 ORDER BY displayName ASC")
    fun getBuiltInLuts(): kotlinx.coroutines.flow.Flow<List<LutEntity>>

    @Query("SELECT * FROM lut_table WHERE isBuiltIn = 0 ORDER BY modificationDate DESC")
    fun getUserImportedLuts(): kotlinx.coroutines.flow.Flow<List<LutEntity>>

    @Query("SELECT * FROM lut_table WHERE isActive = 1 LIMIT 1")
    fun getActiveLut(): kotlinx.coroutines.flow.Flow<LutEntity?>

    @Query("UPDATE lut_table SET isActive = 0 WHERE isActive = 1")
    suspend fun clearActiveLut()

    @Query("UPDATE lut_table SET isActive = 1 WHERE id = :id")
    suspend fun setActiveLut(id: String)
}
