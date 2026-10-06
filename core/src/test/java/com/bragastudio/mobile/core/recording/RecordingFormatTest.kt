package com.bragastudio.mobile.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingFormatTest {

    @Test
    fun duracao_semHoras_usaMmSs() {
        assertEquals("00:00", RecordingFormat.formatDuration(0))
        assertEquals("00:59", RecordingFormat.formatDuration(59_999))
        assertEquals("01:05", RecordingFormat.formatDuration(65_000))
        assertEquals("59:59", RecordingFormat.formatDuration(3_599_000))
    }

    @Test
    fun duracao_comHoras_naoZeraOsMinutos() {
        assertEquals("1:00:00", RecordingFormat.formatDuration(3_600_000))
        assertEquals("2:03:04", RecordingFormat.formatDuration((2 * 3600 + 3 * 60 + 4) * 1000L))
        assertEquals("00:00", RecordingFormat.formatDuration(-5))
    }

    @Test
    fun resolucao_aPartirDasDimensoesReais() {
        assertEquals("4K", RecordingFormat.resolutionFromSize(3840, 2160))
        assertEquals("1440p", RecordingFormat.resolutionFromSize(2560, 1440))
        assertEquals("1080p", RecordingFormat.resolutionFromSize(1920, 1080))
        assertEquals("1080p", RecordingFormat.resolutionFromSize(1080, 1920)) // retrato
        assertEquals("720p", RecordingFormat.resolutionFromSize(1280, 720))
        assertEquals("480p", RecordingFormat.resolutionFromSize(640, 480))
        assertEquals(RecordingFormat.UNKNOWN, RecordingFormat.resolutionFromSize(0, 0))
    }

    @Test
    fun resolucao_rotuloTextual_eLxA() {
        assertEquals("1080p", RecordingFormat.resolutionLabel("1080p"))
        assertEquals("4K", RecordingFormat.resolutionLabel("4K"))
        // "split("x") em 1080p" não pode quebrar nem cair em padrão errado
        assertEquals("1080p", RecordingFormat.resolutionLabel("1920x1080"))
        assertEquals("4K", RecordingFormat.resolutionLabel("3840X2160"))
        assertEquals(RecordingFormat.UNKNOWN, RecordingFormat.resolutionLabel(""))
        assertEquals(RecordingFormat.UNKNOWN, RecordingFormat.resolutionLabel(null))
    }

    @Test
    fun codec_normalizaGrafias() {
        assertEquals("H.265", RecordingFormat.codecLabel("H.265"))
        assertEquals("H.265", RecordingFormat.codecLabel("video/hevc"))
        assertEquals("H.265", RecordingFormat.codecLabel("HEVC"))
        assertEquals("H.264", RecordingFormat.codecLabel("video/avc"))
        assertEquals("H.264", RecordingFormat.codecLabel("h264"))
        assertEquals("AV1", RecordingFormat.codecLabel("video/av01"))
        assertEquals(RecordingFormat.UNKNOWN, RecordingFormat.codecLabel(null))
        assertEquals("AAC", RecordingFormat.audioCodecFromMime("audio/mp4a-latm"))
    }

    @Test
    fun bitrate_aceitaMbpsEBps() {
        assertEquals(50, RecordingFormat.bitrateToMbps(50))
        assertEquals(50, RecordingFormat.bitrateToMbps(50_000_000))
        assertEquals("12 Mbps", RecordingFormat.bitrateLabel(12_000_000))
        assertEquals(RecordingFormat.UNKNOWN, RecordingFormat.bitrateLabel(0))
        assertEquals(8, RecordingFormat.averageMbps(sizeBytes = 60_000_000, durationMs = 60_000))
        assertEquals(0, RecordingFormat.averageMbps(0, 1000))
    }

    @Test
    fun fps_porContagemDeQuadros() {
        assertEquals(30, RecordingFormat.fpsFromFrameCount(1800, 60_000))
        assertEquals(60, RecordingFormat.fpsFromFrameCount(3600, 60_000))
        assertEquals(0, RecordingFormat.fpsFromFrameCount(0, 60_000))
        assertEquals("30fps", RecordingFormat.fpsLabel(30))
        assertEquals(RecordingFormat.UNKNOWN, RecordingFormat.fpsLabel(0))
    }

    @Test
    fun extensoesDeVideo() {
        assertTrue(RecordingFormat.isVideoFile("a.MP4"))
        assertTrue(RecordingFormat.isVideoFile("a.mov"))
        assertTrue(RecordingFormat.isVideoFile("a.b.mkv"))
        assertFalse(RecordingFormat.isVideoFile("a.jpg"))
        assertFalse(RecordingFormat.isVideoFile("mp4"))
    }
}
