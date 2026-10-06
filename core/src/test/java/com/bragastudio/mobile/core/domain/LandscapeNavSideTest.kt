package com.bragastudio.mobile.core.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LandscapeNavSideTest {
    @Test
    fun defaultIsEnd() {
        assertEquals(LandscapeNavSide.END, LandscapeNavSide.Default)
        assertEquals(LandscapeNavSide.END, LandscapeNavSide.fromName(null))
    }

    @Test
    fun roundTripsEveryValue() {
        LandscapeNavSide.entries.forEach { assertEquals(it, LandscapeNavSide.fromName(it.name)) }
    }

    @Test
    fun invalidFallsBackToDefault() {
        assertEquals(LandscapeNavSide.END, LandscapeNavSide.fromName(""))
        assertEquals(LandscapeNavSide.END, LandscapeNavSide.fromName("end"))
        assertEquals(LandscapeNavSide.END, LandscapeNavSide.fromName("LEFT"))
    }
}
