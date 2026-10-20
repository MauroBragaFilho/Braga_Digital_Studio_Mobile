package com.bragastudio.mobile.corecapture.device

import android.view.Surface

/** Ponte JNI (libbdsm-hd60s.so) para a captura da Elgato HD60 S, que não é UVC. */
internal class Hd60sNative {

    external fun nativeCreate(usbFd: Int, productId: Int): Long

    external fun nativeStart(handle: Long, surface: Surface?): Boolean

    external fun nativeSetSurface(handle: Long, surface: Surface?)

    external fun nativeStop(handle: Long)

    external fun nativeDestroy(handle: Long)

    /** Ver Hd60sSnapshot.fromNative para a ordem dos campos; null se o handle é inválido. */
    external fun nativeSnapshot(handle: Long): LongArray?

    companion object {
        val isAvailable: Boolean = try {
            System.loadLibrary("bdsm-hd60s")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }
}
