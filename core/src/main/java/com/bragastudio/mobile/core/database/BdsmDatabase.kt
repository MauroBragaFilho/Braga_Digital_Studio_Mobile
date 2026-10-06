package com.bragastudio.mobile.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Banco de dados unificado do BDSM.
 *
 * NOMES LEGADOS MANTIDOS DE PROPÓSITO (persistidos; renomear apagaria dados/preferências do usuário):
 *  - arquivo do banco "bsm_database" (e -wal/-shm, citados em backup_rules.xml e data_extraction_rules.xml);
 *  - DataStore "bsm_settings" (SettingsRepositoryImpl);
 *  - biblioteca nativa "bsm-media" (CMake/System.loadLibrary) e a pasta de gravações "BSM" já gravada em caminhos do Room.
 *
 * Historico de versoes:
 *  - Version 1: lut_table
 *  - Version 2: lut_table, recording_table
 *  - Version 3: recording_table ganha a coluna contentUri (SAF/armazenamento)
 *  - Version 4: índice único lut_table(filePath) (com deduplicação) e índice recording_table(filePath)
 *
 * exportSchema = true: ao buildar, o Room exporta o schema em core/schemas/.
 * Comite o JSON gerado; e a unica forma de validar migracoes futuras com o
 * MigrationTestHelper em vez de descobrir divergencia so em producao.
 */
@Database(entities = [LutEntity::class, RecordingEntity::class], version = 4, exportSchema = true)
abstract class BdsmDatabase : RoomDatabase() {

    abstract fun lutDao(): LutDao
    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile
        private var INSTANCE: BdsmDatabase? = null

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
                    """.trimIndent(),
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

        /**
         * Migração 3 -> 4.
         *  - lut_table: remove duplicatas por filePath (mantém a LUT ativa; entre iguais, a mais
         *    recente) e cria o índice único, evitando duas linhas para o mesmo arquivo (B12/L3).
         *  - recording_table: índice por filePath para a sincronização disco <-> Room.
         * A coluna isFavorite já existe desde a versão 2 (favoritos são persistidos).
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    DELETE FROM `lut_table` WHERE `id` IN (
                        SELECT t.`id` FROM `lut_table` t WHERE EXISTS (
                            SELECT 1 FROM `lut_table` o
                            WHERE o.`filePath` = t.`filePath` AND o.`id` <> t.`id` AND (
                                o.`isActive` > t.`isActive`
                                OR (o.`isActive` = t.`isActive` AND o.`modificationDate` > t.`modificationDate`)
                                OR (o.`isActive` = t.`isActive` AND o.`modificationDate` = t.`modificationDate` AND o.`id` < t.`id`)
                            )
                        )
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_lut_table_filePath` ON `lut_table` (`filePath`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_recording_table_filePath` ON `recording_table` (`filePath`)")
            }
        }

        /** Todas as migrações, em ordem. Usada pelo builder (produção) e disponível a testes. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        /**
         * Ao abrir o banco (início do processo, antes de qualquer gravação poder começar),
         * qualquer take IN_PROGRESS é órfão: o processo morreu no meio dele. Vira CORRUPTED
         * (a coluna continua TEXT; a galeria mostra o selo de aviso e a exclusão é liberada).
         */
        private val orphanRecordingsCallback = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                try {
                    db.execSQL("UPDATE `recording_table` SET `status` = 'CORRUPTED' WHERE `status` = 'IN_PROGRESS'")
                } catch (e: Exception) {
                    android.util.Log.w("BdsmDatabase", "Falha ao reconciliar gravações órfãs", e)
                }
            }
        }

        fun getInstance(context: Context): BdsmDatabase = INSTANCE ?: synchronized(this) {
            // Mantém o nome do arquivo antigo (lut_database) por baixo dos panos para garantir a migração física,
            // ou usa um novo nome. Como antes usávamos LutDatabase e ele deve ter criado "lut_database",
            // vamos manter esse nome no construtor do Room para não perder o DB antigo.
            val instance = Room.databaseBuilder(
                context.applicationContext,
                BdsmDatabase::class.java,
                "bsm_database",
            )
                // Sem fallback destrutivo: um conflito de versão deve falhar em vez de
                // apagar gravações/LUTs do usuário de forma silenciosa. Migrações
                // explícitas 1→2, 2→3 e 3→4 cobrem a cadeia completa.
                .addMigrations(*ALL_MIGRATIONS)
                .addCallback(orphanRecordingsCallback)
                .build()
            INSTANCE = instance
            instance
        }
    }
}
