package com.bragastudio.mobile.featuresettings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bragastudio.mobile.common.components.UiText
import com.bragastudio.mobile.core.domain.SettingsRepository
import com.bragastudio.mobile.core.model.Lut
import com.bragastudio.mobile.core.repository.LutRepository
import com.bragastudio.mobile.coremedia.domain.MediaGraph
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LutsViewModel @Inject constructor(
    private val lutRepository: LutRepository,
    private val settingsRepository: SettingsRepository,
    private val mediaGraph: MediaGraph,
) : ViewModel() {

    enum class SortOrder {
        ALPHABETICAL_ASC,
        ALPHABETICAL_DESC,
        IMPORT_DATE_ASC,
        IMPORT_DATE_DESC,
    }

    private val _allLuts = MutableStateFlow<List<Lut>>(emptyList())

    /** LUTs apagadas com "Desfazer" pendente: escondidas da lista até a exclusão valer de verdade. */
    private val pendingRemoval = PendingRemoval()

    val allLuts: StateFlow<List<Lut>> = combine(_allLuts, pendingRemoval.hidden) { luts, hidden ->
        if (hidden.isEmpty()) luts else luts.filter { it.id !in hidden }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _sortOrder = MutableStateFlow(SortOrder.ALPHABETICAL_ASC)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private var unsortedLuts = emptyList<Lut>()

    private val _activeLut = MutableStateFlow<Lut?>(null)
    val activeLut: StateFlow<Lut?> = _activeLut.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<UiText?>(null)
    val errorMessage: StateFlow<UiText?> = _errorMessage.asStateFlow()

    /** Aviso de sucesso (ex.: "LUT importada") exibido em Snackbar pela tela. */
    private val _infoMessage = MutableStateFlow<UiText?>(null)
    val infoMessage: StateFlow<UiText?> = _infoMessage.asStateFlow()

    /**
     * Intensidade persistida da LUT (0..1). Este ViewModel só guarda o valor; quem aplica no
     * render lê [SettingsRepository.lutIntensity] por outro caminho.
     */
    val lutIntensity: StateFlow<Float> = settingsRepository.lutIntensity
        .catch { emit(1f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)

    init {
        // A lista vem do Room; a sincronização com o disco só reconcilia (também roda no start
        // do app e a cada evento do Link), então abrir a tela não bloqueia a exibição.
        viewModelScope.launch {
            try {
                lutRepository.syncLutsFromDisk()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = UiText.of(R.string.lut_err_sync, e.message.orEmpty())
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
                    // O flow nunca termina: o "carregando" acaba na primeira emissão.
                    _isLoading.value = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = UiText.of(R.string.lut_err_load, e.message.orEmpty())
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun observeActiveLut() {
        viewModelScope.launch {
            try {
                lutRepository.getActiveLut().collect { lut ->
                    _activeLut.value = lut
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = UiText.of(R.string.lut_err_active, e.message.orEmpty())
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
                // Ativação atômica no repositório (uma instrução SQL); o flow do Room atualiza a UI.
                lutRepository.setActiveLut(if (lutId.isNullOrEmpty()) null else lutId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = UiText.of(R.string.lut_err_apply, e.message.orEmpty())
            }
        }
    }

    /** Remove a LUT (arquivo + registro). A tela deve pedir confirmação antes de chamar. */
    fun removeLut(lutId: String) {
        viewModelScope.launch {
            try {
                lutRepository.deleteLut(lutId)
                // A lista e a LUT ativa se atualizam sozinhas pelos flows do Room.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = UiText.of(R.string.lut_err_remove, e.message.orEmpty())
            }
        }
    }

    fun deleteLut(lutId: String) = removeLut(lutId)

    /** Passo 1 de "apagar com desfazer": esconde a LUT da lista (nada foi apagado ainda). */
    fun hideForDelete(lutId: String) = pendingRemoval.hide(listOf(lutId))

    /** Usuário tocou em Desfazer. */
    fun undoDelete(lutId: String) = pendingRemoval.restore(listOf(lutId))

    /** O aviso expirou (ou a tela saiu): apaga de verdade (arquivo + registro). */
    fun commitDelete(lutId: String) {
        viewModelScope.launch {
            try {
                lutRepository.deleteLut(lutId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = UiText.of(R.string.lut_err_remove, e.message.orEmpty())
            } finally {
                pendingRemoval.release(listOf(lutId))
            }
        }
    }

    /**
     * Importa o .cube de [fileUri]. A cópia, a validação (parser real; LUT 1D e arquivos grandes
     * demais são rejeitados) e a gravação rodam em IO dentro do [LutRepository].
     */
    fun importLut(fileUri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                lutRepository.importLut(fileUri)
                    .onSuccess { _infoMessage.value = UiText.of(R.string.lut_imported, it.displayName) }
                    .onFailure {
                        _errorMessage.value = it.message?.let { m -> UiText.of(R.string.lut_err_import, m) }
                            ?: UiText.of(R.string.lut_err_import, UiText.Res(R.string.lut_err_invalid_file))
                    }
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Compatibilidade com chamadores antigos; o [Context] não é mais necessário. */
    @Suppress("UNUSED_PARAMETER")
    fun importLut(fileUri: Uri, context: Context) = importLut(fileUri)

    fun renameLut(lutId: String, newName: String) {
        viewModelScope.launch {
            try {
                val trimmed = newName.trim()
                if (trimmed.isEmpty()) {
                    _errorMessage.value = UiText.of(R.string.lut_err_name_empty)
                    return@launch
                }
                val currentLut = _allLuts.value.find { it.id == lutId }
                if (currentLut != null && currentLut.displayName != trimmed) {
                    val count = _allLuts.value.count { it.displayName == trimmed && it.id != lutId }
                    if (count > 0) {
                        _errorMessage.value = UiText.of(R.string.lut_err_name_exists, trimmed)
                        return@launch
                    }
                    lutRepository.updateLut(
                        currentLut.copy(displayName = trimmed, modificationDate = System.currentTimeMillis()),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = UiText.of(R.string.lut_err_rename, e.message.orEmpty())
            }
        }
    }

    /** Persiste a intensidade (chamar ao soltar o slider, não a cada quadro do arrasto). */
    fun setLutIntensity(intensity: Float) {
        viewModelScope.launch {
            try {
                settingsRepository.setLutIntensity(intensity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = UiText.of(R.string.lut_err_intensity, e.message.orEmpty())
            }
        }
    }

    // Funções auxiliares para limpar mensagens já exibidas
    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    fun clearInfoMessage() {
        _infoMessage.value = null
    }

    fun attachSurface(surface: android.view.Surface) = viewModelScope.launch {
        mediaGraph.attachPreviewSurface(surface)
    }

    fun detachSurface() = viewModelScope.launch {
        mediaGraph.detachPreviewSurface()
    }
}
