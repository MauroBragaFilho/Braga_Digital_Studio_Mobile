package com.bragastudio.mobile.core.recording

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.util.Log
import com.bragastudio.mobile.core.database.RecordingDao
import com.bragastudio.mobile.core.database.RecordingEntity
import com.bragastudio.mobile.core.database.RecordingStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Mantém o Room (fonte única da galeria) coerente com o disco e executa ações de arquivo.
 *
 *  - [sync]: importa vídeos do disco sem linha no Room, remove linhas de arquivos apagados
 *    e completa miniatura/duração que ficaram em branco. Tudo em IO, serializado por Mutex.
 *  - [delete]: remove arquivo + miniatura + linha (bloqueia takes IN_PROGRESS).
 *  - [setFavorite]: favoritos em lote, persistidos na coluna isFavorite.
 *
 * O reconciliador de takes IN_PROGRESS órfãos roda na abertura do banco (BdsmDatabase), único
 * momento em que se sabe que não há gravação ativa; aqui IN_PROGRESS nunca é tocado.
 */
@Singleton
class RecordingLibrary @Inject constructor(
    private val dao: RecordingDao,
    @ApplicationContext private val context: Context,
) {

    data class SyncResult(val imported: Int = 0, val removed: Int = 0, val repaired: Int = 0)

    data class DeleteResult(val deleted: Int = 0, val blockedInProgress: Int = 0, val failed: Int = 0)

    private val syncMutex = Mutex()

    private val moviesDir: File?
        get() = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)

    private val thumbnailsDir: File
        get() = File(context.getExternalFilesDir(null), "thumbnails")

    // ------------------------------------------------------------------ sincronização

    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val dir = moviesDir
            // Armazenamento desmontado: não dá para afirmar que os arquivos sumiram.
            if (dir == null || Environment.getExternalStorageState(dir) != Environment.MEDIA_MOUNTED) {
                return@withLock SyncResult()
            }

            val rows = dao.getAllRecordingsSync()
            val now = System.currentTimeMillis()

            // 1. Remove linhas de arquivos que não existem mais (nem local, nem cópia SAF).
            val orphanIds = rows.filter { row ->
                when (row.status) {
                    RecordingStatus.DELETED -> true
                    RecordingStatus.IN_PROGRESS -> false
                    else -> !File(row.filePath).exists() && !safExists(row.contentUri)
                }
            }
            orphanIds.forEach { deleteThumbnail(it.thumbnailPath) }
            if (orphanIds.isNotEmpty()) dao.deleteRecordingsByIds(orphanIds.map { it.id })
            val orphanSet = orphanIds.map { it.id }.toSet()
            val liveRows = rows.filter { it.id !in orphanSet }

            // 2. Importa arquivos do disco que não têm linha. Ignora o que ainda está sendo
            //    escrito (modificado há menos de 5 s) e arquivos vazios.
            val known = liveRows.map { it.filePath }.toSet()
            val toImport = dir.listFiles { f -> f.isFile && RecordingFormat.isVideoFile(f.name) }
                ?.filter { it.absolutePath !in known && it.length() > 0 && now - it.lastModified() > RECENT_WRITE_MS }
                .orEmpty()
            val imported = probeAll(toImport) { file, info ->
                importFile(file, info)
            }

            // 3. Completa miniatura/duração que ficaram em branco (RecordManager falhou ao extrair).
            val toRepair = liveRows.filter { row ->
                row.status == RecordingStatus.COMPLETED &&
                    File(row.filePath).exists() &&
                    (row.durationMs <= 0L || row.thumbnailPath.isBlank() || !File(row.thumbnailPath).exists())
            }.take(MAX_REPAIRS_PER_SYNC)
            val repaired = repairAll(toRepair)

            SyncResult(imported = imported, removed = orphanIds.size, repaired = repaired)
        }
    }

    private suspend fun probeAll(files: List<File>, handle: suspend (File, ProbeInfo) -> Boolean): Int {
        if (files.isEmpty()) return 0
        val semaphore = Semaphore(MAX_PARALLEL_PROBES)
        return coroutineScope {
            files.map { file ->
                async {
                    semaphore.withPermit {
                        val info = probe(file, wantThumbnail = true)
                        handle(file, info)
                    }
                }
            }.awaitAll().count { it }
        }
    }

    private suspend fun importFile(file: File, info: ProbeInfo): Boolean {
        val id = UUID.randomUUID().toString()
        val thumbPath = saveThumbnail(id, info.thumbnail)
        val entity = RecordingEntity(
            id = id,
            fileName = file.name,
            filePath = file.absolutePath,
            thumbnailPath = thumbPath,
            durationMs = info.durationMs,
            sizeBytes = file.length(),
            resolution = RecordingFormat.resolutionFromSize(info.width, info.height),
            frameRate = info.fps,
            codec = RecordingFormat.codecFromMime(info.videoMime),
            bitrate = RecordingFormat.averageMbps(file.length(), info.durationMs),
            audioCodec = RecordingFormat.audioCodecFromMime(info.audioMime),
            audioSampleRate = info.audioSampleRate,
            createdAt = file.lastModified(),
            isFavorite = false,
            // Arquivo ilegível (sem duração/trilha de vídeo) é marcado, não escondido.
            status = if (info.readable) RecordingStatus.COMPLETED else RecordingStatus.CORRUPTED,
        )
        // IGNORE: se o RecordManager inseriu a linha entre a listagem e agora, não duplica.
        return dao.insertIfAbsent(entity) != -1L
    }

    private suspend fun repairAll(rows: List<RecordingEntity>): Int {
        if (rows.isEmpty()) return 0
        val semaphore = Semaphore(MAX_PARALLEL_PROBES)
        return coroutineScope {
            rows.map { row ->
                async {
                    semaphore.withPermit {
                        val file = File(row.filePath)
                        val info = probe(file, wantThumbnail = row.thumbnailPath.isBlank() || !File(row.thumbnailPath).exists())
                        if (!info.readable) return@withPermit false
                        val thumbPath = if (info.thumbnail != null) saveThumbnail(row.id, info.thumbnail) else row.thumbnailPath
                        dao.updateFileMetadata(
                            id = row.id,
                            thumbnailPath = thumbPath,
                            durationMs = if (row.durationMs > 0) row.durationMs else info.durationMs,
                            sizeBytes = if (row.sizeBytes > 0) row.sizeBytes else file.length(),
                        )
                        true
                    }
                }
            }.awaitAll().count { it }
        }
    }

    // ------------------------------------------------------------------ ações

    /** Favoritos em lote: define o valor (não inverte), então repetir a ação é idempotente. */
    suspend fun setFavorite(ids: Collection<String>, favorite: Boolean) {
        if (ids.isEmpty()) return
        withContext(Dispatchers.IO) { dao.setFavorite(ids.toList(), favorite) }
    }

    /**
     * Exclui as gravações [ids]: arquivo, miniatura e linha. Takes IN_PROGRESS são bloqueados.
     * A linha só sai do Room se o arquivo local foi realmente apagado (ou já não existia).
     * A cópia exportada (SAF) só é apagada com [alsoExportedCopy], decisão explícita do usuário.
     */
    suspend fun delete(ids: Collection<String>, alsoExportedCopy: Boolean = false): DeleteResult = withContext(Dispatchers.IO) {
        var deleted = 0
        var blocked = 0
        var failed = 0
        for (id in ids) {
            val row = dao.getRecordingById(id) ?: continue
            if (row.status == RecordingStatus.IN_PROGRESS) {
                blocked++
                continue
            }
            val file = File(row.filePath)
            if (file.exists() && !file.delete()) {
                failed++
                continue
            }
            deleteThumbnail(row.thumbnailPath)
            if (alsoExportedCopy) {
                row.contentUri?.let { uri ->
                    try {
                        ExportedCopy.delete(context.contentResolver, uri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Não foi possível apagar a cópia exportada de ${row.fileName}", e)
                    }
                }
            }
            dao.deleteRecordingById(id)
            deleted++
        }
        DeleteResult(deleted, blocked, failed)
    }

    enum class RenameResult { OK, INVALID, EXISTS, FAILED }

    /**
     * Renomeia o arquivo local (mantém a extensão) e atualiza o registro. Takes em andamento não
     * podem ser renomeados. A cópia exportada (SAF), se existir, mantém o nome antigo.
     */
    suspend fun rename(id: String, newBaseName: String): RenameResult = withContext(Dispatchers.IO) {
        val row = dao.getRecordingById(id) ?: return@withContext RenameResult.FAILED
        if (row.status == RecordingStatus.IN_PROGRESS) return@withContext RenameResult.FAILED
        if (RecordingNaming.sanitizeBaseName(newBaseName).isEmpty()) return@withContext RenameResult.INVALID
        val old = File(row.filePath)
        if (!old.exists()) return@withContext RenameResult.FAILED
        val target = File(old.parentFile, RecordingNaming.fileName(newBaseName, old.extension))
        if (target.name == old.name) return@withContext RenameResult.OK
        if (target.exists()) return@withContext RenameResult.EXISTS
        if (!old.renameTo(target)) return@withContext RenameResult.FAILED
        dao.updateRecording(row.copy(fileName = target.name, filePath = target.path))
        RenameResult.OK
    }

    // ------------------------------------------------------------------ metadados

    private class ProbeInfo(
        val readable: Boolean,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val fps: Int,
        val videoMime: String?,
        val audioMime: String?,
        val audioSampleRate: Int,
        val thumbnail: Bitmap?,
    )

    /**
     * Lê duração/quadros pelo MediaMetadataRetriever e codec/dimensões/áudio pelo
     * MediaExtractor (METADATA_KEY_MIMETYPE descreve o contêiner, não o codec). Uma instância
     * de cada por chamada e sempre liberada: nenhuma das duas é thread-safe.
     */
    private fun probe(file: File, wantThumbnail: Boolean): ProbeInfo {
        var durationMs = 0L
        var frameCount = 0L
        var width = 0
        var height = 0
        var fpsFromFormat = 0
        var videoMime: String? = null
        var audioMime: String? = null
        var sampleRate = 0
        var thumbnail: Bitmap? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
            }
            if (wantThumbnail) {
                val atUs = if (durationMs > 2_000) 1_000_000L else 0L
                thumbnail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, THUMB_WIDTH, THUMB_HEIGHT)
                        ?: retriever.getFrameAtTime(atUs)
                } else {
                    retriever.getFrameAtTime(atUs)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao ler metadados de ${file.name}", e)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao liberar MediaMetadataRetriever", e)
            }
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoMime == null) {
                    videoMime = mime
                    width = format.intOrZero(MediaFormat.KEY_WIDTH)
                    height = format.intOrZero(MediaFormat.KEY_HEIGHT)
                    fpsFromFormat = format.intOrZero(MediaFormat.KEY_FRAME_RATE)
                    if (durationMs <= 0 && format.containsKey(MediaFormat.KEY_DURATION)) {
                        durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
                    }
                } else if (mime.startsWith("audio/") && audioMime == null) {
                    audioMime = mime
                    sampleRate = format.intOrZero(MediaFormat.KEY_SAMPLE_RATE)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao ler trilhas de ${file.name}", e)
        } finally {
            try {
                extractor.release()
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao liberar MediaExtractor", e)
            }
        }

        val measuredFps = RecordingFormat.fpsFromFrameCount(frameCount, durationMs)
        return ProbeInfo(
            readable = durationMs > 0 && videoMime != null,
            durationMs = durationMs,
            width = width,
            height = height,
            fps = if (measuredFps > 0) measuredFps else fpsFromFormat,
            videoMime = videoMime,
            audioMime = audioMime,
            audioSampleRate = sampleRate,
            thumbnail = thumbnail,
        )
    }

    private fun MediaFormat.intOrZero(key: String): Int = try {
        if (containsKey(key)) getInteger(key) else 0
    } catch (e: Exception) {
        0
    }

    // ------------------------------------------------------------------ arquivos auxiliares

    private fun saveThumbnail(id: String, bitmap: Bitmap?): String {
        if (bitmap == null) return ""
        return try {
            val dir = thumbnailsDir
            if (!dir.exists()) dir.mkdirs()
            val thumb = File(dir, "thumb_$id.jpg")
            FileOutputStream(thumb).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
            thumb.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao salvar miniatura", e)
            ""
        }
    }

    private fun deleteThumbnail(path: String) {
        if (path.isBlank()) return
        try {
            val f = File(path)
            if (f.exists()) f.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao apagar miniatura", e)
        }
    }

    /** true se a cópia SAF ainda existe. Sem contentUri, false (não há o que consultar). */
    private fun safExists(contentUri: String?): Boolean = ExportedCopy.exists(context.contentResolver, contentUri)

    private companion object {
        const val TAG = "RecordingLibrary"
        const val THUMB_WIDTH = 480
        const val THUMB_HEIGHT = 270
        const val MAX_PARALLEL_PROBES = 3
        const val MAX_REPAIRS_PER_SYNC = 24
        const val RECENT_WRITE_MS = 5_000L
    }
}
