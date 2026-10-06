package com.bragastudio.mobile.featuresettings

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * IP local real (IPv4) do aparelho (M47). Devolve null quando não há rede —
 * a UI mostra "Sem rede"; nunca um IP inventado.
 */
object LocalIp {

    /** Primeiro IPv4 utilizável (não loopback, não link-local) da lista. */
    fun pickIpv4(addresses: List<InetAddress>): String? = addresses
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
        ?.hostAddress

    private fun fromLinkProperties(lp: LinkProperties?): String? = lp?.linkAddresses?.map { it.address }?.let { pickIpv4(it) }

    /**
     * Varredura das interfaces — cobre o caso de ponto de acesso (hotspot) e
     * Ethernet USB, em que não há "rede ativa" Wi-Fi. Só aceita endereços de
     * rede privada (LAN) para não exibir o IP da operadora (CGNAT) como se
     * fosse alcançável pelo OBS.
     */
    fun scanInterfaces(): String? = try {
        Collections.list(NetworkInterface.getNetworkInterfaces() ?: throw IllegalStateException("sem interfaces"))
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    /** Leitura pontual (usada pelo botão Atualizar). */
    // ACCESS_NETWORK_STATE está declarada no manifest do :app.
    @android.annotation.SuppressLint("MissingPermission")
    fun current(context: Context): String? = try {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active: Network? = cm.activeNetwork
        fromLinkProperties(active?.let { cm.getLinkProperties(it) }) ?: scanInterfaces()
    } catch (_: Exception) {
        scanInterfaces()
    }

    /**
     * Fluxo que reage às mudanças da rede padrão via
     * registerDefaultNetworkCallback (Wi-Fi ↔ dados ↔ Ethernet) e emite o IP
     * atual (ou null). O callback é desregistrado ao cancelar a coleta.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun observe(context: Context): Flow<String?> = callbackFlow {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(fromLinkProperties(cm.getLinkProperties(network)) ?: scanInterfaces())
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                trySend(fromLinkProperties(linkProperties) ?: scanInterfaces())
            }

            override fun onLost(network: Network) {
                // Sem rede padrão: ainda pode haver hotspot/Ethernet local.
                trySend(scanInterfaces())
            }
        }

        // Valor inicial imediato (o callback pode demorar a disparar).
        trySend(current(context))
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            // Sem permissão/serviço: mantém só o valor pontual acima.
        }
        awaitClose {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
    }.distinctUntilChanged()
}
