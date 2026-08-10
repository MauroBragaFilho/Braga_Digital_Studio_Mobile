package com.braga.bdsm.network.sharing

import com.bragastudio.mobile.core.repository.RecordingRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class MediaItemDto(
    val id: String,
    val name: String,
    val date: Long,
    val durationMs: Long,
    val resolution: String,
    val fps: Int,
    val codec: String,
    val sizeBytes: Long,
    val hasThumbnail: Boolean
)

@Singleton
class MediaLibraryService @Inject constructor(
    private val recordingRepository: RecordingRepository
) {
    suspend fun getMediaList(): List<MediaItemDto> {
        val recordings = recordingRepository.getAllRecordings().first()
        return recordings.map { entity ->
            MediaItemDto(
                id = entity.id,
                name = entity.fileName,
                date = entity.createdAt,
                durationMs = entity.durationMs,
                resolution = entity.resolution,
                fps = entity.frameRate,
                codec = entity.codec,
                sizeBytes = entity.sizeBytes,
                hasThumbnail = entity.thumbnailPath.isNotBlank() && File(entity.thumbnailPath).exists()
            )
        }
    }

    suspend fun getMediaFile(id: String): File? {
        val recording = recordingRepository.getRecordingById(id) ?: return null
        val file = File(recording.filePath)
        return if (file.exists()) file else null
    }

    suspend fun getThumbnailFile(id: String): File? {
        val recording = recordingRepository.getRecordingById(id) ?: return null
        val file = File(recording.thumbnailPath)
        return if (file.exists()) file else null
    }

    suspend fun deleteMedia(id: String): Boolean {
        val recording = recordingRepository.getRecordingById(id) ?: return false
        
        // Delete files
        try {
            File(recording.filePath).delete()
            if (recording.thumbnailPath.isNotBlank()) {
                File(recording.thumbnailPath).delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Delete from database
        recordingRepository.deleteRecording(id)
        return true
    }
}
