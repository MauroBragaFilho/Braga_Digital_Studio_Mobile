package com.bragastudio.mobile

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

import com.braga.bdsm.network.MetadataCollector
import com.braga.bdsm.network.service.LinkServerService
import javax.inject.Inject

@HiltAndroidApp
class BsmApplication : Application() {

    @Inject
    lateinit var metadataCollector: MetadataCollector

    override fun onCreate() {
        super.onCreate()

        // O LinkServer (Ktor + mDNS) agora roda dentro de um Foreground Service
        // (LinkServerService), para que o Android não mate o processo quando o
        // app for minimizado ou a tela apagar. Chamar linkServer.start() aqui
        // diretamente NÃO é suficiente: sem um Foreground Service com notificação,
        // o SO trata o app como "em background" e mata o processo em pouco tempo.
        LinkServerService.start(this)

        metadataCollector.startCollecting()
    }
}
