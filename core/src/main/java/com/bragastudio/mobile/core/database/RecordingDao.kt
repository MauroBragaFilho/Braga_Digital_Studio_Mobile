package com.bragastudio.mobile.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecording(recording: RecordingEntity)

    @Update
    suspend fun updateRecording(recording: RecordingEntity)

    @Query("DELETE FROM recording_table WHERE id = :id")
    suspend fun deleteRecordingById(id: String)

    @Query("SELECT * FROM recording_table ORDER BY createdAt DESC")
    fun getAllRecordings(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recording_table ORDER BY createdAt DESC")
    suspend fun getAllRecordingsSync(): List<RecordingEntity>

    // O termo é escapado no próprio SQL (!, % e _ viram literais, com '!' como ESCAPE) para que
    // buscar "100%" ou "a_b" não case com tudo; quem chama não precisa conhecer o ESCAPE.
    @Query(
        "SELECT * FROM recording_table WHERE " +
            "fileName LIKE '%' || REPLACE(REPLACE(REPLACE(:query, '!', '!!'), '%', '!%'), '_', '!_') || '%' ESCAPE '!' " +
            "OR projectTag LIKE '%' || REPLACE(REPLACE(REPLACE(:query, '!', '!!'), '%', '!%'), '_', '!_') || '%' ESCAPE '!' " +
            "ORDER BY createdAt DESC",
    )
    fun searchRecordings(query: String): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recording_table WHERE isFavorite = 1 ORDER BY createdAt DESC")
    fun getFavoriteRecordings(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recording_table WHERE id = :id")
    suspend fun getRecordingById(id: String): RecordingEntity?

    /** Insere só se o id ainda não existir (não sobrescreve linha escrita pelo RecordManager). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(recording: RecordingEntity): Long

    /** Favoritos em lote: define o valor explicitamente (idempotente, sem read-modify-write). */
    @Query("UPDATE recording_table SET isFavorite = :favorite WHERE id IN (:ids)")
    suspend fun setFavorite(ids: List<String>, favorite: Boolean)

    /** Atualiza só os metadados derivados do arquivo, sem sobrescrever o resto da linha. */
    @Query("UPDATE recording_table SET thumbnailPath = :thumbnailPath, durationMs = :durationMs, sizeBytes = :sizeBytes WHERE id = :id")
    suspend fun updateFileMetadata(id: String, thumbnailPath: String, durationMs: Long, sizeBytes: Long)

    @Query("DELETE FROM recording_table WHERE id IN (:ids)")
    suspend fun deleteRecordingsByIds(ids: List<String>)

    /**
     * Gravações que ficaram IN_PROGRESS porque o processo morreu no meio do take. Só deve
     * ser chamado quando não há gravação ativa (boot do banco ou galeria sem REC).
     */
    @Query("UPDATE recording_table SET status = 'CORRUPTED' WHERE status = 'IN_PROGRESS'")
    suspend fun markOrphanInProgressAsCorrupted(): Int
}
