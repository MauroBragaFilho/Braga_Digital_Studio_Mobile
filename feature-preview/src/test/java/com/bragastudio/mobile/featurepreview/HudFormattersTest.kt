package com.bragastudio.mobile.featurepreview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudFormattersTest {

    // ---------------- formatTimecode ----------------

    @Test
    fun timecode_zero() {
        assertEquals("00:00:00:00", formatTimecode(0L, 30))
    }

    @Test
    fun timecode_hoursMinutesSecondsFrames() {
        // 1h 02m 03s + 500 ms a 30 fps = 15 quadros
        val ms = ((1 * 3600) + (2 * 60) + 3) * 1000L + 500L
        assertEquals("01:02:03:15", formatTimecode(ms, 30))
    }

    @Test
    fun timecode_framesNeverReachFps() {
        assertEquals("00:00:00:29", formatTimecode(999L, 30))
        assertEquals("00:00:00:59", formatTimecode(999L, 60))
    }

    @Test
    fun timecode_zeroOrNegativeFpsGivesZeroFrames() {
        assertEquals("00:00:01:00", formatTimecode(1500L, 0))
        assertEquals("00:00:01:00", formatTimecode(1500L, -5))
    }

    @Test
    fun timecode_negativeTimeIsClampedToZero() {
        assertEquals("00:00:00:00", formatTimecode(-1234L, 30))
    }

    // ---------------- formatRecordingTimeRemaining ----------------

    @Test
    fun remaining_invalidBitrate() {
        assertEquals("--:--", formatRecordingTimeRemaining(10f, 0))
        assertEquals("--:--", formatRecordingTimeRemaining(10f, -1))
    }

    @Test
    fun remaining_noStorage() {
        assertEquals("00:00", formatRecordingTimeRemaining(0f, 50))
    }

    @Test
    fun remaining_minutesOnly() {
        // 10 GB a 50 Mb/s (+10%): 81920 Mb / 55 = 1489 s = 24 min
        assertEquals("24m", formatRecordingTimeRemaining(10f, 50))
    }

    @Test
    fun remaining_hoursAndMinutes() {
        // 100 GB a 50 Mb/s (+10%): 819200 / 55 = 14894 s = 4 h 08 min
        assertEquals("4h08m", formatRecordingTimeRemaining(100f, 50))
    }

    // ---------------- dB / VU (M32) ----------------

    private val eps = 1e-4f

    @Test
    fun dbToFraction_endpoints() {
        assertEquals(0f, dbToFraction(-60f), eps)
        assertEquals(1f, dbToFraction(0f), eps)
        assertEquals(0f, dbToFraction(Float.NEGATIVE_INFINITY), eps)
        assertEquals(0f, dbToFraction(-200f), eps)
        assertEquals(1f, dbToFraction(6f), eps) // acima de 0 dBFS satura
        assertEquals(0f, dbToFraction(Float.NaN), eps)
    }

    @Test
    fun dbToFraction_scaleMarks() {
        assertEquals(0.8f, dbToFraction(-12f), eps)
        assertEquals(0.95f, dbToFraction(-3f), eps)
        assertEquals(0.8f, VU_GREEN_END_FRACTION, eps)
        assertEquals(0.95f, VU_YELLOW_END_FRACTION, eps)
    }

    @Test
    fun oldConstantsWereMiscalibrated() {
        // As constantes antigas (0.68 / 0.90 / 0.98) valiam -19.2 / -6 / -1.2 dBFS.
        assertEquals(-19.2f, fractionToDb(0.68f), 0.01f)
        assertEquals(-6f, fractionToDb(0.90f), 0.01f)
        assertEquals(-1.2f, fractionToDb(0.98f), 0.01f)
    }

    @Test
    fun fractionToDb_isInverseOfDbToFraction() {
        for (db in listOf(-60f, -42f, -12f, -3f, 0f)) {
            assertEquals(db, fractionToDb(dbToFraction(db)), 0.001f)
        }
    }

    @Test
    fun linearToFraction_fullScaleSine() {
        // Senoide de amplitude 0.95 tem pico em ~-0.45 dBFS -> perto do topo da escala
        assertTrue(linearToFraction(0.95f) > 0.99f)
        assertEquals(1f, linearToFraction(1f), eps)
        assertEquals(0f, linearToFraction(0f), eps)
        assertEquals(0.95f, linearToFraction(0.70794576f), 0.001f) // -3 dBFS
    }

    @Test
    fun normalizeAudioLevel_clampsAndHandlesNaN() {
        assertEquals(0f, normalizeAudioLevel(-0.5f), 0f)
        assertEquals(1f, normalizeAudioLevel(1.5f), 0f)
        assertEquals(0f, normalizeAudioLevel(Float.NaN), 0f)
        assertEquals(0.4f, normalizeAudioLevel(0.4f), 0f)
    }

    @Test
    fun segmentIndexForFraction_mapsToLadder() {
        assertEquals(-1, segmentIndexForFraction(0f, 20))
        assertEquals(0, segmentIndexForFraction(0.04f, 20))
        assertEquals(15, segmentIndexForFraction(0.8f, 20)) // -12 dBFS = 16o segmento (verde)
        assertEquals(19, segmentIndexForFraction(1f, 20))
    }

    // ---------------- dials (M31) ----------------

    @Test
    fun dialIndex_endsOfArc() {
        // O arco começa em 150 graus e varre 240 graus (até 30 graus)
        assertEquals(0, dialIndexForAngle(150.0, 8))
        assertEquals(7, dialIndexForAngle(30.0, 8))
    }

    @Test
    fun dialIndex_middleOfArc() {
        // meio do arco = 150 + 120 = 270 graus
        assertEquals(4, dialIndexForAngle(270.0, 8)) // 0.5 * 7 = 3.5 -> arredonda para 4
    }

    @Test
    fun dialIndex_deadZoneGoesToNearestEnd() {
        // zona morta: 30..150 graus. Perto do fim (35 graus) -> última opção; perto do início (145) -> primeira
        assertEquals(7, dialIndexForAngle(40.0, 8))
        assertEquals(0, dialIndexForAngle(140.0, 8))
    }

    @Test
    fun dialIndex_degenerateCounts() {
        assertEquals(0, dialIndexForAngle(200.0, 0))
        assertEquals(0, dialIndexForAngle(200.0, 1))
    }

    @Test
    fun sliderIndex_mapsAndClamps() {
        assertEquals(0, sliderIndexForFraction(-1f, 9))
        assertEquals(8, sliderIndexForFraction(2f, 9))
        assertEquals(4, sliderIndexForFraction(0.5f, 9))
        assertEquals(0, sliderIndexForFraction(0.7f, 1))
    }

    @Test
    fun sliderIndex_sameIndexForNearbyPositions() {
        // Base do M31: posições próximas devem cair no MESMO índice (onSelect não repete)
        assertEquals(sliderIndexForFraction(0.50f, 9), sliderIndexForFraction(0.52f, 9))
    }
}
