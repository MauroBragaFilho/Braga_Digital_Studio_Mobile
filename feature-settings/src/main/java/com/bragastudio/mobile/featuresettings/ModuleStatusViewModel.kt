package com.bragastudio.mobile.featuresettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bragastudio.mobile.core.database.RecordingStatus
import com.bragastudio.mobile.core.domain.SettingsRepository
import com.bragastudio.mobile.core.repository.LutRepository
import com.bragastudio.mobile.core.repository.RecordingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Estados de uma linha para os cartões de módulo da Home (quantidade de gravações, de LUTs e se o
 * NDI está ativo). Contagens em Flow: só recompõem quando o número muda.
 */
@HiltViewModel
class ModuleStatusViewModel @Inject constructor(
    recordingRepository: RecordingRepository,
    lutRepository: LutRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    /** Gravações visíveis na galeria (concluídas ou interrompidas); null até a primeira leitura. */
    val recordings: StateFlow<Int?> = recordingRepository.getAllRecordings()
        .map { rows -> rows.count { it.status in RecordingStatus.VISIBLE } as Int? }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val luts: StateFlow<Int?> = lutRepository.getAllLuts()
        .map { it.size as Int? }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val ndiEnabled: StateFlow<Boolean> = settingsRepository.ndiSettings
        .map { it.isEnabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    val bspEnabled: StateFlow<Boolean> = settingsRepository.bspSettings
        .map { it.isEnabled }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
