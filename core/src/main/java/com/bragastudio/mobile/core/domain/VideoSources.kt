package com.bragastudio.mobile.core.domain

/**
 * Interruptor ÚNICO dos recursos opcionais de captura. Para religar a fonte "Sony Camera (Wi-Fi)"
 * basta trocar [SONY_WIFI_ENABLED] para `true` (e restaurar no `AndroidManifest.xml` as permissões e o
 * cleartext listados em `.docs/ARQUITETURA.md`, "Sony Wi-Fi desativada por flag").
 *
 * Com `false` (padrão atual): a opção some dos seletores, nenhuma descoberta SSDP/HTTP/socket da Sony
 * é iniciada e uma configuração persistida "SONY" cai para a câmera do celular (ver [VideoSources]).
 * A câmera Sony entra no app como fonte de vídeo pela placa HDMI-USB (fonte "USB"/UVC).
 */
object CaptureFeatureFlags {
    const val SONY_WIFI_ENABLED = false
}

/** Valores persistidos de [VideoSettings.videoSource] e regras puras (testadas em JVM) de disponibilidade. */
object VideoSources {
    const val CAMERA = "Camera"
    const val USB = "USB"
    const val SONY = "SONY"

    /** A fonte Sony Wi-Fi está disponível? (um único ponto: o interruptor [CaptureFeatureFlags]). */
    fun sonyWifiEnabled(): Boolean = CaptureFeatureFlags.SONY_WIFI_ENABLED

    /** Fontes que o usuário pode escolher, na ordem de exibição. */
    fun available(sonyEnabled: Boolean = sonyWifiEnabled()): List<String> = if (sonyEnabled) listOf(CAMERA, USB, SONY) else listOf(CAMERA, USB)

    /**
     * Valor efetivo de uma fonte persistida: "SONY" sem o recurso vira [CAMERA] (câmera do celular).
     * Só normaliza na leitura; o valor salvo não é apagado (religar o recurso restaura a escolha).
     */
    fun normalize(persisted: String?, sonyEnabled: Boolean = sonyWifiEnabled()): String {
        val value = persisted?.trim().orEmpty()
        return when {
            value.equals(SONY, ignoreCase = true) -> if (sonyEnabled) SONY else CAMERA
            value.equals(USB, ignoreCase = true) -> USB
            else -> CAMERA
        }
    }

    /** Próxima fonte do ciclo rápido do Monitor, só entre as disponíveis. */
    fun next(current: String?, sonyEnabled: Boolean = sonyWifiEnabled()): String {
        val list = available(sonyEnabled)
        val index = list.indexOf(normalize(current, sonyEnabled))
        return list[(index + 1) % list.size]
    }
}

/**
 * [VideoSettings] com a fonte efetiva ([VideoSources.normalize]); as demais configurações ficam
 * intactas. É o que o repositório entrega a Monitor, Home, Ajustes e MediaGraph.
 */
fun VideoSettings.withEffectiveSource(sonyEnabled: Boolean = VideoSources.sonyWifiEnabled()): VideoSettings {
    val effective = VideoSources.normalize(videoSource, sonyEnabled)
    return if (effective == videoSource) this else copy(videoSource = effective)
}
