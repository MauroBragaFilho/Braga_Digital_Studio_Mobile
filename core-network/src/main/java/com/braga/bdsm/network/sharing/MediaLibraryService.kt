package com.braga.bdsm.network.sharing

import com.bragastudio.mobile.core.repository.RecordingRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class MediaItemDto(
    val id: String,
    val filename: String,
    val filesize: Long,
    val duration: Double,
    val width: Int,
    val height: Int,
    val fps: Double,
    val codec: String,
    val createdAt: String
)

@Singleton
class MediaLibraryService @Inject constructor(
    private val recordingRepository: RecordingRepository
) {
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    suspend fun getMediaList(): List<MediaItemDto> {
        val recordings = recordingRepository.getAllRecordings().first()
        return recordings.map { entity ->
            val dimensions = entity.resolution.split("x", "X")
            val width = dimensions.getOrNull(0)?.trim()?.toIntOrNull() ?: 1920
            val height = dimensions.getOrNull(1)?.trim()?.toIntOrNull() ?: 1080
            val durationSec = entity.durationMs / 1000.0
            val dateString = synchronized(isoDateFormat) {
                isoDateFormat.format(Date(entity.createdAt))
            }

            MediaItemDto(
                id = entity.id,
                filename = entity.fileName,
                filesize = entity.sizeBytes,
                duration = durationSec,
                width = width,
                height = height,
                fps = entity.frameRate.toDouble(),
                codec = entity.codec.lowercase(Locale.ROOT),
                createdAt = dateString
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
