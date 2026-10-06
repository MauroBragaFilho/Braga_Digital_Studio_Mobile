package com.bragastudio.mobile.core.model

/**
 * Modelo de domínio para uma LUT (Look-Up Table).
 * Representa uma LUT independente da implementação de persistência (como Room).
 * Este modelo é usado pela UI e pela lógica de negócios.
 *
 * @property id ID único da LUT (geralmente um UUID).
 * @property fileName Nome do arquivo .cube (ex: "Cine_Vibrant.cube").
 * @property displayName Nome de exibição amigável (ex: "Cine Vibrant").
 * @property description Descrição opcional da LUT.
 * @property filePath Caminho completo no dispositivo para o arquivo .cube.
 * @property type Tipo da LUT (ex: "3D LUT", "1D LUT").
 * @property sizeBytes Tamanho do arquivo em bytes.
 * @property creationDate Timestamp da criação/importação.
 * @property modificationDate Timestamp da última modificação/importação.
 * @property isActive Indica se esta LUT está atualmente ativa.
 * @property isBuiltIn Indica se a LUT é pré-instalada.
 * @property category Categoria opcional (ex: "Cinema", "Personalizado").
 * @property author Autor opcional.
 * @property version Versão opcional do arquivo.
 * @property checksumSha256 Checksum SHA-256 do arquivo para verificação de integridade.
 */
data class Lut(
    val id: String,
    val fileName: String,
    val displayName: String,
    val description: String? = null,
    val filePath: String,
    val type: String,
    val sizeBytes: Long,
    val creationDate: Long,
    val modificationDate: Long,
    val isActive: Boolean = false,
    val isBuiltIn: Boolean,
    val category: String? = null,
    val author: String? = null,
    val version: String? = null,
    val checksumSha256: String? = null,
)
