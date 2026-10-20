package com.bragastudio.mobile.corecapture.domain

/** IDs USB da Elgato Game Capture HD60 S (a original, não-UVC). A HD60 S+ é UVC e não entra aqui. */
object Hd60sIds {
    const val VENDOR_ID = 0x0FD9

    /** Revisões 1-3 (receptor MStar MST3367) e revisão 4 (ITE IT6802E). */
    private val PRODUCT_IDS = setOf(0x004F, 0x005E, 0x0074, 0x0076)

    fun matches(vendorId: Int, productId: Int): Boolean = vendorId == VENDOR_ID && productId in PRODUCT_IDS
}

/** Estado da sessão nativa (espelha hd60s::SessionState). */
enum class Hd60sSessionState {
    IDLE,
    INITIALIZING,
    NO_SIGNAL,
    STREAMING,
    ERROR,
    ;

    companion object {
        fun fromNative(value: Int): Hd60sSessionState = entries.getOrElse(value) { ERROR }
    }
}

/** Motivo de uma falha da sessão nativa (espelha hd60s::Fault). */
enum class Hd60sFault {
    NONE,
    UNKNOWN_DEVICE,
    FRONT_END_MISSING,
    USB_SETUP,
    INIT_FAILED,
    TRANSPORT_FAILED,
    DEVICE_LOST,
    ;

    companion object {
        fun fromNative(value: Int): Hd60sFault = entries.getOrElse(value) { INIT_FAILED }
    }
}

/** Fotografia da sessão nativa, na ordem de `nativeSnapshot`. */
data class Hd60sSnapshot(
    val state: Hd60sSessionState,
    val fault: Hd60sFault,
    val width: Int,
    val height: Int,
    val interlaced: Boolean,
    val framesRendered: Long,
    val bytes: Long,
    val framesParsed: Long,
    val framesDropped: Long,
    val framesPartial: Long,
    val resyncs: Long,
    val usbErrors: Long,
    val usbSpeed: Int,
    val lastUsbError: Long,
) {
    companion object {
        private const val FIELDS = 14

        fun fromNative(values: LongArray?): Hd60sSnapshot? {
            if (values == null || values.size < FIELDS) return null
            return Hd60sSnapshot(
                state = Hd60sSessionState.fromNative(values[0].toInt()),
                fault = Hd60sFault.fromNative(values[1].toInt()),
                width = values[2].toInt(),
                height = values[3].toInt(),
                interlaced = values[4] != 0L,
                framesRendered = values[5],
                bytes = values[6],
                framesParsed = values[7],
                framesDropped = values[8],
                framesPartial = values[9],
                resyncs = values[10],
                usbErrors = values[11],
                usbSpeed = values[12].toInt(),
                lastUsbError = values[13],
            )
        }
    }
}
