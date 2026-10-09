package com.bragastudio.mobile.corecapture

import com.bragastudio.mobile.corecapture.domain.withBriefProbe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BriefProbeTest {

    @Test
    fun fecha_depois_de_ler() = runBlocking {
        var closed = 0
        val result = withBriefProbe("t", open = { "res" }, close = { closed++ }) { it.length }
        assertEquals(3, result)
        assertEquals(1, closed)
    }

    @Test
    fun fecha_em_excecao() = runBlocking {
        var closed = 0
        val result = withBriefProbe<String, Int>("t", open = { "res" }, close = { closed++ }) { error("falha") }
        assertNull(result)
        assertEquals(1, closed)
    }

    @Test
    fun fecha_em_timeout() = runBlocking {
        var closed = 0
        val result = withBriefProbe<String, Int>("t", timeoutMs = 50, open = { "res" }, close = { closed++ }) {
            delay(5_000)
            1
        }
        assertNull(result)
        assertEquals(1, closed)
    }

    @Test
    fun recurso_indisponivel_nao_chama_close() = runBlocking {
        var closed = 0
        val result = withBriefProbe<String, Int>("t", open = { null }, close = { closed++ }) { 1 }
        assertNull(result)
        assertEquals(0, closed)
    }
}
