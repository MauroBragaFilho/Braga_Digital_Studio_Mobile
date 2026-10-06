package com.bragastudio.mobile.coremedia.domain

/*
 * Política PURA (sem Android) da sessão de captura desacoplada da tela (A3 / item 1.9).
 *
 * CONTRATO
 *  - O MediaGraph (@Singleton, escopo de processo) é o dono da câmera, do GL, do RecordManager,
 *    do NdiManager, do BspManager e do áudio. A tela (TextureView) é só um CONSUMIDOR OPCIONAL
 *    de preview: soltá-la nunca para a captura enquanto houver REC/NDI/BSP ativos.
 *  - "Consumidor ativo" = REC (Preparing/Recording/Stopping), NDI ou BSP em andamento.
 *  - Enquanto houver consumidor ativo, o CaptureForegroundService (camera|microphone) ancora o
 *    processo; ele sobe no INÍCIO do consumidor (Activity ainda visível) e desce quando o último
 *    termina (com uma tolerância para reinícios rápidos, ex.: renomear o NDI).
 *  - Sem consumidor e sem preview, a sessão inteira (câmera, renderer, microfone) é desligada.
 *  - Sem a permissão correspondente o FGS não é iniciado (ou perde o tipo): comportamento antigo.
 */

/** Quais saídas estão ativas. É o que decide se a sessão sobrevive à perda da tela. */
data class SessionConsumers(
    val recording: Boolean = false,
    val ndi: Boolean = false,
    val bsp: Boolean = false,
) {
    /** True se qualquer saída precisa da câmera/GL viva. */
    val anyActive: Boolean get() = recording || ndi || bsp

    /** Quantos consumidores estão ativos (contagem de referência). */
    val count: Int get() = (if (recording) 1 else 0) + (if (ndi) 1 else 0) + (if (bsp) 1 else 0)

    companion object {
        /** Monta a partir do estado do take (Preparing/Recording/Stopping contam) e dos flags de NDI/BSP. */
        fun from(recState: RecState, ndiActive: Boolean, bspActive: Boolean) = SessionConsumers(recording = recState !== RecState.Idle, ndi = ndiActive, bsp = bspActive)
    }
}

/** O que fazer quando a surface de preview é solta. */
enum class DetachAction {
    /** Soltar só a surface de preview; câmera, GL, REC/NDI/BSP e áudio seguem. */
    RELEASE_PREVIEW_ONLY,

    /** Nada ativo: desligamento completo (câmera, renderer, áudio). */
    FULL_SHUTDOWN,
}

/** Decisão do foreground service: quais tipos declarar, ou por que não iniciar. */
data class ForegroundPlan(
    val start: Boolean,
    val camera: Boolean,
    val microphone: Boolean,
    val reason: String? = null,
)

object CaptureSessionPolicy {

    /** Tolerância antes de dar a sessão por encerrada (reinícios de NDI/BSP passam por "inativo" por instantes). */
    const val INACTIVE_GRACE_MS = 1_500L

    fun detachAction(consumers: SessionConsumers): DetachAction = if (consumers.anyActive) DetachAction.RELEASE_PREVIEW_ONLY else DetachAction.FULL_SHUTDOWN

    /**
     * Deve-se desligar a sessão inteira agora? Só quando ninguém usa a sessão: nenhum consumidor
     * ativo E nenhuma tela de preview anexada (e a sessão de fato está de pé).
     */
    fun shouldShutdownSession(consumers: SessionConsumers, previewAttached: Boolean, sessionLive: Boolean): Boolean = sessionLive && !previewAttached && !consumers.anyActive

    /**
     * Plano do FGS. No Android 14+ (API 34) o tipo `camera` exige CAMERA concedida e `microphone`
     * exige RECORD_AUDIO; sem nenhuma das duas não há o que ancorar. Sem câmera mas com mic (ou
     * vice-versa) sobe só com o tipo permitido. Em versões anteriores a mesma regra é aplicada
     * (conservadora): o app só captura com as permissões concedidas.
     */
    fun planForeground(
        consumers: SessionConsumers,
        hasCameraPermission: Boolean,
        hasMicPermission: Boolean,
    ): ForegroundPlan {
        if (!consumers.anyActive) return ForegroundPlan(false, false, false, "nenhum consumidor ativo")
        if (!hasCameraPermission && !hasMicPermission) {
            return ForegroundPlan(false, false, false, "sem permissão de câmera nem de microfone")
        }
        return ForegroundPlan(true, camera = hasCameraPermission, microphone = hasMicPermission)
    }

    /**
     * O microfone é necessário para: REC (a gravação recebe áudio sempre), NDI com áudio ligado e o
     * VU do Preview enquanto a tela de preview está anexada.
     */
    fun audioNeeded(consumers: SessionConsumers, previewAttached: Boolean, ndiAudioEnabled: Boolean): Boolean = previewAttached || consumers.recording || (consumers.ndi && ndiAudioEnabled)

    /** Título da notificação persistente. */
    fun notificationTitle(consumers: SessionConsumers): String = when {
        consumers.recording && (consumers.ndi || consumers.bsp) -> "Gravando e transmitindo — BDSM"
        consumers.recording -> "Gravando — BDSM"
        consumers.ndi && consumers.bsp -> "Transmitindo (NDI e BSP) — BDSM"
        consumers.ndi -> "Transmitindo (NDI) — BDSM"
        consumers.bsp -> "Transmitindo (BSP) — BDSM"
        else -> "Sessão de captura — BDSM"
    }

    /** Texto secundário da notificação. */
    fun notificationText(consumers: SessionConsumers): String = if (consumers.anyActive) {
        "Toque para voltar ao monitor. Use Parar para encerrar."
    } else {
        "Encerrando a sessão…"
    }
}
