package com.bragastudio.mobile.featuresettings

/** Estado da tela BSP: um círculo, um rótulo e um botão (mesma ideia da tela NDI). */
enum class BspScreenPhase {
    /** Desligado. */
    OFF,

    /** Ligado, mas a fonte ainda não subiu (anúncio e controle abrindo). */
    STARTING,

    /** No ar na rede, sem nenhum receptor recebendo. */
    WAITING_RECEIVER,

    /** Há receptor conectado, mas o Monitor está fechado: não há quadros (o BSP não segura a câmera). */
    WAITING_MONITOR,

    /** Enviando vídeo e áudio. */
    STREAMING,

    /** Erro ao iniciar. */
    ERROR,
}

/** Regras puras da tela BSP (testáveis sem Android). */
object BspScreenLogic {
    /** Tempo que a fonte pode levar para subir antes de a tela oferecer "Tentar novamente". */
    const val START_TIMEOUT_MS = 8_000L

    /**
     * [enabled] = o usuário ligou; [active] = a fonte BSP está no ar; [failed] = o motor reportou erro ou
     * estourou o tempo de início; [receiversStreaming] = receptores com READY e sem pausa;
     * [captureFlowing] = há quadros da câmera (Monitor aberto).
     */
    fun phase(enabled: Boolean, active: Boolean, failed: Boolean, receiversStreaming: Int, captureFlowing: Boolean): BspScreenPhase = when {
        !enabled -> BspScreenPhase.OFF
        active && receiversStreaming <= 0 -> BspScreenPhase.WAITING_RECEIVER
        active && !captureFlowing -> BspScreenPhase.WAITING_MONITOR
        active -> BspScreenPhase.STREAMING
        failed -> BspScreenPhase.ERROR
        else -> BspScreenPhase.STARTING
    }

    /** Taxa para a tela: uma casa decimal, ou "--" sem dado. */
    fun formatBitrate(mbps: Float): String = if (mbps <= 0f) "--" else "%.1f Mbps".format(java.util.Locale.US, mbps)

    fun formatRtt(ms: Long): String = if (ms <= 0L) "--" else "$ms ms"

    fun formatLoss(percent: Float): String = "%.1f%%".format(java.util.Locale.US, percent)

    fun formatFps(fps: Int): String = if (fps <= 0) "--" else "$fps fps"

    /** Mensagem de erro curta e legível (corta textos técnicos longos). */
    fun readableError(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.length > MAX_ERROR_CHARS) it.take(MAX_ERROR_CHARS - 1) + "…" else it }

    private const val MAX_ERROR_CHARS = 140
}
