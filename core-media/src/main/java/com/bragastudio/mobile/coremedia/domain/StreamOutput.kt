package com.bragastudio.mobile.coremedia.domain

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Contrato mínimo comum às saídas de transmissão (NDI e BSP): estado ativo, erros amigáveis
 * ao usuário e parada. A partida (start) e as estatísticas específicas continuam em cada
 * implementação, pois seus parâmetros e métricas diferem (NDI: nome; BSP: resolução/bitrate/host).
 */
interface StreamOutput {
    /** true enquanto a saída está transmitindo. */
    val isActive: StateFlow<Boolean>

    /** Mensagens de erro já legíveis para a UI. */
    val errorEvents: SharedFlow<String>

    /** Para a saída e libera seus recursos (idempotente). */
    suspend fun stop()
}
