package com.bragastudio.mobile.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entidade que representa uma gravação no banco de dados.
 */
@Entity(tableName = "recording_table")
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
    
    val status: String, // COMPLETED, IN_PROGRESS, CORRUPTED, DELETED
    
    val projectTag: String? = null
)
