package com.bragastudio.mobile.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class VideoSourcesTest {

    @Test
    fun sonyWifiIsDisabledInThisBuild() {
        // O interruptor único: religar a fonte Sony Wi-Fi exige trocar CaptureFeatureFlags.SONY_WIFI_ENABLED.
        assertFalse(CaptureFeatureFlags.SONY_WIFI_ENABLED)
        assertFalse(VideoSources.sonyWifiEnabled())
    }

    @Test
    fun offeredSourcesHideSonyWhenDisabled() {
        assertEquals(listOf("Camera", "USB"), VideoSources.available(sonyEnabled = false))
        assertEquals(listOf("Camera", "USB", "SONY"), VideoSources.available(sonyEnabled = true))
        // Padrão do build atual (flag desligada).
        assertEquals(listOf("Camera", "USB"), VideoSources.available())
    }

    @Test
    fun persistedSonyFallsBackToPhoneCameraWhenDisabled() {
        assertEquals("Camera", VideoSources.normalize("SONY", sonyEnabled = false))
        assertEquals("Camera", VideoSources.normalize(" sony ", sonyEnabled = false))
        assertEquals("SONY", VideoSources.normalize("SONY", sonyEnabled = true))
        assertEquals("Camera", VideoSources.normalize("SONY")) // padrão do build: desligada
    }

    @Test
    fun otherSourcesAndGarbageAreUnchangedOrDefault() {
        assertEquals("USB", VideoSources.normalize("USB", sonyEnabled = false))
        assertEquals("USB", VideoSources.normalize("usb", sonyEnabled = false))
        assertEquals("Camera", VideoSources.normalize("Camera", sonyEnabled = false))
        assertEquals("Camera", VideoSources.normalize(null, sonyEnabled = false))
        assertEquals("Camera", VideoSources.normalize("lixo", sonyEnabled = true))
    }

    @Test
    fun quickCycleSkipsSonyWhenDisabled() {
        assertEquals("USB", VideoSources.next("Camera", sonyEnabled = false))
        assertEquals("Camera", VideoSources.next("USB", sonyEnabled = false))
        // Configuração antiga "SONY" (flag desligada) é tratada como Camera e segue o ciclo.
        assertEquals("USB", VideoSources.next("SONY", sonyEnabled = false))
        assertEquals("SONY", VideoSources.next("USB", sonyEnabled = true))
        assertEquals("Camera", VideoSources.next("SONY", sonyEnabled = true))
    }

    @Test
    fun persistedSonySettingsFallBackWithoutLosingOtherSettings() {
        val saved = VideoSettings(
            resolution = "4K",
            fps = 60,
            bitrateMbps = 100,
            codec = "H.265",
            videoSource = "SONY",
            recordingDirectoryUri = "content://tree/x",
            saveToGallery = true,
            stabilizationEnabled = true,
            hdrEnabled = true,
        )
        val effective = saved.withEffectiveSource(sonyEnabled = false)
        assertEquals("Camera", effective.videoSource)
        assertEquals(saved.copy(videoSource = "Camera"), effective)
    }

    @Test
    fun settingsAreReturnedAsIsWhenSourceIsValid() {
        val usb = VideoSettings(videoSource = "USB", fps = 60)
        assertSame(usb, usb.withEffectiveSource(sonyEnabled = false))
        val sony = VideoSettings(videoSource = "SONY")
        assertSame(sony, sony.withEffectiveSource(sonyEnabled = true))
    }
}
