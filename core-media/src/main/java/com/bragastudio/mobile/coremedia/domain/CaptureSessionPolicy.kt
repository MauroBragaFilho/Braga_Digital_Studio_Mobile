package com.bragastudio.mobile.coremedia.domain

/*
 * Política PURA (sem Android) da sessão de captura desacoplada da tela (A3 / item 1.9).
 *
 * CONTRATO (regra "só Monitor ou REC")
 *  - O MediaGraph (@Singleton, escopo de processo) é o dono da câmera, do GL, do RecordManager,
 *    do NdiManager, do BspManager e do áudio. A tela (TextureView) é só um CONSUMIDOR OPCIONAL
 *    de preview.
 *  - Só a GRAVAÇÃO (REC: Preparing/Recording/Stopping) "segura" a sessão fora do Monitor: é uma
 *    ação explícita e perder um take em silêncio seria perda de dados. NDI e BSP NÃO seguram:
 *    ao sair do Monitor sem REC a sessão inteira é encerrada, mesmo com NDI/BSP "ligados"
 *    (continuam habilitados, sem quadros até o Monitor abrir de novo).
 *  - Com REC, o CaptureForegroundService (camera|microphone) ancora o processo; sobe no INÍCIO
 *    do take (Activity ainda visível) e desce quando ele termina (com uma tolerância para reinícios rápidos).
 *  - Sem a permissão correspondente o FGS não é iniciado (ou perde o tipo): comportamento antigo.
 */

/** Quais saídas estão ativas. Só [holdsSession] (REC) decide se a sessão sobrevive à perda da tela. */
data class SessionConsumers(
    val recording: Boolean = false,
    val ndi: Boolean = false,
    val bsp: Boolean = false,
) {
    /** True se algum consumidor SEGURA a sessão fora do Monitor: só a gravação (NDI/BSP não). */
    val holdsSession: Boolean get() = recording

    /** Quantos consumidores estão ativos (contagem de referência). */
    val count: Int get() = (if (recording) 1 else 0) + (if (ndi) 1 else 0) + (if (bsp) 1 else 0)

    companion object {
        /** Monta a partir do estado do take (Preparing/Recording/Stopping contam) e dos flags de NDI/BSP. */
        fun from(recState: RecState, ndiActive: Boolean, bspActive: Boolean) = SessionConsumers(recording = recState !== RecState.Idle, ndi = ndiActive, bsp = bspActive)
    }
}

/** O que fazer quando a surface de preview é solta. */
enum class DetachAction {
    /** Soltar só a surface de preview; câmera, GL, REC e áudio seguem (há gravação em andamento). */
    RELEASE_PREVIEW_ONLY,

    /** Sem gravação: desligamento completo (câmera, renderer, áudio). */
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

    fun detachAction(consumers: SessionConsumers): DetachAction = if (consumers.holdsSession) DetachAction.RELEASE_PREVIEW_ONLY else DetachAction.FULL_SHUTDOWN

    /**
     * Deve-se desligar a sessão inteira agora? Só quando ninguém usa a sessão: sem gravação
     * (NDI/BSP não seguram) E nenhuma tela de preview anexada (e a sessão de fato está de pé).
     */
    fun shouldShutdownSession(consumers: SessionConsumers, previewAttached: Boolean, sessionLive: Boolean): Boolean = sessionLive && !previewAttached && !consumers.holdsSession

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
        if (!consumers.holdsSession) return ForegroundPlan(false, false, false, "sem gravação em andamento")
        if (!hasCameraPermission && !hasMicPermission) {
            return ForegroundPlan(false, false, false, "sem permissão de câmera nem de microfone")
        }
        return ForegroundPlan(true, camera = hasCameraPermission, microphone = hasMicPermission)
    }

    /** Título da notificação persistente (o serviço só existe com REC). */
    fun notificationTitle(consumers: SessionConsumers): String = when {
        consumers.recording && (consumers.ndi || consumers.bsp) -> "Gravando e transmitindo — BDSM"
        consumers.recording -> "Gravando — BDSM"
        else -> "Sessão de captura — BDSM"
    }

    /** Texto secundário da notificação. */
    fun notificationText(consumers: SessionConsumers): String = if (consumers.holdsSession) {
        "Toque para voltar ao monitor. Use Parar para encerrar."
    } else {
        "Encerrando a sessão…"
    }
}
