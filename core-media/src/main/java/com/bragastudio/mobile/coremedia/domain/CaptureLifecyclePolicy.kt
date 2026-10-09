package com.bragastudio.mobile.coremedia.domain

/*
 * Política PURA (sem Android) do ciclo de vida de câmera e microfone.
 *
 * REGRA: câmera, microfone e serviço de captura só ficam ativos (a) com o MONITOR visível ou
 * (b) enquanto houver GRAVAÇÃO (REC: Preparing/Recording/Stopping) em andamento. NDI e BSP NÃO
 * seguram a captura fora do Monitor: ao sair do Monitor sem REC a sessão é totalmente encerrada
 * (CameraDevice, AudioRecord, SCO, serviço de primeiro plano), mesmo com NDI/BSP "ligados" (estado
 * persistido; sem quadros até o Monitor abrir de novo, quando o envio é religado sozinho).
 * "REC ativo" é lido a cada instante: REC parou fora do Monitor -> encerra.
 *
 * Uma sessão de captura sem estar viva (ex.: NDI persistido como ligado ao abrir o app, sem
 * nunca entrar no Monitor) NÃO tem o que segurar: não abre microfone nem sobe o serviço.
 */
object CaptureLifecyclePolicy {

    /** A captura (sessão de câmera + microfone) deve ficar de pé? */
    fun shouldHoldCapture(monitorVisible: Boolean, recording: Boolean): Boolean = monitorVisible || recording

    /** Variante sobre [SessionConsumers]. */
    fun shouldHoldCapture(monitorVisible: Boolean, consumers: SessionConsumers): Boolean = shouldHoldCapture(monitorVisible, consumers.holdsSession)

    /**
     * O serviço de primeiro plano (camera|microphone) deve existir? Só com a sessão de captura
     * viva E gravação: o Monitor visível já tem a Activity em primeiro plano e não precisa dele.
     */
    fun shouldRunForegroundService(sessionLive: Boolean, recording: Boolean): Boolean = sessionLive && recording

    /**
     * O microfone (AudioRecord) deve estar aberto? Só com a sessão viva e: Monitor visível (VU e
     * áudio do NDI enquanto o Monitor está aberto) ou REC (sempre grava áudio).
     */
    fun shouldCaptureAudio(sessionLive: Boolean, monitorVisible: Boolean, recording: Boolean): Boolean = sessionLive && (monitorVisible || recording)

    /** Transição ao mudar o estado: manter ou encerrar a captura. */
    enum class Transition { KEEP, CLOSE }

    fun transition(monitorVisible: Boolean, consumers: SessionConsumers): Transition = if (shouldHoldCapture(monitorVisible, consumers)) Transition.KEEP else Transition.CLOSE
}
