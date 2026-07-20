package com.bragastudio.mobile.featuresettings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bragastudio.mobile.core.model.Lut
import com.bragastudio.mobile.core.repository.LutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import com.bragastudio.mobile.coremedia.domain.MediaGraph
import javax.inject.Inject

@HiltViewModel
class LutsViewModel @Inject constructor(
    private val lutRepository: LutRepository,
    private val mediaGraph: MediaGraph
) : ViewModel() {

    enum class SortOrder {
        ALPHABETICAL_ASC,
        ALPHABETICAL_DESC,
        IMPORT_DATE_ASC,
        IMPORT_DATE_DESC
    }

    private val _allLuts = MutableStateFlow<List<Lut>>(emptyList())
    val allLuts: StateFlow<List<Lut>> = _allLuts.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.ALPHABETICAL_ASC)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()
    
    private var unsortedLuts = emptyList<Lut>()

    private val _activeLut = MutableStateFlow<Lut?>(null)
    val activeLut: StateFlow<Lut?> = _activeLut.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                lutRepository.syncLutsFromDisk()
            } catch (e: Exception) {
                _errorMessage.value = "Erro ao sincronizar LUTs: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
        loadAllLuts()
        observeActiveLut()
    }

    private fun loadAllLuts() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                lutRepository.getAllLuts().collect { luts ->
                    unsortedLuts = luts
                    applySorting()
                }
            } catch (e: Exception) {
                _errorMessage.value = "Erro ao carregar LUTs: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun observeActiveLut() {
        viewModelScope.launch {
            lutRepository.getActiveLut().collect { lut ->
                _activeLut.value = lut
            }
        }
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
        applySorting()
    }

    private fun applySorting() {
        _allLuts.value = when (_sortOrder.value) {
            SortOrder.ALPHABETICAL_ASC -> unsortedLuts.sortedBy { it.displayName.lowercase() }
            SortOrder.ALPHABETICAL_DESC -> unsortedLuts.sortedByDescending { it.displayName.lowercase() }
            SortOrder.IMPORT_DATE_ASC -> unsortedLuts.sortedBy { it.creationDate }
            SortOrder.IMPORT_DATE_DESC -> unsortedLuts.sortedByDescending { it.creationDate }
        }
    }

    fun applyLut(lutId: String?) {
        viewModelScope.launch {
            try {
                lutRepository.setActiveLut(if (lutId.isNullOrEmpty()) null else lutId)
                // Atualização otimista
                _activeLut.value = if (lutId.isNullOrEmpty()) null else _allLuts.value.find { it.id == lutId }
            } catch (e: Exception) {
                _errorMessage.value = "Erro ao aplicar LUT: ${e.message}"
            }
        }
    }

    fun removeLut(lutId: String) {
        viewModelScope.launch {
            try {
                lutRepository.deleteLut(lutId)
                // A atualização da lista de allLuts será feita automaticamente via loadAllLuts
            } catch (e: Exception) {
                _errorMessage.value = "Erro ao remover LUT: ${e.message}"
            }
        }
    }

    fun importLut(fileUri: android.net.Uri, context: Context) { // Context necessário para resolver o URI
        viewModelScope.launch {
            _isLoading.value = true
            var tempFile: File? = null // Declare a variável com escopo suficiente
            try {
                // 1. Validar o arquivo via Repository
                val inputStream = context.contentResolver.openInputStream(fileUri)
                tempFile = java.io.File.createTempFile("lut_import_", ".cube")
                inputStream?.use { it.copyTo(tempFile.outputStream()) }

                if (!lutRepository.validateLutFile(tempFile)) {
                    throw IllegalArgumentException("Arquivo LUT inválido.")
                }

                // 2. Copiar o arquivo para o diretório de LUTs do app
                val fileName = context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    cursor.moveToFirst()
                    cursor.getString(nameIndex)
                } ?: "imported_lut_${System.currentTimeMillis()}.cube"

                // Supondo um diretório padrão (pode ser definido no Repository ou SettingsRepository)
                val appLutDir = File(context.getExternalFilesDir(null), "luts")
                appLutDir.mkdirs()
                val destinationFile = File(appLutDir, fileName)

                tempFile.copyTo(destinationFile, overwrite = true)

                // 3. Gerar metadados e criar objeto Lut
                val lut = Lut(
                    id = lutRepository.generateUniqueId(fileName), // Usar função do Repository
                    fileName = fileName,
                    displayName = lutRepository.generateDisplayName(fileName), // Usar função do Repository
                    description = null, // Pode ser obtido do cabeçalho do .cube ou informado pelo usuário
                    filePath = destinationFile.absolutePath,
                    type = "3D LUT", // Determinar via parser ou padrão
                    sizeBytes = destinationFile.length(),
                    creationDate = System.currentTimeMillis(),
                    modificationDate = System.currentTimeMillis(),
                    isActive = false, // Não ativa por padrão
                    isBuiltIn = false // Importada pelo usuário
                    // Preencher outros campos conforme necessário (category, author, checksum, etc.)
                )

                // 4. Salvar no Repository (isso deve copiar o arquivo e salvar os metadados no DB)
                lutRepository.insertLut(lut, destinationFile)

            } catch (e: Exception) {
                _errorMessage.value = "Erro ao importar LUT: ${e.message}"
                e.printStackTrace() // Log para debug
            } finally {
                _isLoading.value = false
                tempFile?.delete() // Limpar arquivo temporário, se existir
            }
        }
    }

    fun renameLut(lutId: String, newName: String) {
        viewModelScope.launch {
            try {
                val currentLut = _allLuts.value.find { it.id == lutId }
                if (currentLut != null && currentLut.displayName != newName) {
                    // Validação de nome único (exemplo)
                    val count = _allLuts.value.count { it.displayName == newName && it.id != lutId }
                    if (count > 0) {
                         _errorMessage.value = "Já existe uma LUT com o nome '$newName'."
                         return@launch
                    }

                    val updatedLut = currentLut.copy(
                        displayName = newName,
                        modificationDate = System.currentTimeMillis()
                    )
                    lutRepository.updateLut(updatedLut)
                }
            } catch (e: Exception) {
                _errorMessage.value = "Erro ao renomear LUT: ${e.message}"
            }
        }
    }

    fun deleteLut(lutId: String) {
        viewModelScope.launch {
            try {
                lutRepository.deleteLut(lutId)
                if (_activeLut.value?.id == lutId) {
                    lutRepository.setActiveLut(null)
                    _activeLut.value = null
                }
            } catch (e: Exception) {
                _errorMessage.value = "Erro ao deletar LUT: ${e.message}"
            }
        }
    }

    // Função auxiliar para limpar mensagem de erro
    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    fun attachSurface(surface: android.view.Surface) = viewModelScope.launch { 
        mediaGraph.attachPreviewSurface(surface) 
    }
    
    fun detachSurface() = viewModelScope.launch { 
        mediaGraph.detachPreviewSurface() 
    }
}