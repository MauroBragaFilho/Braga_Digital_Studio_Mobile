package com.bragastudio.mobile.featurehome

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bragastudio.mobile.core.database.RecordingEntity
import com.bragastudio.mobile.core.repository.RecordingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

import android.content.Context
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import com.bragastudio.mobile.core.domain.SettingsRepository
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.flow.first

import com.bragastudio.mobile.core.domain.HardwareMetrics
import com.bragastudio.mobile.core.domain.HardwareMonitorService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class RecordingsViewModel @Inject constructor(
    private val repository: RecordingRepository,
    private val settingsRepository: SettingsRepository,
    private val hardwareMonitorService: HardwareMonitorService
) : ViewModel() {

    enum class SortOrder {
        DATE_DESC, DATE_ASC, SIZE_DESC, SIZE_ASC
    }

    private val _recordings = MutableStateFlow<List<RecordingEntity>>(emptyList())
    
    private val _filteredRecordings = MutableStateFlow<List<RecordingEntity>>(emptyList())
    val filteredRecordings: StateFlow<List<RecordingEntity>> = _filteredRecordings.asStateFlow()
    
    private val _isFavoritesOnly = MutableStateFlow(false)
    val isFavoritesOnly: StateFlow<Boolean> = _isFavoritesOnly.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.DATE_DESC)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()
    
    private val _isGridView = MutableStateFlow(true)
    val isGridView: StateFlow<Boolean> = _isGridView.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val hardwareMetrics: StateFlow<HardwareMetrics> = hardwareMonitorService.metrics.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HardwareMetrics()
    )

    init {
        syncAndLoad()
    }

    fun syncAndLoad() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                repository.syncFileSystemWithDatabase()
                repository.getAllRecordings().collectLatest { list ->
                    // Filter completed
                    _recordings.value = list.filter { it.status == "COMPLETED" }
                    applyFiltersAndSorting()
                }
            } catch (e: Exception) {
                // handle error
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun setFavoritesOnly(onlyFavorites: Boolean) {
        _isFavoritesOnly.value = onlyFavorites
        applyFiltersAndSorting()
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
        applyFiltersAndSorting()
    }

    fun setGridView(isGrid: Boolean) {
        _isGridView.value = isGrid
    }

    private fun applyFiltersAndSorting() {
        var result = _recordings.value
        
        if (_isFavoritesOnly.value) {
            result = result.filter { it.isFavorite }
        }
        
        result = when (_sortOrder.value) {
            SortOrder.DATE_DESC -> result.sortedByDescending { it.createdAt }
            SortOrder.DATE_ASC -> result.sortedBy { it.createdAt }
            SortOrder.SIZE_DESC -> result.sortedByDescending { it.sizeBytes }
            SortOrder.SIZE_ASC -> result.sortedBy { it.sizeBytes }
        }
        
        _filteredRecordings.value = result
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch {
            repository.toggleFavorite(id)
        }
    }
    
    fun toggleFavoriteSelected(ids: Set<String>, isFavorite: Boolean) {
        viewModelScope.launch {
            ids.forEach { id ->
                val entity = _recordings.value.find { it.id == id }
                if (entity != null && entity.isFavorite != isFavorite) {
                    repository.toggleFavorite(id)
                }
            }
        }
    }

    fun deleteRecording(id: String) {
        viewModelScope.launch {
            repository.deleteRecording(id)
        }
    }
    
    fun deleteSelectedRecordings(ids: Set<String>) {
        viewModelScope.launch {
            ids.forEach { id ->
                repository.deleteRecording(id)
            }
        }
    }

    fun renameRecording(id: String, newName: String) {
        viewModelScope.launch {
            repository.renameRecording(id, newName)
        }
    }

    fun saveToGallery(id: String, context: Context) {
        viewModelScope.launch {
            try {
                // Obter o Recording
                val recordingsList = _recordings.value
                val recording = recordingsList.find { it.id == id } ?: return@launch
                val sourceFile = File(recording.filePath)
                if (!sourceFile.exists()) return@launch

                // Opcional: checar diretorio customizado do SettingsRepository
                val settings = settingsRepository.videoSettings.first()
                val dirUriString = settings.recordingDirectoryUri
                
                if (!dirUriString.isNullOrEmpty()) {
                    // Export to user designated folder via SAF
                    val uri = android.net.Uri.parse(dirUriString)
                    val documentFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                    val newFile = documentFile?.createFile("video/mp4", recording.fileName)
                    if (newFile != null) {
                        context.contentResolver.openOutputStream(newFile.uri)?.use { outputStream ->
                            FileInputStream(sourceFile).use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                    }
                } else {
                    // Export via MediaStore to public Gallery
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, recording.fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/BDSM")
                    }
                    
                    val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                            FileInputStream(sourceFile).use { inputStream ->
                                inputStream.copyTo(outputStream)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
