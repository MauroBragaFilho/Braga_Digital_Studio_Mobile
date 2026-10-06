package com.bragastudio.mobile.coremedia.ndi

import android.view.Surface

/** Ponte JNI (NdiNetwork.cpp): descoberta (NDIlib_find) e recebimento (NDIlib_recv) para "Ver na rede". */
internal object NdiNative {
    @Volatile
    var loaded: Boolean = false
        private set

    init {
        loaded = try {
            System.loadLibrary("ndi")
            System.loadLibrary("bdsm-media")
            true
        } catch (_: Throwable) {
            false
        }
    }

    external fun nativeFindStart(): Boolean
    external fun nativeFindStop()

    /** Espera mudanças e devolve [nome0, url0, nome1, url1, ...]; null quando o buscador está parado. */
    external fun nativeFindPoll(timeoutMs: Int): Array<String>?

    external fun nativeRecvStart(name: String, url: String?, lowBandwidth: Boolean): Boolean
    external fun nativeRecvStop()

    /** [status, srcW, srcH, outW, outH, fps*10]; null = receptor parado. */
    external fun nativeRecvState(): IntArray?
    external fun nativeRecvSetSurface(surface: Surface?)
}
