package com.bragastudio.mobile

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import com.bragastudio.mobile.core.domain.VideoSources
import com.bragastudio.mobile.corecapture.status.CameraStatusProvider
import com.bragastudio.mobile.network.service.AppVisibility
import com.bragastudio.mobile.network.sony.SonyNetwork
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BdsmApplication : Application() {

    @Inject
    lateinit var appVisibility: AppVisibility

    @Inject
    lateinit var cameraStatus: CameraStatusProvider

    override fun onCreate() {
        super.onCreate()

        enableStrictModeInDebug()

        // O servidor do Link (Ktor + mDNS) NÃO é iniciado aqui: startForegroundService a
        // partir do Application.onCreate pode lançar ForegroundServiceStartNotAllowedException
        // (Android 12+, processo iniciado em background) e derrubar o app. Quem inicia é a
        // MainActivity, em primeiro plano, via LinkServerController.startIfEnabled().

        // Sabe quando há Activity visível (diálogo x notificação de pareamento).
        appVisibility.attach(this)

        // Permite vincular os sockets da câmera Sony à rede Wi-Fi dela (sem bindProcessToNetwork).
        // Com a fonte Sony Wi-Fi desativada (CaptureFeatureFlags) nem isso é feito: o app não toca
        // em nenhuma rede da Sony.
        if (VideoSources.sonyWifiEnabled()) SonyNetwork.init(this)

        // Sonda as câmeras (sem abrir nenhuma) e lê o formato salvo, fora da Main: a Home já abre com o
        // estado da câmera. Só agenda uma corrotina; não atrasa a splash.
        cameraStatus.start()
    }

    /**
     * StrictMode só em build debuggable, apenas registrando no log (sem penaltyDeath): ajuda a achar
     * I/O de disco e rede na thread principal e objetos/Activities vazados.
     */
    private fun enableStrictModeInDebug() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build(),
        )
    }
}
