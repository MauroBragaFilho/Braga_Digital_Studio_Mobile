package com.bragastudio.mobile.corecapture.data

import android.content.Context
import com.bragastudio.mobile.core.database.RecordingDao
import com.bragastudio.mobile.core.database.RecordingEntity
import com.bragastudio.mobile.core.recording.ExportedCopy
import com.bragastudio.mobile.core.repository.RecordingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

@Singleton
class RecordingRepositoryImpl @Inject constructor(
    private val recordingDao: RecordingDao,
    @ApplicationContext private val context: Context,
) : RecordingRepository {

    override fun getAllRecordings(): Flow<List<RecordingEntity>> = recordingDao.getAllRecordings()

    override fun searchRecordings(query: String): Flow<List<RecordingEntity>> = recordingDao.searchRecordings(query)

    override fun getFavoriteRecordings(): Flow<List<RecordingEntity>> = recordingDao.getFavoriteRecordings()

    override suspend fun getRecordingById(id: String): RecordingEntity? = recordingDao.getRecordingById(id)

    override suspend fun insertRecording(recording: RecordingEntity) {
        recordingDao.insertRecording(recording)
    }

    override suspend fun updateRecording(recording: RecordingEntity) {
        recordingDao.updateRecording(recording)
    }

    override suspend fun deleteRecording(id: String) {
        withContext(Dispatchers.IO) {
            val recording = recordingDao.getRecordingById(id)
            if (recording != null) {
                val file = File(recording.filePath)
                if (file.exists()) {
                    val deleted = file.delete()
                    android.util.Log.d("RecordingRepositoryImpl", "Deleted video file: $deleted")
                }
                val thumbnail = File(recording.thumbnailPath)
                if (thumbnail.exists()) {
                    val deleted = thumbnail.delete()
                    android.util.Log.d("RecordingRepositoryImpl", "Deleted thumbnail file: $deleted")
                }
                recording.contentUri?.let { uriString ->
                    runCatching {
                        ExportedCopy.delete(context.contentResolver, uriString)
                    }
                }
                recordingDao.deleteRecordingById(id)
            }
        }
    }

    override suspend fun toggleFavorite(id: String) {
        val recording = recordingDao.getRecordingById(id)
        if (recording != null) {
            recordingDao.updateRecording(recording.copy(isFavorite = !recording.isFavorite))
        }
    }

    override suspend fun updateStatus(id: String, newStatus: String) {
        val recording = recordingDao.getRecordingById(id)
        if (recording != null) {
            recordingDao.updateRecording(recording.copy(status = newStatus))
        }
    }

    override suspend fun renameRecording(id: String, newName: String) {
        val recording = recordingDao.getRecordingById(id)
        if (recording != null) {
            recordingDao.updateRecording(recording.copy(fileName = newName))
        }
    }
}
