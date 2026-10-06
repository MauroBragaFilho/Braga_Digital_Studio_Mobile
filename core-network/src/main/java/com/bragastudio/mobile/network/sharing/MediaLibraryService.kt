package com.bragastudio.mobile.network.sharing

import com.bragastudio.mobile.core.database.RecordingEntity
import com.bragastudio.mobile.core.repository.RecordingRepository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable

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
    val createdAt: String,
)

@Singleton
class MediaLibraryService @Inject constructor(
    private val recordingRepository: RecordingRepository,
) {
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    companion object {
        /**
         * Só gravações concluídas são expostas pelo Link: IN_PROGRESS ainda está sendo
         * escrita, COPYING está sendo copiada para o destino SAF (em andamento), CORRUPTED/DELETED não são confiáveis para download.
         */
        private val SERVABLE_STATUS = setOf("COMPLETED", "COPIED")

        /** Estados em andamento: nunca listar, baixar nem apagar. */
        private val IN_FLIGHT_STATUS = setOf("IN_PROGRESS", "COPYING")

        internal fun isServable(entity: RecordingEntity): Boolean = entity.status in SERVABLE_STATUS
    }

    suspend fun getMediaList(): List<MediaItemDto> {
        val recordings = recordingRepository.getAllRecordings().first().filter { isServable(it) }
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
                createdAt = dateString,
            )
        }
    }

    suspend fun getMediaFile(id: String): File? {
        val recording = recordingRepository.getRecordingById(id) ?: return null
        if (!isServable(recording)) return null
        val file = File(recording.filePath)
        return if (file.isFile) file else null
    }

    suspend fun getThumbnailFile(id: String): File? {
        val recording = recordingRepository.getRecordingById(id) ?: return null
        if (!isServable(recording) || recording.thumbnailPath.isBlank()) return null
        val file = File(recording.thumbnailPath)
        return if (file.isFile) file else null
    }

    /**
     * Apaga o arquivo e, só então, a linha do Room. Se o arquivo existe e não pôde ser
     * apagado, a linha é mantida (senão a gravação "sumiria" da lista mas continuaria no
     * disco) e o resultado é false. Uma gravação em andamento nunca é apagada por aqui.
     */
    suspend fun deleteMedia(id: String): Boolean {
        val recording = recordingRepository.getRecordingById(id) ?: return false
        if (recording.status in IN_FLIGHT_STATUS) return false

        val media = File(recording.filePath)
        val deleted = try {
            !media.exists() || media.delete()
        } catch (e: SecurityException) {
            false
        }
        if (!deleted) return false

        if (recording.thumbnailPath.isNotBlank()) {
            try {
                File(recording.thumbnailPath).delete()
            } catch (e: SecurityException) {
                // Miniatura é melhor-esforço.
            }
        }
        recordingRepository.deleteRecording(id)
        return true
    }
}
