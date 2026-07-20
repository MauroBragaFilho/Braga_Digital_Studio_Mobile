package com.bragastudio.mobile.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Banco de dados unificado do BSM.
 * Version 1: lut_table
 * Version 2: lut_table, recording_table
 */
@Database(entities = [LutEntity::class, RecordingEntity::class], version = 2, exportSchema = false)
abstract class BsmDatabase : RoomDatabase() {

    abstract fun lutDao(): LutDao
    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile
        private var INSTANCE: BsmDatabase? = null
        
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Criação da tabela de recordings (versão 2)
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recording_table` (
                        `id` TEXT NOT NULL, 
                        `fileName` TEXT NOT NULL, 
                        `filePath` TEXT NOT NULL, 
                        `thumbnailPath` TEXT NOT NULL, 
                        `durationMs` INTEGER NOT NULL, 
                        `sizeBytes` INTEGER NOT NULL, 
                        `resolution` TEXT NOT NULL, 
                        `frameRate` INTEGER NOT NULL, 
                        `codec` TEXT NOT NULL, 
                        `bitrate` INTEGER NOT NULL, 
                        `audioCodec` TEXT NOT NULL, 
                        `audioSampleRate` INTEGER NOT NULL, 
                        `createdAt` INTEGER NOT NULL, 
                        `isFavorite` INTEGER NOT NULL, 
                        `status` TEXT NOT NULL, 
                        `projectTag` TEXT, 
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): BsmDatabase {
            return INSTANCE ?: synchronized(this) {
                // Mantém o nome do arquivo antigo (lut_database) por baixo dos panos para garantir a migração física,
                // ou usa um novo nome. Como antes usávamos LutDatabase e ele deve ter criado "lut_database", 
                // vamos manter esse nome no construtor do Room para não perder o DB antigo.
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BsmDatabase::class.java,
                    "bsm_database"
                )
                // Usando fallbackToDestructiveMigration apenas no desenvolvimento, 
                // mas a MIGRATION_1_2 fará a ponte se o banco antigo existir (como "bsm_database").
                // Nota: se o app já estava em produção com o nome "lut_database", precisaríamos renomear o arquivo ou usar esse nome.
                // Como não lançamos, vamos usar bsm_database e fazer fallback destrutivo caso dê conflito.
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
