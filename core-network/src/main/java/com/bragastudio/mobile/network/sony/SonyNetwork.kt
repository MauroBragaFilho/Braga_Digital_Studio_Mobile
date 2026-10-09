package com.bragastudio.mobile.network.sony

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.bragastudio.mobile.core.domain.VideoSources
import java.net.DatagramSocket
import java.net.Socket
import java.net.URL
import java.net.URLConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Vínculo POR SOCKET à rede Wi-Fi da câmera Sony (A4 parte 2).
 *
 * A rede `DIRECT-xxxx` da câmera não tem internet; com dados móveis ligados o Android
 * pode rotear o tráfego pela rede móvel. Em vez de `bindProcessToNetwork` (que desviaria
 * também o NDI e o LinkServer), pedimos a rede Wi-Fi com `requestNetwork` e usamos
 * `network.openConnection` / `bindSocket` só nas conexões da Sony.
 *
 * Uso: [init] uma vez no `Application.onCreate`; [SonyCameraDiscovery.discoverCamera] chama
 * [acquire] sozinho. Quem encerra a sessão com a câmera deve chamar [release].
 * Sem [init] (ou sem Wi-Fi), tudo cai no comportamento padrão do sistema.
 *
 * Com a fonte Sony Wi-Fi desativada ([VideoSources.sonyWifiEnabled] = false) nada aqui toca a rede:
 * [init] e [acquire] não fazem nada. Ao religar o recurso, o manifesto precisa declarar
 * CHANGE_NETWORK_STATE (requestNetwork); ver `.docs/ARQUITETURA.md`.
 */
object SonyNetwork {
    private const val TAG = "SonyNetwork"

    @Volatile private var appContext: Context? = null

    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null

    /** Rede Wi-Fi atualmente vinculada, ou null. */
    @Volatile var network: Network? = null
        private set

    fun init(context: Context) {
        if (!VideoSources.sonyWifiEnabled()) return
        appContext = context.applicationContext
    }

    /** Pede a rede Wi-Fi e espera até [timeoutMs]; retorna null se indisponível ou se o recurso está desligado. */
    // CHANGE_NETWORK_STATE só é declarada quando o recurso Sony Wi-Fi é religado; desligado, retorna antes.
    @SuppressLint("MissingPermission")
    suspend fun acquire(timeoutMs: Long = 2500): Network? {
        if (!VideoSources.sonyWifiEnabled()) return null
        network?.let { return it }
        val ctx = appContext ?: return null
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val ready = CompletableDeferred<Network>()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                network = n
                ready.complete(n)
            }

            override fun onLost(n: Network) {
                if (network == n) network = null
            }
        }
        // O Wi-Fi da câmera não tem internet: não exigimos NET_CAPABILITY_INTERNET.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            release()
            callback = cb
            cm.requestNetwork(request, cb)
        } catch (e: Exception) {
            Log.w(TAG, "requestNetwork falhou: ${e.javaClass.simpleName}: ${e.message}")
            callback = null
            return null
        }
        val result = withTimeoutOrNull(timeoutMs) { ready.await() }
        if (result == null) Log.w(TAG, "Rede Wi-Fi não ficou disponível em ${timeoutMs}ms; usando rota padrão")
        return result
    }

    /** Libera o pedido de rede (a câmera deixa de ser vinculada). */
    fun release() {
        val cb = callback ?: return
        callback = null
        network = null
        val cm = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        try {
            cm?.unregisterNetworkCallback(cb)
        } catch (e: Exception) {
            // Já desregistrado.
        }
    }

    /** Abre a conexão HTTP pela rede vinculada (ou pela rota padrão). */
    fun openConnection(url: URL): URLConnection = network?.openConnection(url) ?: url.openConnection()

    /** Cria um Socket (não conectado) já vinculado à rede, quando houver. */
    fun newSocket(): Socket {
        val s = Socket()
        try {
            network?.bindSocket(s)
        } catch (e: Exception) {
            Log.w(TAG, "bindSocket falhou: ${e.message}")
        }
        return s
    }

    /** Vincula um DatagramSocket (SSDP) à rede, quando houver. */
    fun bind(socket: DatagramSocket) {
        try {
            network?.bindSocket(socket)
        } catch (e: Exception) {
            Log.w(TAG, "bindSocket (UDP) falhou: ${e.message}")
        }
    }
}
