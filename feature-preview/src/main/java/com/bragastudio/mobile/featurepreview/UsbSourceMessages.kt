package com.bragastudio.mobile.featurepreview

import com.bragastudio.mobile.corecapture.device.UsbSourceStatus

/**
 * Textos do Monitor para a fonte USB/UVC (placa de captura HDMI-USB, p.ex. Sony a6000 -> HDMI -> placa).
 * Lógica pura (testada em JVM): só escolhe o texto; o layout dos overlays do Monitor não muda.
 */
internal object UsbSourceMessages {

    /** Título do overlay "aguardando" (câmera USB ainda sem imagem). */
    fun waiting(status: UsbSourceStatus): String = when (status) {
        UsbSourceStatus.REQUESTING_PERMISSION -> "Permita o acesso USB no aviso do Android"
        UsbSourceStatus.DISCONNECTED -> "Câmera USB desconectada. Reconecte o cabo"
        UsbSourceStatus.NO_SIGNAL -> "Sem sinal HDMI. Ligue a saída HDMI da câmera ou do console"
        else -> "Aguardando câmera USB..."
    }

    /** Os três textos do overlay de erro quando a fonte é USB. */
    data class Error(val title: String, val detail: String, val hint: String)

    fun error(status: UsbSourceStatus): Error = when (status) {
        UsbSourceStatus.PERMISSION_DENIED -> Error(
            title = "⚠️ Acesso USB negado",
            detail = "O Android não liberou a câmera USB para o app.",
            hint = "Toque em Tentar novamente e permita o acesso.",
        )

        UsbSourceStatus.FRONT_END_MISSING -> Error(
            title = "⚠️ Elgato HD60 S ainda sem suporte",
            detail = "A placa foi reconhecida, mas não é UVC e o app ainda não sabe inicializar o hardware dela.",
            hint = "Use uma placa UVC (webcam) por enquanto.",
        )

        else -> Error(
            title = "⚠️ Não foi possível abrir a câmera USB",
            detail = "A placa recusou os formatos de vídeo ou falhou ao abrir.",
            hint = "Confira se a placa é UVC (webcam) e se a câmera está com a saída HDMI ligada. Veja Ajustes > Câmera.",
        )
    }
}
