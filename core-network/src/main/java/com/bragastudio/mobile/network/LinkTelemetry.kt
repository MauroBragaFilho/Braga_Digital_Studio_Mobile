package com.bragastudio.mobile.network

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Estado de tally enviado pelo OBS (PROGRAM = no ar, PREVIEW = prévia de corte). */
enum class TallyState { OFF, PREVIEW, PROGRAM }

/**
 * Ponte de telemetria entre o app (MediaGraph, captura) e o BDSM Link.
 * Fica em :core-network porque :core-media já depende dele (o contrário criaria ciclo).
 * Quem tem a informação real chama os setters; o [MetadataCollector] lê [snapshot]
 * e publica no WebSocket. O tally faz o caminho inverso: o servidor chama [setTally]
 * ao receber `TALLY_UPDATE` e a UI observa [tally].
 */
@Singleton
class LinkTelemetry @Inject constructor() {

    data class Snapshot(
        val isRecording: Boolean = false,
        val captureSource: String = "--",
        val cameraLens: String = "--",
        val fps: Int = 0,
        val microphone: String = "--",
        val ndiStreamName: String? = null,
    )

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _tally = MutableStateFlow(TallyState.OFF)
    val tally: StateFlow<TallyState> = _tally.asStateFlow()

    fun setRecording(recording: Boolean) = _snapshot.update { it.copy(isRecording = recording) }

    fun setCaptureInfo(source: String, lens: String, fps: Int) = _snapshot.update { it.copy(captureSource = source, cameraLens = lens, fps = fps) }

    fun setMicrophone(name: String) = _snapshot.update { it.copy(microphone = name) }

    /** [name] nulo = NDI desligado. */
    fun setNdi(name: String?) = _snapshot.update { it.copy(ndiStreamName = name) }

    fun setTally(state: TallyState) {
        _tally.value = state
    }
}
