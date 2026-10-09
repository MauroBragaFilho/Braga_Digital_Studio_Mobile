package com.bragastudio.mobile.coremedia.bsp

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

private const val TAG = "BspDiscovery"

/**
 * Anúncio mDNS/DNS-SD `_bsp._tcp` da fonte BSP (3), pelo `NsdManager` (mesmo mecanismo do anúncio
 * `_bdsm._tcp` do Link). Só existe enquanto o BSP estiver habilitado: [advertise] com um [BspAdvert]
 * registra (ou re-registra, se nome/porta/TXT mudaram, p.ex. `st=idle` -> `st=live`) e com `null`
 * retira o serviço da rede. As chamadas do NSD são assíncronas; este objeto as serializa: nunca
 * registra por cima de um registro ainda não desfeito e sempre converge para o último pedido.
 */
class BspDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val lock = Any()
    private var desired: BspAdvert? = null
    private var registered: BspAdvert? = null
    private var listener: NsdManager.RegistrationListener? = null
    private var busy = false

    /** Nome com que o NSD de fato registrou (pode ganhar sufixo " (2)" em caso de conflito). */
    @Volatile var registeredName: String? = null
        private set

    fun advertise(advert: BspAdvert?) {
        synchronized(lock) { desired = advert }
        reconcile()
    }

    private fun reconcile() {
        synchronized(lock) {
            if (busy) return
            val want = desired
            val current = registered
            when {
                current != null && current != want -> unregister()
                current == null && want != null -> register(want)
            }
        }
    }

    // sempre chamados com [lock] retido
    private fun register(advert: BspAdvert) {
        val info = NsdServiceInfo().apply {
            serviceName = advert.instanceName
            serviceType = BspTxt.SERVICE_TYPE
            port = advert.port
            for ((k, v) in advert.txt) {
                try {
                    setAttribute(k, v)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "TXT '$k' recusado pelo NSD")
                }
            }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                synchronized(lock) {
                    registered = advert
                    registeredName = serviceInfo.serviceName
                    busy = false
                }
                Log.i(TAG, "Anúncio _bsp._tcp registrado")
                reconcile()
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(lock) {
                    listener = null
                    busy = false
                }
                Log.e(TAG, "Falha ao registrar o anúncio _bsp._tcp: $errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                synchronized(lock) {
                    registered = null
                    registeredName = null
                    listener = null
                    busy = false
                }
                Log.i(TAG, "Anúncio _bsp._tcp retirado")
                reconcile()
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(lock) {
                    registered = null
                    registeredName = null
                    listener = null
                    busy = false
                }
                Log.e(TAG, "Falha ao retirar o anúncio _bsp._tcp: $errorCode")
            }
        }
        listener = l
        busy = true
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao registrar o anúncio _bsp._tcp: ${e.javaClass.simpleName}")
            listener = null
            busy = false
        }
    }

    private fun unregister() {
        val l = listener
        if (l == null) {
            registered = null
            return
        }
        busy = true
        try {
            nsd.unregisterService(l)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao retirar o anúncio _bsp._tcp: ${e.javaClass.simpleName}")
            registered = null
            listener = null
            busy = false
        }
    }
}
