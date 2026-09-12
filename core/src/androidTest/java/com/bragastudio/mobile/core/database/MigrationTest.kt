package com.bragastudio.mobile.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Valida as migrações do Room 1 → 2 → 3 contra os schemas exportados em
 * core/schemas/.../BsmDatabase/ (1.json, 2.json e 3.json).
 *
 * Os JSONs 1.json e 2.json foram derivados do 3.json + SQL das migrações, já que
 * o Room só exporta o schema corrente; eles são fixtures de teste e não devem ser
 * editados à mão fora do contexto de uma migração.
 *
 * Requer device/emulador para executar: `./gradlew :core:connectedDebugAndroidTest`
 * (os testes usam androidTest assets que apontam para core/schemas).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BsmDatabase::class.java
    )

    // ---- 2 -> 3 -------------------------------------------------------------

    @Test
    fun migracao2Para3_adicionaContentUri_preservandoDados() {
        helper.createDatabase("migracao-2-3", 2).apply {
            insertRecordingV2(this, "rec-002")
            close()
        }

        val db = helper.runMigrationsAndValidate(
            "migracao-2-3",
            3,
            true,
            BsmDatabase.MIGRATION_2_3
        )
        try {
            assertRecordingPresent(db, "rec-002")
            assertColumnExists(db, "recording_table", "contentUri")
        } finally {
            db.close()
        }
    }

    // ---- 1 -> 2 -> 3 --------------------------------------------------------

    @Test
    fun migracao1Para3_rodaCadeiaInteira_preservandoLuts() {
        helper.createDatabase("migracao-1-3", 1).apply {
            execSQL(
                """
                INSERT INTO lut_table (
                    id, fileName, displayName, description, filePath, type, sizeBytes,
                    creationDate, modificationDate, isActive, isBuiltIn, category,
                    author, version, checksumSha256
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    "lut-001", "velvia.json", "Velvia", null,
                    "/storage/emulated/0/BSM/LUTs/velvia.json", "CUBE", 4096L,
                    1_723_000_000_000L, 1_723_000_000_000L, 1L, 0L,
                    null, null, null, null
                )
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            "migracao-1-3",
            3,
            true,
            BsmDatabase.MIGRATION_1_2,
            BsmDatabase.MIGRATION_2_3
        )
        try {
            db.query("SELECT id FROM lut_table WHERE id = ?", arrayOf("lut-001")).use { c ->
                assertTrue("registro lut-001 deve sobreviver à migração", c.moveToFirst())
            }
            assertColumnExists(db, "recording_table", "contentUri")
        } finally {
            db.close()
        }
    }

    // ---- helpers ------------------------------------------------------------

    private fun insertRecordingV2(db: SupportSQLiteDatabase, id: String) {
        db.execSQL(
            """
            INSERT INTO recording_table (
                id, fileName, filePath, thumbnailPath, durationMs, sizeBytes,
                resolution, frameRate, codec, bitrate, audioCodec, audioSampleRate,
                createdAt, isFavorite, status, projectTag
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                id,
                "grabacao_${id}.mp4",
                "/storage/emulated/0/Movies/BSM/grabacao_${id}.mp4",
                "/storage/emulated/0/Movies/BSM/grabacao_${id}.jpg",
                62_400L,
                8_200_000L,
                "1920x1080",
                30,
                "h264",
                12_000_000,
                "aac",
                48_000,
                1_723_123_456_789L,
                0L,
                "COMPLETED",
                null
            )
        )
    }

    private fun assertRecordingPresent(db: SupportSQLiteDatabase, id: String) {
        db.query(
            "SELECT id, fileName, contentUri FROM recording_table WHERE id = ?",
            arrayOf(id)
        ).use { c ->
            assertTrue("recording $id deve existir após a migração", c.moveToFirst())
            val contentUriIdx = c.getColumnIndexOrThrow("contentUri")
            assertTrue("contentUri deve ser NULL no registro migrado", c.isNull(contentUriIdx))
        }
    }

    private fun assertColumnExists(db: SupportSQLiteDatabase, table: String, column: String) {
        db.query("PRAGMA table_info($table)").use { c ->
            var found = false
            while (c.moveToNext()) {
                if (c.getString(1) == column) {
                    found = true
                    break
                }
            }
            assertTrue("coluna $column deve existir em $table", found)
        }
    }
}