package com.bragastudio.mobile.featuresettings

import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.bragastudio.mobile.coremedia.ndi.NdiPreviewState
import com.bragastudio.mobile.coremedia.ndi.NdiReceiver
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Preview em tela cheia de UMA fonte. O receptor só existe enquanto a tela está visível. */
@HiltViewModel
class NdiPreviewViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val receiver: NdiReceiver,
) : ViewModel() {

    /** Nome NDI da fonte (não é o IP). */
    val sourceName: String = savedState.get<String>(ARG_NAME).orEmpty()

    /** Nome para o canto da tela (para BDSM, só o aparelho; sem IPs). */
    val displayName: String = com.bragastudio.mobile.coremedia.ndi.NdiSourceNames.displayName(sourceName)

    val state: StateFlow<NdiPreviewState> = receiver.state

    /** Tela visível: abre o receptor. */
    fun onScreenStart() {
        if (sourceName.isNotEmpty()) receiver.open(sourceName)
    }

    /** Tela fora de vista (voltar, tela apagada, segundo plano): fecha o receptor. */
    fun onScreenStop() = receiver.close()

    fun retry() = onScreenStart()

    fun setSurface(surface: Surface?) = receiver.setSurface(surface)

    override fun onCleared() = receiver.close()

    companion object {
        const val ARG_NAME = "name"
    }
}
