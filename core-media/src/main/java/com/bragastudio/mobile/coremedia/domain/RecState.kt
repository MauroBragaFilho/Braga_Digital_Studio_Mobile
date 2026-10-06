package com.bragastudio.mobile.coremedia.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estado do ciclo de vida de um take (M17). Lógica pura, sem dependência de Android,
 * para ser testada em JVM.
 *
 *   Idle ──prepare──▶ Preparing ──start──▶ Recording ──stop──▶ Stopping ──fim──▶ Idle
 *                         │ (falha/cancel)                                       ▲
 *                         └──────────────────────────────────────────────────────┘
 *
 * A UI usa [RecState] para desabilitar o botão REC fora de [Idle]/[Recording]
 * (em Preparing/Stopping qualquer toque seria ignorado de qualquer forma).
 */
sealed interface RecState {
    object Idle : RecState {
        override fun toString() = "Idle"
    }
    object Preparing : RecState {
        override fun toString() = "Preparing"
    }
    object Recording : RecState {
        override fun toString() = "Recording"
    }
    object Stopping : RecState {
        override fun toString() = "Stopping"
    }
}

/** True quando o botão REC deve ficar desabilitado (transição em curso). */
val RecState.isTransitioning: Boolean
    get() = this === RecState.Preparing || this === RecState.Stopping

/**
 * Máquina de estados thread-safe. Toda transição é um compare-and-set atômico:
 * dois toques simultâneos em REC não conseguem preparar duas vezes (o segundo
 * recebe `false` e não toca em nenhum codec).
 */
class RecStateMachine {
    private val _state = MutableStateFlow<RecState>(RecState.Idle)
    val state: StateFlow<RecState> = _state.asStateFlow()

    val current: RecState get() = _state.value

    /** Idle → Preparing. */
    fun tryBeginPrepare(): Boolean = _state.compareAndSet(RecState.Idle, RecState.Preparing)

    /** Preparing → Recording. */
    fun tryStart(): Boolean = _state.compareAndSet(RecState.Preparing, RecState.Recording)

    /** Recording → Stopping. */
    fun tryBeginStop(): Boolean = _state.compareAndSet(RecState.Recording, RecState.Stopping)

    /** Preparing → Idle (falha ou cancelamento da preparação). */
    fun abortPrepare(): Boolean = _state.compareAndSet(RecState.Preparing, RecState.Idle)

    /** Stopping → Idle (finalização concluída). */
    fun finishStop(): Boolean = _state.compareAndSet(RecState.Stopping, RecState.Idle)

    /** Saída de emergência: volta a Idle de qualquer estado (após liberar tudo). */
    fun forceIdle() {
        _state.value = RecState.Idle
    }
}

/** Por que um take terminou. */
enum class StopReason { USER, SOURCE_LOST, DISK_FULL, ENCODER_ERROR }

/**
 * Eventos pontuais da gravação para a UI avisar o operador (M15 / L1).
 * Emitidos via SharedFlow (sem replay): uma tela nova não reabre aviso antigo.
 */
sealed interface RecordingEvent {
    /** A fonte de vídeo caiu (Camera2 em ERROR, USB desplugado, Sony fora de alcance). */
    data class SourceLost(val message: String) : RecordingEvent

    /** Espaço livre abaixo do piso durante o take; o arquivo é finalizado com segurança. */
    data class DiskFull(val availableBytes: Long) : RecordingEvent

    /** O encoder/muxer falhou durante o take. */
    data class EncoderError(val message: String) : RecordingEvent

    /** O take foi finalizado (qualquer motivo). [filePath] é o arquivo local, se existir. */
    data class Stopped(val reason: StopReason, val filePath: String?) : RecordingEvent
}
