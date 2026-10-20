package com.bragastudio.mobile.corecapture.device

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.Surface
import com.bragastudio.mobile.corecapture.domain.CaptureState
import com.bragastudio.mobile.corecapture.domain.Hd60sSessionState
import com.bragastudio.mobile.corecapture.domain.Hd60sSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captura da Elgato HD60 S (não-UVC): abre o dispositivo pelo [UsbManager], entrega o descritor de
 * arquivo à sessão nativa (libbdsm-hd60s) e acompanha o estado dela para a interface.
 *
 * A imagem não passa por aqui: a sessão nativa converte cada quadro e o posta direto na [Surface].
 */
internal class Hd60sCapture(
    private val context: Context,
    private val onStatus: (UsbSourceStatus, CaptureState) -> Unit,
) {
    private val native = Hd60sNative()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var connection: UsbDeviceConnection? = null
    private var handle: Long = 0L
    private var pollJob: Job? = null

    @Volatile
    var lastSnapshot: Hd60sSnapshot? = null
        private set

    val isOpen: Boolean get() = handle != 0L

    /** Abre a placa e inicia a captura. false (com o status já publicado) se não foi possível. */
    fun open(device: UsbDevice, surface: Surface?): Boolean {
        close()
        if (!Hd60sNative.isAvailable) {
            Log.e(TAG, "libbdsm-hd60s indisponível nesta ABI")
            onStatus(UsbSourceStatus.ERROR, CaptureState.ERROR)
            return false
        }
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(device)) {
            onStatus(UsbSourceStatus.PERMISSION_DENIED, CaptureState.ERROR)
            return false
        }
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            Log.e(TAG, "UsbManager.openDevice devolveu null")
            onStatus(UsbSourceStatus.ERROR, CaptureState.ERROR)
            return false
        }
        logDescriptors(device)

        connection = conn
        handle = native.nativeCreate(conn.fileDescriptor, device.productId)
        if (handle == 0L || !native.nativeStart(handle, surface)) {
            close()
            onStatus(UsbSourceStatus.ERROR, CaptureState.ERROR)
            return false
        }
        startPolling()
        return true
    }

    fun setSurface(surface: Surface?) {
        if (handle != 0L) native.nativeSetSurface(handle, surface)
    }

    fun close() {
        pollJob?.cancel()
        pollJob = null
        val h = handle
        handle = 0L
        if (h != 0L) {
            native.nativeStop(h)
            native.nativeDestroy(h)
        }
        connection?.close()
        connection = null
        lastSnapshot = null
    }

    /** Libera tudo, inclusive o escopo de acompanhamento (a instância não pode mais ser usada). */
    fun release() {
        close()
        scope.cancel()
    }

    private fun startPolling() {
        pollJob = scope.launch {
            var last: Pair<UsbSourceStatus, CaptureState>? = null
            var tick = 0
            while (isActive) {
                val h = handle
                val snap = if (h != 0L) Hd60sSnapshot.fromNative(native.nativeSnapshot(h)) else null
                if (snap != null) {
                    lastSnapshot = snap
                    if (++tick % STATS_EVERY_TICKS == 0 && snap.state == Hd60sSessionState.STREAMING) {
                        Log.i(
                            TAG,
                            "bytes=${snap.bytes} quadros(parser)=${snap.framesParsed} renderizados=${snap.framesRendered} " +
                                "descartados=${snap.framesDropped} parciais=${snap.framesPartial} resyncs=${snap.resyncs} " +
                                "errosUsb=${snap.usbErrors} (último ${snap.lastUsbError})",
                        )
                    }
                    val mapped = Hd60sStatusMapper.map(snap)
                    if (mapped != last) {
                        last = mapped
                        Log.i(
                            TAG,
                            "estado=${snap.state} falha=${snap.fault} ${snap.width}x${snap.height} " +
                                "quadros=${snap.framesRendered} usb=${snap.usbSpeed}",
                        )
                        onStatus(mapped.first, mapped.second)
                    }
                }
                delay(POLL_MS)
            }
        }
    }

    private fun logDescriptors(device: UsbDevice) {
        Log.i(TAG, "dispositivo ${device.productName} ${"%04x:%04x".format(device.vendorId, device.productId)}")
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            val endpoints = (0 until intf.endpointCount).joinToString { e ->
                val ep = intf.getEndpoint(e)
                "0x%02x/%s/%d".format(ep.address, endpointType(ep.type), ep.maxPacketSize)
            }
            Log.i(TAG, "  interface ${intf.id} alt ${intf.alternateSetting}: $endpoints")
        }
    }

    private fun endpointType(type: Int): String = when (type) {
        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "iso"
        UsbConstants.USB_ENDPOINT_XFER_INT -> "int"
        else -> "ctrl"
    }

    private companion object {
        const val TAG = "Hd60sCapture"
        const val POLL_MS = 500L
        const val STATS_EVERY_TICKS = 4
    }
}
