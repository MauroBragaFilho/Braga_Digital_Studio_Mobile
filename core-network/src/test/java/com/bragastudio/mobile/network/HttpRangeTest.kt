package com.bragastudio.mobile.network

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpRangeTest {

    private val len = 1000L

    @Test
    fun `sem cabecalho ou invalido responde completo`() {
        assertEquals(RangeResult.Full, parseRange(null, len))
        assertEquals(RangeResult.Full, parseRange("", len))
        assertEquals(RangeResult.Full, parseRange("items=0-10", len))
        assertEquals(RangeResult.Full, parseRange("bytes=abc-def", len))
        assertEquals(RangeResult.Full, parseRange("bytes=0-10,20-30", len)) // multiplos: ignora
        assertEquals(RangeResult.Full, parseRange("bytes=500-100", len)) // fim antes do inicio
        assertEquals(RangeResult.Full, parseRange("bytes=", len))
    }

    @Test
    fun `intervalo fechado aberto e sufixo`() {
        assertEquals(RangeResult.Partial(0, 99), parseRange("bytes=0-99", len))
        assertEquals(RangeResult.Partial(500, 999), parseRange("bytes=500-", len))
        assertEquals(RangeResult.Partial(900, 999), parseRange("bytes=-100", len))
        assertEquals(RangeResult.Partial(0, 999), parseRange("bytes=-5000", len)) // sufixo maior que o arquivo
        assertEquals(RangeResult.Partial(990, 999), parseRange("bytes=990-5000", len)) // fim e limitado
        assertEquals(RangeResult.Partial(0, 0), parseRange("bytes=0-0", len))
        assertEquals(RangeResult.Partial(10, 20), parseRange(" BYTES=10-20 ", len))
    }

    @Test
    fun `fora do arquivo e insatisfazivel`() {
        assertEquals(RangeResult.Unsatisfiable, parseRange("bytes=1000-", len))
        assertEquals(RangeResult.Unsatisfiable, parseRange("bytes=2000-3000", len))
        assertEquals(RangeResult.Unsatisfiable, parseRange("bytes=-0", len))
        assertEquals(RangeResult.Unsatisfiable, parseRange("bytes=0-10", 0))
    }
}
