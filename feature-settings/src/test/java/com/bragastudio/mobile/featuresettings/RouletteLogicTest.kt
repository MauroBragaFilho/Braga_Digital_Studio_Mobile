package com.bragastudio.mobile.featuresettings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouletteLogicTest {
    private val slices = 19
    private val sweep = 360f / slices

    @Test
    fun rotationZero_pointsAtSliceUnderTop() {
        // Ponteiro no topo (270°): com rotação 0 a fatia sob ele é a que contém o ângulo 270°.
        assertEquals((270f / sweep).toInt() % slices, rouletteSliceIndex(0f, slices))
    }

    @Test
    fun fullTurnsDoNotChangeTheSlice() {
        val base = rouletteSliceIndex(123.4f, slices)
        assertEquals(base, rouletteSliceIndex(123.4f + 360f * 10, slices))
        assertEquals(base, rouletteSliceIndex(123.4f - 360f * 3, slices))
    }

    @Test
    fun indexAlwaysWithinBounds() {
        var a = -720f
        while (a <= 3600f) {
            val i = rouletteSliceIndex(a, slices)
            assertTrue("indice $i fora para $a", i in 0 until slices)
            a += 7.3f
        }
    }

    @Test
    fun rotatingByOneSliceMovesIndexByOne() {
        val i0 = rouletteSliceIndex(10f, slices)
        val i1 = rouletteSliceIndex(10f + sweep, slices)
        // girar a roleta no sentido horário faz o ponteiro apontar para a fatia anterior
        assertEquals((i0 - 1 + slices) % slices, i1)
    }
}
