package com.bragastudio.mobile.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entidade que representa uma LUT (Look-Up Table) no banco de dados Room.
 *
 * Esta entidade armazena metadados e informações sobre arquivos .cube de LUTs,
 * tanto pré-instaladas quanto importadas pelo usuário.
 */
// filePath é único: o caminho (absoluto no disco ou "luts/x" nos assets) identifica a LUT e
// impede duas linhas para o mesmo arquivo (B12/L3). Índice criado na migração 3 -> 4.
@Entity(tableName = "lut_table", indices = [Index(value = ["filePath"], unique = true)])
data class LutEntity(
    /**
     * Chave primária única para identificar a LUT no banco de dados.
     * Pode ser um UUID gerado ou um caminho relativo único baseado no nome e origem.
     */
    @PrimaryKey
    val id: String,

    /**
     * Nome do arquivo da LUT (ex: "Cine_Vibrant.cube").
     * Este é o nome exato do arquivo no sistema de arquivos.
     */
    val fileName: String,

    /**
     * Nome de exibição amigável para a LUT (ex: "Cine Vibrant").
     * Pode ser derivado do fileName ou definido separadamente.
     */
    val displayName: String,

    /**
     * Descrição opcional da LUT.
     */
    val description: String? = null,

    /**
     * Caminho completo no dispositivo onde o arquivo .cube está armazenado.
     * Ex: "/Android/data/com.bragastudio.bsm/files/luts/Cine_Vibrant.cube"
     */
    val filePath: String,

    /**
     * Tipo da LUT (ex: "3D LUT", "1D LUT"). Útil para categorização futura.
     */
    val type: String,

    /**
     * Tamanho do arquivo da LUT em bytes.
     */
    val sizeBytes: Long,

    /**
     * Timestamp Unix da criação ou importação da LUT.
     */
    val creationDate: Long,

    /**
     * Timestamp Unix da última modificação ou importação da LUT.
     */
    val modificationDate: Long,

    /**
     * Indica se esta LUT está atualmente selecionada/aplicada no monitor.
     * Apenas uma LUT deve ser ativa por vez.
     */
    val isActive: Boolean = false,

    /**
     * Indica se a LUT é uma das pré-instaladas (true) ou importada pelo usuário (false).
     */
    val isBuiltIn: Boolean,

    /**
     * Categoria opcional da LUT (ex: "Cinema", "Broadcast", "Personalizado").
     */
    val category: String? = null,

    /**
     * Autor opcional da LUT.
     */
    val author: String? = null,

    /**
     * Versão opcional do arquivo LUT.
     */
    val version: String? = null,

    /**
     * Checksum SHA-256 do arquivo LUT para verificação de integridade.
     */
    val checksumSha256: String? = null,
)
