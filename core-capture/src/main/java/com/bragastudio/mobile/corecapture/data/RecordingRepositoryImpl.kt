package com.bragastudio.mobile.corecapture.data

import android.content.Context
import android.os.Environment
import com.bragastudio.mobile.core.database.RecordingDao
import com.bragastudio.mobile.core.database.RecordingEntity
import com.bragastudio.mobile.core.repository.RecordingRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import android.net.Uri
import android.provider.DocumentsContract
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingRepositoryImpl @Inject constructor(
    private val recordingDao: RecordingDao,
    @ApplicationContext private val context: Context
) : RecordingRepository {

    override fun getAllRecordings(): Flow<List<RecordingEntity>> {
        return recordingDao.getAllRecordings()
    }

    override fun searchRecordings(query: String): Flow<List<RecordingEntity>> {
        return recordingDao.searchRecordings(query)
    }

    override fun getFavoriteRecordings(): Flow<List<RecordingEntity>> {
        return recordingDao.getFavoriteRecordings()
    }

    override suspend fun getRecordingById(id: String): RecordingEntity? {
        return recordingDao.getRecordingById(id)
    }

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
                        DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uriString))
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

    override suspend fun syncFileSystemWithDatabase() = withContext(Dispatchers.IO) {
        val moviesDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return@withContext
        val physicalFiles = moviesDir.listFiles { file -> 
            file.extension.equals("mp4", true) || file.extension.equals("mkv", true)
        }?.toList() ?: emptyList()
        
        val physicalPaths = physicalFiles.map { it.absolutePath }
        
        val dbRecordings = recordingDao.getAllRecordingsSync()
        
        // Remove from DB if file is missing and it's marked as COMPLETED
        dbRecordings.forEach { dbRecording ->
            val hasLocalFile = physicalPaths.contains(dbRecording.filePath)
            val hasSafFile = dbRecording.contentUri?.let { uriString ->
                runCatching {
                    context.contentResolver.query(Uri.parse(uriString), arrayOf("_id"), null, null, null)
                        ?.use { it.moveToFirst() } == true
                }.getOrDefault(false)
            } == true
            if (dbRecording.status == "COMPLETED" && !hasLocalFile && !hasSafFile) {
                recordingDao.deleteRecordingById(dbRecording.id)
            }
        }
        
        // Note: For a complete sync, we could also extract metadata from physical files that are not in the DB
        // and insert them. However, that requires MediaMetadataRetriever which can be slow for many files.
        // As a simpler sync for now, we just clean up dead records.
    }
}
