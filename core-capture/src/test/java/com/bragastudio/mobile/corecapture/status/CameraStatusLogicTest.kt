package com.bragastudio.mobile.corecapture.status

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraStatusLogicTest {
    private val badge = "4K • 30 FPS"

    @Test
    fun badgeHasNoCodecOrBitrate() {
        assertEquals("4K • 30 FPS", CameraStatusLogic.badge("4K", 30))
    }

    @Test
    fun sourceIsTolerant() {
        assertEquals(CameraSource.USB, CameraStatusLogic.source(" usb "))
        assertEquals(CameraSource.SONY, CameraStatusLogic.source("SONY"))
        assertEquals(CameraSource.CAMERA, CameraStatusLogic.source(null))
        assertEquals(CameraSource.CAMERA, CameraStatusLogic.source("outra"))
    }

    @Test
    fun phoneCameraNeedsPermissionAndACamera() {
        assertEquals(CameraStatus.NoPermission, CameraStatusLogic.resolve(CameraSource.CAMERA, false, 3, 0, badge))
        assertEquals(CameraStatus.Unavailable, CameraStatusLogic.resolve(CameraSource.CAMERA, true, 0, 0, badge))
        assertEquals(CameraStatus.Ready(CameraSource.CAMERA, badge), CameraStatusLogic.resolve(CameraSource.CAMERA, true, 2, 0, badge))
    }

    @Test
    fun usbNeedsAConnectedVideoDevice() {
        assertEquals(CameraStatus.Unavailable, CameraStatusLogic.resolve(CameraSource.USB, true, 3, 0, badge))
        assertEquals(CameraStatus.Ready(CameraSource.USB, badge), CameraStatusLogic.resolve(CameraSource.USB, false, 0, 1, badge))
    }

    @Test
    fun sonyShowsOnlyTheConfiguredSource() {
        assertEquals(CameraStatus.Ready(CameraSource.SONY, badge), CameraStatusLogic.resolve(CameraSource.SONY, false, 0, 0, badge))
    }
}
