package com.bragastudio.mobile.network

import kotlinx.serialization.json.Json

/**
 * Json compartilhado por todo o Link. `encodeDefaults = true` é essencial: sem ele o
 * kotlinx.serialization omite campos iguais ao valor padrão (ex.: fps = 0, bateria = 100)
 * e o dashboard/plugin OBS mostrava valores errados.
 */
object LinkJson {
    val instance: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}
