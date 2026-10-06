package com.bragastudio.mobile.core.repository

import com.bragastudio.mobile.core.database.RecordingEntity
import kotlinx.coroutines.flow.Flow

interface RecordingRepository {
    fun getAllRecordings(): Flow<List<RecordingEntity>>
    fun searchRecordings(query: String): Flow<List<RecordingEntity>>
    fun getFavoriteRecordings(): Flow<List<RecordingEntity>>

    suspend fun getRecordingById(id: String): RecordingEntity?
    suspend fun insertRecording(recording: RecordingEntity)
    suspend fun updateRecording(recording: RecordingEntity)
    suspend fun deleteRecording(id: String)
    suspend fun toggleFavorite(id: String)
    suspend fun updateStatus(id: String, newStatus: String)
    suspend fun renameRecording(id: String, newName: String)
}
