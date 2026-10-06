package com.bragastudio.mobile.featuresettings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bragastudio.mobile.coremedia.ndi.NdiDiscovery
import com.bragastudio.mobile.coremedia.ndi.NdiDiscoveryPhase
import com.bragastudio.mobile.coremedia.ndi.NdiSortMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/** "Ver na rede": a descoberta só roda enquanto a tela está visível (start/stop pelo ciclo de vida). */
@HiltViewModel
class NdiNetworkViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val discovery: NdiDiscovery,
) : ViewModel() {

    private val _sortMode = MutableStateFlow(NdiSortMode.PROXIMITY)
    val sortMode: StateFlow<NdiSortMode> = _sortMode

    val uiState: StateFlow<NdiRadarUiState> = combine(discovery.state, _sortMode) { state, mode ->
        // A rede só é consultada quando a lista está vazia (para a dica certa).
        val noSources = state.phase == NdiDiscoveryPhase.READY && state.sources.isEmpty()
        NdiNetworkLogic.build(state, mode, hasNetwork = !noSources || LocalIp.current(appContext) != null)
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), NdiRadarUiState.Searching)

    fun setSortMode(mode: NdiSortMode) {
        _sortMode.value = mode
    }

    /** Tela visível: liga o NDIlib_find e o MulticastLock. */
    fun onScreenStart() = discovery.start()

    /** Tela fora de vista: desliga tudo. */
    fun onScreenStop() = discovery.stop()

    fun retry() {
        discovery.stop()
        discovery.start()
    }

    override fun onCleared() {
        discovery.stop()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 3000L
    }
}
