package com.bragastudio.mobile.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entidade que representa uma gravação no banco de dados.
 */
// Índice por filePath: a sincronização disco <-> Room consulta por caminho (migração 3 -> 4).
@Entity(tableName = "recording_table", indices = [Index(value = ["filePath"])])
data class RecordingEntity(
    @PrimaryKey
    val id: String,

    val fileName: String,

    val filePath: String,

    val thumbnailPath: String,

    val durationMs: Long,

    val sizeBytes: Long,

    val resolution: String,

    val frameRate: Int,

    val codec: String,

    val bitrate: Int,

    val audioCodec: String,

    val audioSampleRate: Int,

    val createdAt: Long,

    val isFavorite: Boolean = false,

    // Coluna TEXT mantida (RecordManager grava String). Valores: ver [RecordingStatus].
    val status: String, // COMPLETED, IN_PROGRESS, CORRUPTED, DELETED

    val projectTag: String? = null,

    val contentUri: String? = null,
)
