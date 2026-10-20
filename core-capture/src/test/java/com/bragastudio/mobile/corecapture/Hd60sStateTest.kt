package com.bragastudio.mobile.corecapture

import com.bragastudio.mobile.corecapture.device.Hd60sStatusMapper
import com.bragastudio.mobile.corecapture.device.UsbSourceStatus
import com.bragastudio.mobile.corecapture.domain.CaptureState
import com.bragastudio.mobile.corecapture.domain.Hd60sFault
import com.bragastudio.mobile.corecapture.domain.Hd60sIds
import com.bragastudio.mobile.corecapture.domain.Hd60sSessionState
import com.bragastudio.mobile.corecapture.domain.Hd60sSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Hd60sStateTest {

    private fun snapshot(state: Hd60sSessionState, fault: Hd60sFault = Hd60sFault.NONE) = Hd60sSnapshot(
        state = state, fault = fault, width = 0, height = 0, interlaced = false, framesRendered = 0, bytes = 0,
        framesParsed = 0, framesDropped = 0, framesPartial = 0, resyncs = 0, usbErrors = 0, usbSpeed = 0,
        lastUsbError = 0,
    )

    @Test
    fun recognisesOnlyTheNonUvcHd60sIds() {
        assertTrue(Hd60sIds.matches(0x0FD9, 0x005E))
        assertTrue(Hd60sIds.matches(0x0FD9, 0x004F))
        assertTrue(Hd60sIds.matches(0x0FD9, 0x0074))
        assertTrue(Hd60sIds.matches(0x0FD9, 0x0076))
        // HD60 S+ (UVC) e outros produtos Elgato/de outras marcas ficam com a trilha UVC.
        assertFalse(Hd60sIds.matches(0x0FD9, 0x006A))
        assertFalse(Hd60sIds.matches(0x1234, 0x005E))
    }

    @Test
    fun snapshotParsesTheNativeArray() {
        val raw = longArrayOf(3, 0, 1920, 1080, 1, 600, 123456789L, 610, 2, 1, 0, 0, 5, 0)
        val snap = Hd60sSnapshot.fromNative(raw)!!
        assertEquals(Hd60sSessionState.STREAMING, snap.state)
        assertEquals(1920, snap.width)
        assertEquals(1080, snap.height)
        assertTrue(snap.interlaced)
        assertEquals(600L, snap.framesRendered)
        assertEquals(5, snap.usbSpeed)
    }

    @Test
    fun snapshotRejectsShortOrMissingArrays() {
        assertNull(Hd60sSnapshot.fromNative(null))
        assertNull(Hd60sSnapshot.fromNative(longArrayOf(1, 2, 3)))
    }

    @Test
    fun unknownNativeCodesBecomeErrors() {
        assertEquals(Hd60sSessionState.ERROR, Hd60sSessionState.fromNative(99))
        assertEquals(Hd60sFault.INIT_FAILED, Hd60sFault.fromNative(99))
    }

    @Test
    fun mapperTranslatesSessionStatesForTheMonitor() {
        assertEquals(
            UsbSourceStatus.STREAMING to CaptureState.READY,
            Hd60sStatusMapper.map(snapshot(Hd60sSessionState.STREAMING)),
        )
        // Sem sinal = IDLE: o Monitor mostra o aviso de espera.
        assertEquals(
            UsbSourceStatus.NO_SIGNAL to CaptureState.IDLE,
            Hd60sStatusMapper.map(snapshot(Hd60sSessionState.NO_SIGNAL)),
        )
        assertEquals(
            UsbSourceStatus.NO_SIGNAL to CaptureState.INITIALIZING,
            Hd60sStatusMapper.map(snapshot(Hd60sSessionState.INITIALIZING)),
        )
    }

    @Test
    fun mapperSeparatesFaults() {
        assertEquals(
            UsbSourceStatus.FRONT_END_MISSING to CaptureState.ERROR,
            Hd60sStatusMapper.map(snapshot(Hd60sSessionState.ERROR, Hd60sFault.FRONT_END_MISSING)),
        )
        assertEquals(
            UsbSourceStatus.DISCONNECTED to CaptureState.IDLE,
            Hd60sStatusMapper.map(snapshot(Hd60sSessionState.ERROR, Hd60sFault.DEVICE_LOST)),
        )
        assertEquals(
            UsbSourceStatus.ERROR to CaptureState.ERROR,
            Hd60sStatusMapper.map(snapshot(Hd60sSessionState.ERROR, Hd60sFault.TRANSPORT_FAILED)),
        )
    }
}
