package com.bragastudio.mobile.core.domain

/**
 * Nome de exibição da pessoa na saudação da Home. O app não tem conta: o padrão é o nome do aparelho e a
 * pessoa pode trocá-lo em Ajustes > Aplicativo. Fica só no aparelho (DataStore); nunca é enviado para fora.
 * Texto vazio guardado = "usar o nome do aparelho".
 */
object DisplayName {
    /** Limite de caracteres do nome digitado. */
    const val MAX_LENGTH = 24

    private val GENERIC = setOf("android", "unknown", "localhost", "device", "phone")
    private val WHITESPACE = Regex("""\s+""")

    /** Remove quebras de linha e controles, junta espaços repetidos, apara e limita a [MAX_LENGTH]. */
    fun sanitize(raw: String?): String = raw.orEmpty()
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
        .replace(WHITESPACE, " ")
        .trim()
        .take(MAX_LENGTH)
        .trim()

    /** Nome genérico demais para cumprimentar ("Android", vazio...): a saudação sai sem nome. */
    fun isGeneric(name: String?): Boolean = sanitize(name).let { it.isEmpty() || it.lowercase() in GENERIC }

    /**
     * Nome a exibir: o escolhido pela pessoa ([custom]); se vazio, o do aparelho ([deviceName]); se este for
     * genérico, `null` (mostra-se só a saudação).
     */
    fun resolve(custom: String?, deviceName: String?): String? {
        val chosen = sanitize(custom)
        if (chosen.isNotEmpty()) return chosen
        val device = sanitize(deviceName)
        return device.takeUnless { isGeneric(it) }
    }
}
