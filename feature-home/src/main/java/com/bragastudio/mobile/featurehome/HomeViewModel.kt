package com.bragastudio.mobile.featurehome

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bragastudio.mobile.core.database.RecordingStatus
import com.bragastudio.mobile.core.domain.DisplayName
import com.bragastudio.mobile.core.domain.NdiNaming
import com.bragastudio.mobile.core.domain.SettingsRepository
import com.bragastudio.mobile.core.repository.RecordingRepository
import com.bragastudio.mobile.corecapture.domain.CaptureState
import com.bragastudio.mobile.corecapture.status.CameraStatusProvider
import com.bragastudio.mobile.coremedia.domain.MediaGraph
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class HomeViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    recordingRepository: RecordingRepository,
    mediaGraph: MediaGraph,
    @ApplicationContext context: Context,
    private val cameraStatusProvider: CameraStatusProvider,
) : ViewModel() {

    // Nome do aparelho lido uma vez (só local; nunca sai do aparelho).
    private val deviceName: String = NdiNaming.deviceName(context)

    // Nome da saudação: o escolhido em Ajustes; vazio = nome do aparelho; genérico demais = sem nome.
    private val greetingName: Flow<String?> = settingsRepository.displayName
        .map { DisplayName.resolve(it, deviceName) }
        .distinctUntilChanged()

    private val formatSummary: Flow<String> = settingsRepository.videoSettings
        .map { HomeStatus.plainFormat(it) }
        .distinctUntilChanged()

    // Gravação em andamento (linha IN_PROGRESS recente): só muda quando o Room muda.
    private val recordingStart: Flow<Long?> = recordingRepository.getAllRecordings()
        .map { rows ->
            HomeStatus.activeRecordingStart(
                rows.filter { it.status == RecordingStatus.IN_PROGRESS }.map { it.createdAt },
                System.currentTimeMillis(),
            )
        }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    // Câmera "ativa" = sessão aberta (Monitor, gravação ou NDI); com a Home sozinha o grafo fica ocioso.
    private val cameraActive: Flow<Boolean> = mediaGraph.captureState
        .map { it == CaptureState.READY || it == CaptureState.RECORDING }
        .distinctUntilChanged()

    private val camera = combine(cameraActive, cameraStatusProvider.status) { active, status -> active to status }

    val uiState: StateFlow<HomeUiState> = combine(formatSummary, greetingName, recordingStart, camera) { format, name, rec, (active, status) ->
        HomeUiState(
            formatSummary = format,
            recordingStartedAt = rec,
            cameraActive = active,
            camera = status,
            greetingName = name,
            loaded = true,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState(),
    )

    /** Relê as câmeras (ao voltar para a Home: permissão concedida, câmera USB conectada...). */
    fun refreshCamera() = cameraStatusProvider.refresh()
}
