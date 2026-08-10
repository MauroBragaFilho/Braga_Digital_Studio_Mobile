package com.bragastudio.mobile

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

import com.braga.bdsm.network.LinkServer
import com.braga.bdsm.network.MetadataCollector
import javax.inject.Inject

@HiltAndroidApp
class BsmApplication : Application() {

    @Inject
    lateinit var linkServer: LinkServer

    @Inject
    lateinit var metadataCollector: MetadataCollector

    override fun onCreate() {
        super.onCreate()
        
        // Iniciar servidor local BDSM Link e coletor de dados
        linkServer.start()
        metadataCollector.startCollecting()
    }
}
