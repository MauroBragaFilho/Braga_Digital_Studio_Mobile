package com.bragastudio.mobile.core.repository

import com.bragastudio.mobile.core.model.Lut
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * Interface para o repositório de LUTs.
 * Define os contratos para operações de persistência e consulta de LUTs.
 * Esta interface reside em 'core' para abstrair a implementação específica
 * (por exemplo, Room no 'core-media').
 */
interface LutRepository {

    /**
     * Consulta todas as LUTs armazenadas, retornando um Flow para observar mudanças.
     * @return Flow contendo a lista de todas as [Lut].
     */
    fun getAllLuts(): Flow<List<Lut>>

    /**
     * Consulta as LUTs pré-instaladas (built-in), retornando um Flow.
     * @return Flow contendo a lista de [Lut] built-in.
     */
    fun getBuiltInLuts(): Flow<List<Lut>>

    /**
     * Consulta as LUTs importadas pelo usuário, retornando um Flow.
     * @return Flow contendo a lista de [Lut] importadas pelo usuário.
     */
    fun getUserImportedLuts(): Flow<List<Lut>>

    /**
     * Consulta uma LUT específica pelo seu ID.
     * @param id O ID único da LUT.
     * @return A [Lut] encontrada ou null se não existir.
     */
    suspend fun getLutById(id: String): Lut?

    /**
     * Insere uma nova LUT no repositório.
     * Isso envolve copiar o arquivo para o local apropriado e salvar os metadados.
     * @param lut A [Lut] a ser inserida.
     * @param file O arquivo .cube original a ser copiado.
     * @throws Exception Se ocorrer um erro durante a cópia ou persistência.
     */
    suspend fun insertLut(lut: Lut, file: File)

    /**
     * Atualiza os metadados de uma LUT existente no repositório.
     * @param lut A [Lut] com os dados atualizados.
     * @throws Exception Se ocorrer um erro durante a atualização.
     */
    suspend fun updateLut(lut: Lut)

    /**
     * Remove uma LUT específica do repositório, incluindo o arquivo e os metadados.
     * @param lutId O ID da LUT a ser removida.
     * @throws Exception Se ocorrer um erro durante a remoção do arquivo ou do registro no DB.
     */
    suspend fun deleteLut(lutId: String)

    /**
     * Define uma LUT específica como ativa.
     * Isso implica desativar a LUT ativa anterior (se houver) e ativar a nova.
     * @param lutId O ID da LUT a ser ativada. Pode ser null para desativar todas.
     */
    suspend fun setActiveLut(lutId: String?)

    /**
     * Consulta a LUT atualmente ativa, retornando um Flow.
     * @return Flow contendo a [Lut] ativa ou null se nenhuma estiver ativa.
     */
    fun getActiveLut(): Flow<Lut?>

    /**
     * Valida se um arquivo é um LUT .cube válido.
     * @param file O arquivo a ser validado.
     * @return true se o arquivo for um LUT .cube válido, false caso contrário.
     */
    suspend fun validateLutFile(file: File): Boolean

    /**
     * Gera um ID único para uma nova LUT.
     * @param fileName O nome do arquivo da LUT.
     * @return Um ID único (ex: UUID baseado no nome e timestamp).
     */
    fun generateUniqueId(fileName: String): String

    /**
     * Gera um nome de exibição amigável a partir do nome do arquivo.
     * @param fileName O nome do arquivo (ex: "Cine_Vibrant.cube").
     * @return Um nome de exibição (ex: "Cine Vibrant").
     */
    fun generateDisplayName(fileName: String): String

    /**
     * Sincroniza a base de dados com os arquivos físicos (assets e armazenamento interno).
     */
    suspend fun syncLutsFromDisk()

    /**
     * Importa uma LUT .cube a partir de [uri] (SAF): copia em IO para um temporário com teto de
     * tamanho, valida de verdade (parser), grava em `luts/` com nome sanitizado e registra no
     * banco. Falhas (arquivo inválido, 1D, grande demais) voltam como [Result.failure] com
     * mensagem exibível; nunca lança por dados ruins.
     */
    suspend fun importLut(uri: android.net.Uri): Result<Lut>
}
