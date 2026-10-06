package com.bragastudio.mobile.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Ciclo de vida do servidor HTTP/WS do Link (A9 / B36). */
sealed interface ServerState {
    object Stopped : ServerState
    object Starting : ServerState
    data class Running(val port: Int) : ServerState

    /** Falha ao abrir a porta (ex.: BindException). Uma nova chamada a start() tenta de novo. */
    data class Failed(val reason: String) : ServerState
}

/**
 * Estado global (por processo) do servidor. Fica fora do [LinkServer] para que
 * [LinkServerController] possa observá-lo sem depender dele.
 */
object LinkServerStatus {
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    internal fun set(value: ServerState) {
        _state.value = value
    }
}
