package com.bragastudio.mobile.permissions

import android.Manifest

/** Permissões de execução que o app explica e pede de forma contextual. */
enum class AppPermission(val manifestName: String) {
    Camera(Manifest.permission.CAMERA),
    Microphone(Manifest.permission.RECORD_AUDIO),
    Notifications(Manifest.permission.POST_NOTIFICATIONS),
}

/** Situação de uma permissão do ponto de vista da UI. */
enum class PermissionStatus {
    Granted,

    /** Ainda não concedida, mas o sistema ainda mostra o diálogo ao pedir. */
    Denied,

    /** Negada e o sistema não mostra mais o diálogo: só pelas configurações do app. */
    PermanentlyDenied,
}

/** Passo que a entrada no Monitor exige antes de abrir. */
enum class MonitorEntryStep {
    /** Tudo certo: abrir o Monitor. */
    Ready,

    /** Explicar e pedir a câmera. */
    NeedCamera,

    /** Câmera negada de vez: levar às configurações do sistema. */
    CameraBlocked,

    /** Oferecer o microfone (opcional; dá para seguir sem ele). */
    OfferMicrophone,
}

/** Regras puras (testadas em JVM) do fluxo de permissões. */
object PermissionLogic {
    /**
     * [askedBefore] = o app já pediu essa permissão alguma vez; [shouldShowRationale] vem de
     * `shouldShowRequestPermissionRationale`. Sem ter pedido nunca, "rationale = false" significa
     * "ainda não perguntei" e não "negada de vez".
     */
    fun status(granted: Boolean, askedBefore: Boolean, shouldShowRationale: Boolean): PermissionStatus = when {
        granted -> PermissionStatus.Granted
        askedBefore && !shouldShowRationale -> PermissionStatus.PermanentlyDenied
        else -> PermissionStatus.Denied
    }

    /** Notificações só são permissão de execução a partir do Android 13 (API 33). */
    fun notificationsRuntime(sdkInt: Int): Boolean = sdkInt >= 33

    /**
     * Decide o próximo passo ao abrir o Monitor. A câmera é obrigatória; o microfone é opcional
     * e oferecido uma única vez ([micOfferDismissed]) — dá para gravar/transmitir sem áudio.
     */
    fun monitorEntryStep(
        camera: PermissionStatus,
        microphone: PermissionStatus,
        micOfferDismissed: Boolean,
    ): MonitorEntryStep = when {
        camera == PermissionStatus.PermanentlyDenied -> MonitorEntryStep.CameraBlocked
        camera != PermissionStatus.Granted -> MonitorEntryStep.NeedCamera
        microphone == PermissionStatus.Denied && !micOfferDismissed -> MonitorEntryStep.OfferMicrophone
        else -> MonitorEntryStep.Ready
    }

    /** O onboarding mostra como "pendente" o que ainda não foi concedido e é relevante. */
    fun pendingCount(statuses: Map<AppPermission, PermissionStatus>): Int = AppPermission.entries.count { statuses[it] != PermissionStatus.Granted }
}
