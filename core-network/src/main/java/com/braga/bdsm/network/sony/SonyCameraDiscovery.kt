package com.braga.bdsm.network.sony

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

class SonyCameraDiscovery {

    companion object {
        private const val TAG = "SonyCameraDiscovery"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SONY_SEARCH_TARGET = "urn:schemas-sony-com:service:ScalarWebAPI:1"
        private const val DEFAULT_FALLBACK_ENDPOINT = "http://192.168.122.1:8080/sony/camera"
    }

    suspend fun discoverCamera(timeoutMs: Int = 3000): SonyCameraDevice? = withContext(Dispatchers.IO) {
        val ssdpMessage = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: $SONY_SEARCH_TARGET\r\n\r\n"

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.soTimeout = timeoutMs

            val sendData = ssdpMessage.toByteArray(Charsets.UTF_8)
            val sendPacket = DatagramPacket(
                sendData,
                sendData.size,
                InetAddress.getByName(SSDP_ADDRESS),
                SSDP_PORT
            )
            socket.send(sendPacket)

            val receiveBuffer = ByteArray(2048)
            val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)

            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    socket.receive(receivePacket)
                    val response = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)
                    val location = extractLocation(response)
                    if (location != null) {
                        val device = parseDeviceDescription(location)
                        if (device != null) {
                            return@withContext device
                        }
                    }
                } catch (e: Exception) {
                    // Socket timeout or receive error
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP Discovery error: ${e.message}")
        } finally {
            socket?.close()
        }

        // Fallback para IP padrão do Wi-Fi Direct da Sony
        Log.d(TAG, "Testing default fallback endpoint: $DEFAULT_FALLBACK_ENDPOINT")
        if (pingEndpoint(DEFAULT_FALLBACK_ENDPOINT)) {
            return@withContext SonyCameraDevice(
                friendlyName = "Sony Camera (Direct)",
                modelName = "Sony Remote Device",
                ddUrl = "",
                endpointUrl = DEFAULT_FALLBACK_ENDPOINT
            )
        }

        null
    }

    private fun extractLocation(ssdpResponse: String): String? {
        val lines = ssdpResponse.split("\r\n", "\n")
        for (line in lines) {
            val lower = line.lowercase()
            if (lower.startsWith("location:")) {
                return line.substring(line.indexOf(':') + 1).trim()
            }
        }
        return null
    }

    private fun parseDeviceDescription(locationUrl: String): SonyCameraDevice? {
        return try {
            val url = URL(locationUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            connection.requestMethod = "GET"

            if (connection.responseCode == 200) {
                val xmlData = connection.inputStream.bufferedReader().use { it.readText() }
                val factory = DocumentBuilderFactory.newInstance()
                val builder = factory.newDocumentBuilder()
                val doc = builder.parse(ByteArrayInputStream(xmlData.toByteArray()))

                val friendlyName = doc.getElementsByTagName("friendlyName").item(0)?.textContent ?: "Sony Camera"
                val modelName = doc.getElementsByTagName("modelName").item(0)?.textContent ?: ""

                // Localizar X_ScalarWebAPI_ServiceType com X_ScalarWebAPI_ServiceType == "camera"
                var cameraEndpoint = ""
                val serviceTypeList = doc.getElementsByTagName("av:X_ScalarWebAPI_ServiceType")
                val actionUrlList = doc.getElementsByTagName("av:X_ScalarWebAPI_ActionList_URL")

                for (i in 0 until serviceTypeList.length) {
                    if (serviceTypeList.item(i)?.textContent?.contains("camera") == true) {
                        val baseUrl = actionUrlList.item(i)?.textContent ?: ""
                        cameraEndpoint = if (baseUrl.endsWith("/")) "${baseUrl}camera" else "$baseUrl/camera"
                        break
                    }
                }

                if (cameraEndpoint.isEmpty()) {
                    val rootHost = "${url.protocol}://${url.host}:${url.port}"
                    cameraEndpoint = "$rootHost/sony/camera"
                }

                SonyCameraDevice(
                    friendlyName = friendlyName,
                    modelName = modelName,
                    ddUrl = locationUrl,
                    endpointUrl = cameraEndpoint
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse device description: ${e.message}")
            null
        }
    }

    private fun pingEndpoint(endpoint: String): Boolean {
        return try {
            val url = URL(endpoint)
            val conn = url.openConnection() as HttpURLConnection
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
        }
    }
}
