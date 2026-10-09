package com.bragastudio.mobile.featurehome

import com.bragastudio.mobile.core.domain.VideoSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeStatusTest {
    @Test
    fun activeRecording_ignoresStaleRows() {
        val now = 100_000_000L
        assertEquals(null, HomeStatus.activeRecordingStart(emptyList(), now))
        assertEquals(null, HomeStatus.activeRecordingStart(listOf(now - HomeStatus.STALE_RECORDING_MS - 1), now))
        assertEquals(now - 5000, HomeStatus.activeRecordingStart(listOf(now - 5000, now - 9000_000), now))
    }

    @Test
    fun formatElapsed() {
        assertEquals("00:12", HomeStatus.formatElapsed(12_000))
        assertEquals("1:02:03", HomeStatus.formatElapsed(3_723_000))
        assertEquals("00:00", HomeStatus.formatElapsed(-5))
    }

    @Test
    fun dayPeriod_boundaries() {
        assertEquals(DayPeriod.NIGHT, HomeStatus.dayPeriod(0))
        assertEquals(DayPeriod.NIGHT, HomeStatus.dayPeriod(4))
        assertEquals(DayPeriod.MORNING, HomeStatus.dayPeriod(5))
        assertEquals(DayPeriod.MORNING, HomeStatus.dayPeriod(11))
        assertEquals(DayPeriod.AFTERNOON, HomeStatus.dayPeriod(12))
        assertEquals(DayPeriod.AFTERNOON, HomeStatus.dayPeriod(17))
        assertEquals(DayPeriod.NIGHT, HomeStatus.dayPeriod(18))
        assertEquals(DayPeriod.NIGHT, HomeStatus.dayPeriod(23))
    }

    @Test
    fun plainFormat_hidesCodec() {
        assertEquals("4K · 30 fps", HomeStatus.plainFormat(VideoSettings(resolution = "4K", fps = 30, codec = "H.265")))
    }

    @Test
    fun greeting_withAndWithoutName() {
        assertEquals("Bom dia, Maria", HomeStatus.greeting("Bom dia", "Maria"))
        assertEquals("Boa tarde, Maria", HomeStatus.greeting("Boa tarde", "  Maria "))
        assertEquals("Boa noite", HomeStatus.greeting("Boa noite", null))
        assertEquals("Boa noite", HomeStatus.greeting("Boa noite", "  "))
    }
}
