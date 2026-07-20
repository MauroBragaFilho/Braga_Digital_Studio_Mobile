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

    @Query("SELECT * FROM recording_table WHERE fileName LIKE '%' || :query || '%' OR projectTag LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun searchRecordings(query: String): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recording_table WHERE isFavorite = 1 ORDER BY createdAt DESC")
    fun getFavoriteRecordings(): Flow<List<RecordingEntity>>
    
    @Query("SELECT * FROM recording_table WHERE id = :id")
    suspend fun getRecordingById(id: String): RecordingEntity?
}
