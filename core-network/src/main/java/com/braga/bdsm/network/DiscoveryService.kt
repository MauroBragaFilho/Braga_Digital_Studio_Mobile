package com.braga.bdsm.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscoveryService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serviceName: String? = null

    fun registerService(port: Int) {
        if (registrationListener != null) {
            return
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "BDSM Link"
            serviceType = "_bdsm._tcp"
            this.port = port
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                serviceName = NsdServiceInfo.serviceName
                Log.i("DiscoveryService", "mDNS Service registered: $serviceName on port $port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("DiscoveryService", "mDNS Service registration failed: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.i("DiscoveryService", "mDNS Service unregistered: ${arg0.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("DiscoveryService", "mDNS Service unregistration failed: $errorCode")
            }
        }

        try {
            nsdManager.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener
            )
        } catch (e: Exception) {
            Log.e("DiscoveryService", "Error registering mDNS service", e)
        }
    }

    fun unregisterService() {
        registrationListener?.let {
            try {
                nsdManager.unregisterService(it)
            } catch (e: Exception) {
                Log.e("DiscoveryService", "Error unregistering mDNS service", e)
            }
            registrationListener = null
        }
    }
}
