package com.bragastudio.mobile.core.domain

/** Preferência de aparência do app. Persistida por [name]; a leitura é tolerante (ver [fromName]). */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    /** Resolve o modo para "usar escuro?" dado o estado atual do sistema. */
    fun resolveDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        val Default = SYSTEM

        /** Valor desconhecido, nulo ou corrompido volta ao padrão (Sistema). */
        fun fromName(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: Default
    }
}
