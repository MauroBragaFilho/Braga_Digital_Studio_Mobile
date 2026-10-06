package com.bragastudio.mobile.coremedia.persistence

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.bragastudio.mobile.core.database.LutDao
import com.bragastudio.mobile.core.database.LutEntity
import com.bragastudio.mobile.core.model.Lut
import com.bragastudio.mobile.core.repository.LutRepository
import com.bragastudio.mobile.coremedia.domain.LutParser
import com.bragastudio.mobile.network.sharing.LutLibraryService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Ponto de entrada Hilt para obter o [LutLibraryService] sem mudar o construtor do repositório. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LutLibraryEntryPoint {
    fun lutLibraryService(): LutLibraryService
}

@Singleton
class LutRepositoryImpl @Inject constructor(
    private val lutDao: LutDao,
    @ApplicationContext private val context: Context,
) : LutRepository {

    private val syncMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Sincroniza no start do app e a cada upload/exclusão feito pelo Link (L3): a LUT enviada
        // pelo PC aparece no Preview sem abrir a tela de gerenciamento.
        scope.launch {
            try {
                syncLutsFromDisk()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Falha na sincronização inicial de LUTs", e)
            }
            observeLibraryChanges()
        }
    }

    private suspend fun observeLibraryChanges() {
        val service = try {
            EntryPointAccessors.fromApplication(context, LutLibraryEntryPoint::class.java).lutLibraryService()
        } catch (e: Exception) {
            Log.w(TAG, "LutLibraryService indisponível; sincronização por evento desativada", e)
            return
        }
        try {
            service.changes.collect {
                try {
                    delay(CHANGE_SETTLE_MS) // agrupa rajadas (upload de várias LUTs)
                    syncLutsFromDisk()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao sincronizar LUTs após evento do Link", e)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Coleta de mudanças de LUT encerrada", e)
        }
    }

    override fun getAllLuts(): Flow<List<Lut>> = lutDao.getAllLuts().map { entities -> entities.map { it.toLut() } }

    override fun getBuiltInLuts(): Flow<List<Lut>> = lutDao.getBuiltInLuts().map { entities -> entities.map { it.toLut() } }

    override fun getUserImportedLuts(): Flow<List<Lut>> = lutDao.getUserImportedLuts().map { entities -> entities.map { it.toLut() } }

    override suspend fun getLutById(id: String): Lut? = lutDao.getLutById(id)?.toLut()

    override suspend fun insertLut(lut: Lut, file: File) {
        lutDao.insertLut(lut.toEntity())
    }

    override suspend fun updateLut(lut: Lut) {
        lutDao.updateLut(lut.toEntity())
    }

    override suspend fun deleteLut(lutId: String) = withContext(Dispatchers.IO) {
        val lut = lutDao.getLutById(lutId) ?: return@withContext
        if (!lut.isBuiltIn) {
            val file = File(lut.filePath)
            // Se o arquivo não puder ser apagado, mantém a linha: senão a sincronização a recriaria.
            if (file.exists() && !file.delete()) {
                throw IOException("Não foi possível apagar o arquivo da LUT.")
            }
        }
        lutDao.deleteLutById(lutId)
    }

    override suspend fun setActiveLut(lutId: String?) {
        // Uma única instrução SQL: nunca há estado intermediário "nenhuma ativa" nem duas ativas.
        if (lutId == null) lutDao.deactivateAll() else lutDao.activateOnly(lutId)
    }

    override fun getActiveLut(): Flow<Lut?> = lutDao.getActiveLut().map { it?.toLut() }

    /** Validação real: extensão .cube, tamanho dentro do teto e conteúdo parseável como LUT 3D. */
    override suspend fun validateLutFile(file: File): Boolean = withContext(Dispatchers.IO) {
        file.exists() &&
            file.extension.equals("cube", ignoreCase = true) &&
            LutParser.parseFromFile(file) != null
    }

    override fun generateUniqueId(fileName: String): String = UUID.randomUUID().toString()

    override fun generateDisplayName(fileName: String): String = fileName.removeSuffix(".cube").replace("_", " ")

    override suspend fun importLut(uri: Uri): Result<Lut> = withContext(Dispatchers.IO) {
        var tmp: File? = null
        try {
            val rawName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            val name = sanitizeFileName(rawName, "imported_lut_${System.currentTimeMillis()}.cube")

            val temp = File.createTempFile("lut_import_", ".tmp", context.cacheDir)
            tmp = temp
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IOException("Não foi possível abrir o arquivo selecionado."))
            input.use { src ->
                temp.outputStream().use { dst ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = src.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > LutParser.MAX_FILE_BYTES) {
                            return@withContext Result.failure(
                                IllegalArgumentException("Arquivo LUT maior que ${LutParser.MAX_FILE_BYTES / (1024 * 1024)} MB."),
                            )
                        }
                        dst.write(buffer, 0, n)
                    }
                }
            }

            // Validação real (parser completo): LUT 1D, tamanho absurdo ou dados truncados falham aqui.
            val parsed = LutParser.tryParseFromFile(temp)
            if (parsed.isFailure) {
                return@withContext Result.failure(
                    IllegalArgumentException(parsed.exceptionOrNull()?.message ?: "Arquivo LUT inválido."),
                )
            }

            val dir = userLutDir()
            val dest = File(dir, name)
            temp.copyTo(dest, overwrite = true)

            val now = System.currentTimeMillis()
            val existing = lutDao.getLutByFilePath(dest.absolutePath)
            val entity = existing?.copy(
                sizeBytes = dest.length(),
                modificationDate = now,
            ) ?: LutEntity(
                id = generateUniqueId(name),
                fileName = name,
                displayName = generateDisplayName(name),
                filePath = dest.absolutePath,
                type = "3D LUT",
                sizeBytes = dest.length(),
                creationDate = now,
                modificationDate = now,
                isActive = false,
                isBuiltIn = false,
            )
            // REPLACE mantém a linha única por filePath; isActive é preservado via copy().
            lutDao.insertLut(entity)
            Result.success(entity.toLut())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Falha ao importar LUT", e)
            Result.failure(e)
        } finally {
            tmp?.delete()
        }
    }

    private fun userLutDir(): File {
        val dir = File(context.getExternalFilesDir(null), "luts")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Reconcilia o Room com o disco:
     *  - assets `luts/` viram LUTs built-in (caminho virtual `luts/<nome>`);
     *  - `getExternalFilesDir/luts` é varrido recursivamente (o Link grava em subpastas);
     *    a identidade é o caminho, então duas LUTs de mesmo nome em pastas diferentes coexistem;
     *  - linhas cujo arquivo sumiu (apagado pelo Link/usuário) saem do Room;
     *  - só entram LUTs 3D com cabeçalho válido (1D e arquivos quebrados são ignorados).
     */
    override suspend fun syncLutsFromDisk() = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val assetNames = try {
                context.assets.list("luts")?.filter { it.endsWith(".cube", ignoreCase = true) } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }

            val root = userLutDir()
            val physical = root.walkTopDown()
                .maxDepth(MAX_SCAN_DEPTH)
                .filter { it.isFile && it.extension.equals("cube", ignoreCase = true) }
                .toList()

            val dbLuts = lutDao.getAllLutsSync()

            // 1. Remove do Room o que não existe mais no disco/assets.
            dbLuts.forEach { dbLut ->
                val gone = if (dbLut.isBuiltIn) {
                    dbLut.filePath.removePrefix("luts/") !in assetNames
                } else {
                    !File(dbLut.filePath).exists()
                }
                if (gone) lutDao.deleteLutById(dbLut.id)
            }

            // 2. Adiciona o que existe e ainda não tem linha (identidade = filePath; IGNORE no conflito).
            val knownPaths = dbLuts.map { it.filePath }.toSet()
            val now = System.currentTimeMillis()

            assetNames.forEach { assetName ->
                val path = "luts/$assetName"
                if (path !in knownPaths) {
                    lutDao.insertIfAbsent(
                        LutEntity(
                            id = UUID.randomUUID().toString(),
                            fileName = assetName,
                            displayName = generateDisplayName(assetName),
                            filePath = path, // caminho virtual de assets
                            type = "3D LUT",
                            sizeBytes = 0L,
                            creationDate = now,
                            modificationDate = now,
                            isActive = false,
                            isBuiltIn = true,
                        ),
                    )
                }
            }

            physical.forEach { file ->
                if (file.absolutePath in knownPaths) return@forEach
                when (LutParser.peekHeader(file)) {
                    is LutParser.Header.Lut3D -> Unit

                    LutParser.Header.Lut1D -> {
                        Log.w(TAG, "LUT 1D ignorada: ${file.name}")
                        return@forEach
                    }

                    LutParser.Header.Unknown -> {
                        Log.w(TAG, "Arquivo .cube sem cabeçalho válido ignorado: ${file.name}")
                        return@forEach
                    }
                }
                lutDao.insertIfAbsent(
                    LutEntity(
                        id = UUID.randomUUID().toString(),
                        fileName = file.name,
                        displayName = displayNameFor(file.relativeTo(root).path),
                        filePath = file.absolutePath,
                        type = "3D LUT",
                        sizeBytes = file.length(),
                        creationDate = file.lastModified(),
                        modificationDate = file.lastModified(),
                        isActive = false,
                        isBuiltIn = false,
                    ),
                )
            }
        }
    }

    private fun displayNameFor(relativePath: String): String = displayNameFromRelativePath(relativePath)

    private fun LutEntity.toLut() = Lut(
        id = id,
        fileName = fileName,
        displayName = displayName,
        description = description,
        filePath = filePath,
        type = type,
        sizeBytes = sizeBytes,
        creationDate = creationDate,
        modificationDate = modificationDate,
        isActive = isActive,
        isBuiltIn = isBuiltIn,
        category = category,
        author = author,
        version = version,
        checksumSha256 = checksumSha256,
    )

    private fun Lut.toEntity() = LutEntity(
        id = id,
        fileName = fileName,
        displayName = displayName,
        description = description,
        filePath = filePath,
        type = type,
        sizeBytes = sizeBytes,
        creationDate = creationDate,
        modificationDate = modificationDate,
        isActive = isActive,
        isBuiltIn = isBuiltIn,
        category = category,
        author = author,
        version = version,
        checksumSha256 = checksumSha256,
    )

    companion object {
        private const val TAG = "LutRepository"
        private const val MAX_SCAN_DEPTH = 4
        private const val CHANGE_SETTLE_MS = 250L
        private const val MAX_NAME_LENGTH = 100

        /**
         * Nome de arquivo seguro para gravar em `luts/`: descarta diretórios (tudo até a
         * última barra, normal ou invertida), caracteres de controle e símbolos fora de
         * letras/dígitos/espaço/`._-()`, evita nome começando por ponto, limita o tamanho e
         * garante a extensão `.cube`. Nome vazio usa [fallback].
         */
        internal fun sanitizeFileName(raw: String?, fallback: String): String {
            // Sem java.io.File: no Windows (testes JVM) "a:b" seria lido como unidade.
            val base = raw.orEmpty().replace('\\', '/').substringAfterLast('/')
            if (base.equals(".cube", ignoreCase = true)) return fallback
            val cleaned = buildString {
                for (ch in base) {
                    append(
                        if (ch.isLetterOrDigit() || ch == ' ' || ch == '.' || ch == '_' || ch == '-' || ch == '(' || ch == ')') ch else '_',
                    )
                }
            }.trim().trimStart('.')
            if (cleaned.isBlank()) return fallback
            val noExt = if (cleaned.lowercase().endsWith(".cube")) cleaned.dropLast(5) else cleaned
            val stem = noExt.trimEnd('.', ' ')
            if (stem.isBlank()) return fallback
            return stem.take(MAX_NAME_LENGTH - ".cube".length) + ".cube"
        }

        /** "Cine_Vibrant.cube" -> "Cine Vibrant"; "pasta/Look_A.cube" -> "pasta / Look A". */
        internal fun displayNameFromRelativePath(relativePath: String): String {
            val normalized = relativePath.replace('\\', '/')
            val noExt = if (normalized.lowercase().endsWith(".cube")) normalized.dropLast(5) else normalized
            return noExt
                .replace("_", " ")
                .replace("/", " / ")
        }
    }
}
