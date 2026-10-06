package com.bragastudio.mobile.featuresettings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garante que o mapeamento enum <-> string persistida NÃO altera os valores já
 * gravados no DataStore (M48) e que a leitura é tolerante a lixo.
 */
class SettingsOptionsTest {

    @Test
    fun resolution_persistedValuesAreUnchanged() {
        assertEquals("1080p", Resolution.P1080.persisted)
        assertEquals("1440p", Resolution.P1440.persisted)
        assertEquals("4K", Resolution.UHD4K.persisted)
    }

    @Test
    fun resolution_roundTrip() {
        Resolution.values().forEach { assertEquals(it, Resolution.fromPersisted(it.persisted)) }
    }

    @Test
    fun resolution_unknownOrNullFallsBackTo1080p() {
        assertEquals(Resolution.P1080, Resolution.fromPersisted("8K"))
        assertEquals(Resolution.P1080, Resolution.fromPersisted(null))
        assertEquals(Resolution.P1080, Resolution.fromPersisted(""))
        assertNull(Resolution.fromPersistedOrNull("8K"))
    }

    @Test
    fun resolution_parseIsCaseAndWhitespaceTolerant() {
        assertEquals(Resolution.UHD4K, Resolution.fromPersisted(" 4k "))
    }

    @Test
    fun codec_persistedValuesAndFallback() {
        assertEquals("H.264", Codec.H264.persisted)
        assertEquals("H.265", Codec.H265.persisted)
        assertEquals(Codec.H265, Codec.fromPersisted("H.265"))
        assertEquals(Codec.H264, Codec.fromPersisted("VP9"))
        assertEquals(Codec.H264, Codec.fromPersisted(null))
    }

    @Test
    fun fps_valuesAndLookup() {
        assertEquals(listOf(24, 30, 60), Fps.values().map { it.value })
        assertEquals(Fps.F60, Fps.fromValue(60))
        assertEquals(Fps.F30, Fps.fromValue(25)) // desconhecido -> padrão
        assertNull(Fps.fromValueOrNull(25))
        assertNull(Fps.fromValueOrNull(null))
    }

    @Test
    fun videoSource_persistedValuesAreUnchanged() {
        assertEquals("Camera", VideoSourceOption.CAMERA.persisted)
        assertEquals("USB", VideoSourceOption.USB.persisted)
        assertEquals("SONY", VideoSourceOption.SONY.persisted)
        assertEquals(VideoSourceOption.CAMERA, VideoSourceOption.fromPersisted("lixo"))
    }

    @Test
    fun peaking_persistedValuesAreUnchanged() {
        assertEquals(listOf("Red", "Green", "Blue", "White"), PeakingColor.values().map { it.persisted })
        assertEquals(listOf("Low", "Medium", "High"), PeakingSensitivity.values().map { it.persisted })
        assertEquals(PeakingColor.RED, PeakingColor.fromPersisted("Magenta"))
        assertEquals(PeakingSensitivity.MEDIUM, PeakingSensitivity.fromPersisted(null))
    }

    @Test
    fun streamPreset_persistedValuesAreUnchanged() {
        assertEquals(listOf("HD", "FHD", "QHD", "UHD"), StreamPreset.values().map { it.persisted })
        assertEquals(StreamPreset.FHD, StreamPreset.fromPersisted("???"))
    }

    // ---- Nome do stream (M46) ----

    @Test
    fun streamName_sanitizeTrimsAndLimits() {
        assertEquals("Cam A", SettingsRules.sanitizeStreamName("  Cam A \n"))
        assertEquals("", SettingsRules.sanitizeStreamName("   "))
        assertEquals(SettingsRules.MAX_STREAM_NAME_LENGTH, SettingsRules.sanitizeStreamName("x".repeat(200)).length)
    }

    @Test
    fun streamName_blankMeansDefault() {
        assertEquals("BDSM - X", SettingsRules.effectiveStreamName("", "BDSM - X"))
        assertEquals("BDSM - X", SettingsRules.effectiveStreamName("   ", "BDSM - X"))
        assertEquals("Cam A", SettingsRules.effectiveStreamName("Cam A", "BDSM - X"))
    }

    @Test
    fun streamName_changeDetection() {
        val def = "BDSM - X"
        assertFalse(SettingsRules.streamNameChanged("Cam A ", "Cam A", def))
        assertTrue(SettingsRules.streamNameChanged("Cam B", "Cam A", def))
        // Limpar o campo quando o gravado já é o padrão não é mudança.
        assertFalse(SettingsRules.streamNameChanged("", def, def))
        // Limpar o campo quando há nome customizado volta ao padrão: é mudança.
        assertTrue(SettingsRules.streamNameChanged("", "Cam A", def))
    }

    // ---- Validações de host (BSP) ----

    @Test
    fun host_validIpv4AndNames() {
        assertTrue(SettingsRules.isValidHost("192.168.1.10"))
        assertTrue(SettingsRules.isValidHost(" 10.0.0.1 "))
        assertTrue(SettingsRules.isValidHost("meu-pc.local"))
        assertTrue(SettingsRules.isValidHost("estudio"))
    }

    @Test
    fun host_invalid() {
        assertFalse(SettingsRules.isValidHost(""))
        assertFalse(SettingsRules.isValidHost("999.1.1.1"))
        assertFalse(SettingsRules.isValidHost("1.2.3"))
        assertFalse(SettingsRules.isValidHost("1.2.3.4.5"))
        assertFalse(SettingsRules.isValidHost("host name"))
        assertFalse(SettingsRules.isValidHost("-host"))
        assertFalse(SettingsRules.isValidHost("http://10.0.0.1"))
    }

    @Test
    fun coerce_limits() {
        assertEquals(1, SettingsRules.coerceBitrate(0))
        assertEquals(400, SettingsRules.coerceBitrate(10_000))
        assertEquals(0, SettingsRules.coerceZebra(-5))
        assertEquals(100, SettingsRules.coerceZebra(150))
    }

    // ---- Valores sem dado nunca viram números inventados (M47) ----

    @Test
    fun metrics_noDataShowsDashes() {
        assertEquals("--", formatBitrateMbps(null))
        assertEquals("--", formatBitrateMbps(0))
        assertEquals("12 Mbps", formatBitrateMbps(12))
        assertEquals("--", formatLatencyMs(0))
        assertEquals("33 ms", formatLatencyMs(33))
    }
}
