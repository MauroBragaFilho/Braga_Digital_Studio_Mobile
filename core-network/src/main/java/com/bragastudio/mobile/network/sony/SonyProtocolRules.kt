package com.bragastudio.mobile.network.sony

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URL

/**
 * Regras puras (sem Android) de validação do protocolo Sony — testáveis em JVM.
 */
internal object SonyProtocolRules {

    /** Endereço de rede local (10/8, 172.16/12, 192.168/16 ou link-local): nunca loopback/multicast. */
    fun isPrivateAddress(addr: InetAddress): Boolean = !addr.isLoopbackAddress && !addr.isAnyLocalAddress && !addr.isMulticastAddress &&
        (addr.isSiteLocalAddress || addr.isLinkLocalAddress)

    /**
     * Valida o `LOCATION` de uma resposta SSDP: precisa ser http, apontar para o PRÓPRIO
     * endereço que respondeu ([from]) e esse endereço precisa ser de rede privada. Impede
     * que um peer da LAN faça o app buscar URLs arbitrárias (SSRF) ou direcione o app a
     * outro host.
     */
    fun validateLocation(location: String, from: InetAddress): URL? {
        if (!isPrivateAddress(from)) return null
        val url = try {
            URL(location.trim())
        } catch (e: Exception) {
            return null
        }
        if (!url.protocol.equals("http", ignoreCase = true)) return null
        if (url.host != from.hostAddress) return null
        if (url.userInfo != null) return null
        return url
    }

    /** Uma URL anunciada pela câmera só vale se apontar para o mesmo host da descrição. */
    fun sameHost(url: URL, reference: URL): Boolean = url.protocol.equals("http", ignoreCase = true) && url.host == reference.host && url.userInfo == null

    /** Porta efetiva: `url.port` é -1 quando omitida (usa a padrão do protocolo). */
    fun effectivePort(url: URL): Int = if (url.port != -1) url.port else url.defaultPort

    /** Lê no máximo [maxBytes]; mais que isso é erro (evita OOM por resposta gigante). */
    fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > maxBytes) throw IOException("Resposta maior que $maxBytes bytes")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
