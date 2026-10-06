package com.bragastudio.mobile.core.domain

import android.content.Context
import android.provider.Settings

/**
 * Nomes da fonte NDI. Na rede o NDI mostra `MÁQUINA (nome do sender)`. A "máquina" vem do
 * sistema (no Android seria sempre "localhost"), então o BDSM a fixa em [MACHINE_NAME] pelo
 * arquivo `ndi-config.v1.json` (chave `ndi.machinename`, pasta em `NDI_CONFIG_DIR`). O resultado
 * visto no OBS/vMix é `BDSM (Nome do Aparelho)` ou `BDSM (nome definido pelo usuário)`.
 */
object NdiNaming {
    const val MACHINE_NAME = "BDSM"
    private const val MAX_LENGTH = 64
    private val LEGACY_PREFIX = Regex("""^\s*BDSM\s*[-–—:]\s*""", RegexOption.IGNORE_CASE)

    /** Nome do aparelho definido pelo usuário no Android; senão o modelo. */
    fun deviceName(context: Context): String {
        val configured = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (_: Exception) {
            null
        }
        return fallbackName(configured)
    }

    /** Nome padrão sem acesso a Context (modelo do aparelho). */
    fun fallbackName(configured: String? = null): String = clean(configured).ifEmpty { clean(android.os.Build.MODEL) }.ifEmpty { "Android" }

    /**
     * Nome do sender (a parte entre parênteses no NDI). Remove o prefixo antigo "BDSM - "
     * (que, junto da máquina "BDSM", ficaria repetido) e cai no [deviceName] se ficar vazio.
     */
    fun sourceName(raw: String?, deviceName: String): String {
        val withoutPrefix = clean(raw).replace(LEGACY_PREFIX, "").trim()
        return withoutPrefix.ifEmpty { clean(deviceName).ifEmpty { "Android" } }.take(MAX_LENGTH)
    }

    /** Conteúdo do `ndi-config.v1.json` que fixa o nome da máquina. */
    fun configJson(machineName: String = MACHINE_NAME): String {
        val escaped = buildString {
            for (c in machineName) {
                if (c == '\\' || c == '"') append('\\')
                append(c)
            }
        }
        return "{\"ndi\":{\"machinename\":\"$escaped\"}}"
    }

    private fun clean(s: String?): String = s.orEmpty().filter { !it.isISOControl() }.trim()
}
