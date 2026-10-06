package com.bragastudio.mobile.network

import kotlinx.serialization.Serializable

/**
 * Estado publicado a ~2 Hz no WebSocket `/ws/link`. Os padrões representam "sem dado"
 * (o [MetadataCollector] preenche com a telemetria real); precisam ser serializados
 * sempre — ver [LinkJson].
 */
@Serializable
data class LinkState(
    val deviceName: String = "BDSM Device",
    val batteryLevel: Int = 0,
    val isCharging: Boolean = false,
    val captureSource: String = "--",
    val cameraLens: String = "--",
    val fps: Int = 0,
    val microphone: String = "--",
    val isRecording: Boolean = false,
    /** Nome do stream NDI; nulo = NDI desligado. */
    val ndiStreamName: String? = null,
    /** "OFF", "PREVIEW" ou "PROGRAM" (nome de [TallyState]). */
    val tally: String = TallyState.OFF.name,
)
