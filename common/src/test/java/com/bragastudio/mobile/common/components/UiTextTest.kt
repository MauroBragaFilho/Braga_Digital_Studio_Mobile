package com.bragastudio.mobile.common.components

import org.junit.Assert.assertEquals
import org.junit.Test

class UiTextTest {
    @Test
    fun ofKeepsTheResourceIdAndArgumentsInOrder() {
        val text = UiText.of(42, "a", 3)
        assertEquals(UiText.Res(42, listOf("a", 3)), text)
    }

    @Test
    fun plainTextIsComparableByValue() {
        assertEquals(UiText.Plain("x"), UiText.Plain("x"))
    }

    @Test
    fun nestedTextsAreAllowedAsArguments() {
        val nested = UiText.of(1, UiText.of(2))
        assertEquals(UiText.Res(1, listOf(UiText.Res(2))), nested)
    }
}
