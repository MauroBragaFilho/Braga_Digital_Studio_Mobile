package com.bragastudio.mobile.featurepreview

import com.bragastudio.mobile.corecapture.device.UsbSourceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbSourceMessagesTest {

    @Test
    fun waitingMessageFollowsTheUsbStatus() {
        assertEquals("Aguardando câmera USB...", UsbSourceMessages.waiting(UsbSourceStatus.NO_DEVICE))
        assertTrue(UsbSourceMessages.waiting(UsbSourceStatus.REQUESTING_PERMISSION).contains("Permita"))
        assertTrue(UsbSourceMessages.waiting(UsbSourceStatus.DISCONNECTED).contains("desconectada"))
    }

    @Test
    fun errorMessageSeparatesDeniedPermissionFromOpenFailure() {
        val denied = UsbSourceMessages.error(UsbSourceStatus.PERMISSION_DENIED)
        val failed = UsbSourceMessages.error(UsbSourceStatus.ERROR)
        assertNotEquals(denied, failed)
        assertTrue(denied.title.contains("negado"))
        assertTrue(failed.hint.contains("UVC"))
        assertEquals(failed, UsbSourceMessages.error(UsbSourceStatus.STREAMING))
    }
}
