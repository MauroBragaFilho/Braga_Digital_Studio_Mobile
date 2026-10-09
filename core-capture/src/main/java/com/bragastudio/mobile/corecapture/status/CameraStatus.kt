package com.bragastudio.mobile.corecapture.status

import com.bragastudio.mobile.core.domain.VideoSources

/** Estado da câmera para a Home/NDI, SEM abrir a câmera nem iniciar preview. */
sealed interface CameraStatus {
    /** Primeira sondagem ainda em andamento (a UI mostra um esqueleto). */
    data object Loading : CameraStatus

    /** A fonte é a câmera do celular e a permissão de câmera não foi concedida. */
    data object NoPermission : CameraStatus

    /** A fonte configurada não tem câmera detectada (ex.: USB desconectada). */
    data object Unavailable : CameraStatus

    /** Há uma câmera para a fonte configurada. [badge] é o formato salvo ("4K • 30 FPS"). */
    data class Ready(val source: CameraSource, val badge: String) : CameraStatus
}

enum class CameraSource { CAMERA, USB, SONY }

/** Regras puras (testadas em JVM) que transformam o que foi sondado em [CameraStatus]. */
object CameraStatusLogic {
    /** "SONY" só vale com a fonte Sony Wi-Fi ligada (flag); senão a Home trata como câmera do celular. */
    fun source(raw: String?, sonyEnabled: Boolean = VideoSources.sonyWifiEnabled()): CameraSource = when (VideoSources.normalize(raw, sonyEnabled)) {
        VideoSources.USB -> CameraSource.USB
        VideoSources.SONY -> CameraSource.SONY
        else -> CameraSource.CAMERA
    }

    /** "4K • 30 FPS": só resolução e quadros (sem codec nem bitrate). */
    fun badge(resolution: String, fps: Int): String = "$resolution • $fps FPS"

    /**
     * USB exige um dispositivo de vídeo conectado; Sony Wi-Fi não é detectável sem custo (mostra só a
     * fonte configurada); a câmera do celular exige permissão e ao menos uma câmera.
     */
    fun resolve(
        source: CameraSource,
        hasCameraPermission: Boolean,
        cameraCount: Int,
        usbVideoDevices: Int,
        badge: String,
    ): CameraStatus = when (source) {
        CameraSource.USB -> if (usbVideoDevices > 0) CameraStatus.Ready(source, badge) else CameraStatus.Unavailable

        CameraSource.SONY -> CameraStatus.Ready(source, badge)

        CameraSource.CAMERA -> when {
            !hasCameraPermission -> CameraStatus.NoPermission
            cameraCount > 0 -> CameraStatus.Ready(source, badge)
            else -> CameraStatus.Unavailable
        }
    }
}
