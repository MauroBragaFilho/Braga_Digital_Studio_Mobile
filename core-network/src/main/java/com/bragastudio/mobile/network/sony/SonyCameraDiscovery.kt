package com.bragastudio.mobile.network.sony

import android.util.Log
import android.util.Xml
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser

class SonyCameraDiscovery {

    companion object {
        private const val TAG = "SonyCameraDiscovery"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SONY_SEARCH_TARGET = "urn:schemas-sony-com:service:ScalarWebAPI:1"
        private const val DEFAULT_FALLBACK_ENDPOINT = "http://192.168.122.1:8080/sony/camera"
        private const val MAX_DESCRIPTION_BYTES = 64 * 1024
    }

    suspend fun discoverCamera(timeoutMs: Int = 3000): SonyCameraDevice? = withContext(Dispatchers.IO) {
        // Vincula as conexões da Sony ao Wi-Fi da câmera (e não aos dados móveis).
        SonyNetwork.acquire()

        val ssdpMessage = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $SONY_SEARCH_TARGET\r\n\r\n"

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            SonyNetwork.bind(socket)

            val sendData = ssdpMessage.toByteArray(Charsets.UTF_8)
            socket.send(DatagramPacket(sendData, sendData.size, InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT))

            val deadline = System.currentTimeMillis() + timeoutMs
            val receiveBuffer = ByteArray(2048)
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                socket.soTimeout = remaining.toInt()
                // Pacote novo a cada volta: `length` encolhe após cada receive().
                val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    break
                }
                val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                val location = extractLocation(response) ?: continue
                // Respostas de outros hosts/redes públicas são ignoradas e a busca continua.
                val url = SonyProtocolRules.validateLocation(location, packet.address)
                if (url == null) {
                    Log.w(TAG, "SSDP: LOCATION descartado (não confere com ${packet.address})")
                    continue
                }
                parseDeviceDescription(url)?.let { return@withContext it }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP Discovery error: ${e.message}")
        } finally {
            socket?.close()
        }

        // Fallback para o IP padrão do Wi-Fi Direct da Sony
        Log.d(TAG, "Testing default fallback endpoint")
        if (pingEndpoint(DEFAULT_FALLBACK_ENDPOINT)) {
            return@withContext SonyCameraDevice(
                friendlyName = "Sony Camera (Direct)",
                modelName = "Sony Remote Device",
                ddUrl = "",
                endpointUrl = DEFAULT_FALLBACK_ENDPOINT,
            )
        }

        null
    }

    private fun extractLocation(ssdpResponse: String): String? {
        for (line in ssdpResponse.split("\r\n", "\n")) {
            if (line.lowercase().startsWith("location:")) {
                return line.substring(line.indexOf(':') + 1).trim()
            }
        }
        return null
    }

    private fun parseDeviceDescription(url: URL): SonyCameraDevice? {
        var connection: HttpURLConnection? = null
        return try {
            connection = SonyNetwork.openConnection(url) as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false

            if (connection.responseCode != 200) return null
            val xmlData = connection.inputStream.use { SonyProtocolRules.readLimited(it, MAX_DESCRIPTION_BYTES) }
            val info = parseDescriptionXml(xmlData)

            var cameraEndpoint = ""
            if (info.cameraActionUrl.isNotBlank()) {
                val action = try {
                    URL(info.cameraActionUrl)
                } catch (e: Exception) {
                    null
                }
                // A URL de ação precisa apontar para o mesmo host que respondeu.
                if (action != null && SonyProtocolRules.sameHost(action, url)) {
                    val base = info.cameraActionUrl
                    cameraEndpoint = if (base.endsWith("/")) "${base}camera" else "$base/camera"
                }
            }
            if (cameraEndpoint.isEmpty()) {
                cameraEndpoint = "${url.protocol}://${url.host}:${SonyProtocolRules.effectivePort(url)}/sony/camera"
            }

            SonyCameraDevice(
                friendlyName = info.friendlyName.ifBlank { "Sony Camera" },
                modelName = info.modelName,
                ddUrl = url.toString(),
                endpointUrl = cameraEndpoint,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse device description: ${e.message}")
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (e: Exception) { }
        }
    }

    private class DescriptionInfo(
        var friendlyName: String = "",
        var modelName: String = "",
        var cameraActionUrl: String = "",
    )

    /**
     * Lê o Device Description com XmlPullParser (o parser do Android não resolve DTD
     * nem entidades externas, ao contrário de DocumentBuilderFactory) e com namespaces:
     * o prefixo (`av:`) pode variar entre modelos, então comparamos o nome local.
     */
    private fun parseDescriptionXml(data: ByteArray): DescriptionInfo {
        val info = DescriptionInfo()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(ByteArrayInputStream(data), "UTF-8")

        var currentType = ""
        var currentUrl = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "friendlyName" -> if (info.friendlyName.isEmpty()) info.friendlyName = readText(parser)
                    "modelName" -> if (info.modelName.isEmpty()) info.modelName = readText(parser)
                    "X_ScalarWebAPI_ServiceType" -> currentType = readText(parser)
                    "X_ScalarWebAPI_ActionList_URL" -> currentUrl = readText(parser)
                }

                XmlPullParser.END_TAG -> if (parser.name == "X_ScalarWebAPI_Service") {
                    if (info.cameraActionUrl.isEmpty() && currentType.equals("camera", ignoreCase = true)) {
                        info.cameraActionUrl = currentUrl
                    }
                    currentType = ""
                    currentUrl = ""
                }
            }
            event = parser.next()
        }
        return info
    }

    /** Texto do elemento corrente (posiciona o parser no END_TAG correspondente). */
    private fun readText(parser: XmlPullParser): String {
        var result = ""
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.text.orEmpty().trim().take(512)
            parser.nextTag()
        }
        return result
    }

    private fun pingEndpoint(endpoint: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = SonyNetwork.openConnection(URL(endpoint)) as HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val dummyBody = "{\"method\":\"getAvailableApiList\",\"params\":[],\"id\":1,\"version\":\"1.0\"}"
            conn.outputStream.use { it.write(dummyBody.toByteArray()) }
            conn.responseCode == 200
        } catch (e: Exception) {
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) { }
        }
    }
}
