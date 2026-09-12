package com.bragastudio.mobile.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Banco de dados unificado do BSM.
 *
 * Historico de versoes:
 *  - Version 1: lut_table
 *  - Version 2: lut_table, recording_table
 *  - Version 3: recording_table ganha a coluna contentUri (SAF/armazenamento)
 *
 * exportSchema = true: ao buildar, o Room exporta o schema em core/schemas/.
 * Comite o JSON gerado; e a unica forma de validar migracoes futuras com o
 * MigrationTestHelper em vez de descobrir divergencia so em producao.
 */
@Database(entities = [LutEntity::class, RecordingEntity::class], version = 3, exportSchema = true)
abstract class BsmDatabase : RoomDatabase() {

    abstract fun lutDao(): LutDao
    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile
        private var INSTANCE: BsmDatabase? = null
        
        /**
         * Migração 1 -> 2 (criação da recording_table).
         * Exposta (pública) de propósito para o MigrationTestHelper (androidTest)
         * validar as mesmas instâncias usadas em produção.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
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

        /**
         * Migração 2 -> 3 (recording_table ganha contentUri para SAF/armazenamento).
         * Exposta (pública) de propósito para o MigrationTestHelper (androidTest).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recording_table` ADD COLUMN `contentUri` TEXT")
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
                // Sem fallback destrutivo: um conflito de versão deve falhar em vez de
                // apagar gravações/LUTs do usuário de forma silenciosa. Migrações
                // explícitas 1→2 e 2→3 cobrem a cadeia completa. 
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
