package com.bragastudio.mobile.core.domain

/**
 * Lado da barra de navegação lateral (rail) em paisagem/telas largas. [END] (padrão) fica do lado da
 * mão que segura o aparelho na maioria dos usuários; [START] é para canhotos. "Start/End" respeitam
 * RTL. Persistida por [name]; leitura tolerante (ver [fromName]).
 */
enum class LandscapeNavSide {
    END,
    START,
    ;

    companion object {
        val Default = END

        /** Valor desconhecido, nulo ou corrompido volta ao padrão (direita). */
        fun fromName(name: String?): LandscapeNavSide = entries.firstOrNull { it.name == name } ?: Default
    }
}
