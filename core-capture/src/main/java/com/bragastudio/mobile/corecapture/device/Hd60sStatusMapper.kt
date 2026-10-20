package com.bragastudio.mobile.corecapture.device

import com.bragastudio.mobile.corecapture.domain.CaptureState
import com.bragastudio.mobile.corecapture.domain.Hd60sFault
import com.bragastudio.mobile.corecapture.domain.Hd60sSessionState
import com.bragastudio.mobile.corecapture.domain.Hd60sSnapshot

/** Traduz a sessão nativa da HD60 S para o estado público da fonte USB. Lógica pura (testada em JVM). */
internal object Hd60sStatusMapper {

    fun map(snapshot: Hd60sSnapshot): Pair<UsbSourceStatus, CaptureState> = when (snapshot.state) {
        Hd60sSessionState.IDLE -> UsbSourceStatus.NO_DEVICE to CaptureState.IDLE

        Hd60sSessionState.INITIALIZING -> UsbSourceStatus.NO_SIGNAL to CaptureState.INITIALIZING

        // A placa abriu, mas não há imagem HDMI chegando (fonte desligada ou sem saída): IDLE faz o
        // Monitor mostrar o aviso de espera, como na fonte sem dispositivo.
        Hd60sSessionState.NO_SIGNAL -> UsbSourceStatus.NO_SIGNAL to CaptureState.IDLE

        Hd60sSessionState.STREAMING -> UsbSourceStatus.STREAMING to CaptureState.READY

        Hd60sSessionState.ERROR -> when (snapshot.fault) {
            Hd60sFault.FRONT_END_MISSING -> UsbSourceStatus.FRONT_END_MISSING to CaptureState.ERROR
            Hd60sFault.DEVICE_LOST -> UsbSourceStatus.DISCONNECTED to CaptureState.IDLE
            else -> UsbSourceStatus.ERROR to CaptureState.ERROR
        }
    }
}
