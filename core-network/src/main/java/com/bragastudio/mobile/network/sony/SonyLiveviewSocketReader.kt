package com.bragastudio.mobile.network.sony

import android.util.Log
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class SonyLiveviewSocketReader {

    companion object {
        private const val TAG = "SonyLiveviewReader"
        private const val COMMON_HEADER_SIZE = 8
        private const val PAYLOAD_HEADER_SIZE = 128
        private const val SYNC_SIZE = COMMON_HEADER_SIZE + 4 // 0xFF... + start code do payload
        private const val MAX_JPEG_BYTES = 2 * 1024 * 1024
        private const val MAX_RESYNC_SCAN = 1024 * 1024
        private const val MAX_HTTP_HEADER = 8 * 1024
        private const val CONNECT_TIMEOUT_MS = 3000
        private val PAYLOAD_START_CODE = byteArrayOf(0x24.toByte(), 0x35.toByte(), 0x68.toByte(), 0x79.toByte())

        /** [w] (12 bytes) começa um quadro válido: 0xFF + 7 bytes de common header + start code 24 35 68 79. */
        internal fun isFrameStart(w: ByteArray): Boolean = w[0] == 0xFF.toByte() &&
            w[COMMON_HEADER_SIZE] == PAYLOAD_START_CODE[0] &&
            w[COMMON_HEADER_SIZE + 1] == PAYLOAD_START_CODE[1] &&
            w[COMMON_HEADER_SIZE + 2] == PAYLOAD_START_CODE[2] &&
            w[COMMON_HEADER_SIZE + 3] == PAYLOAD_START_CODE[3]

        /**
         * Ressincroniza o fluxo depois de bytes inválidos: desliza uma janela de 12 bytes
         * até achar 0xFF seguido, 8 bytes depois, do start code do payload. Ao retornar,
         * [window] contém o início do quadro (common header + 4 bytes do payload header).
         * Lança [IOException] se não achar em [MAX_RESYNC_SCAN] bytes ou no fim do fluxo.
         */
        internal fun resync(input: InputStream, window: ByteArray) {
            var scanned = 0
            while (!isFrameStart(window)) {
                if (scanned++ > MAX_RESYNC_SCAN) throw IOException("Liveview sem sincronismo")
                System.arraycopy(window, 1, window, 0, SYNC_SIZE - 1)
                val b = input.read()
                if (b < 0) throw SocketException("Fim do fluxo durante a ressincronização")
                window[SYNC_SIZE - 1] = b.toByte()
            }
        }

        internal fun readFully(inStream: InputStream, buffer: ByteArray, offset: Int, length: Int) {
            var bytesRead = 0
            while (bytesRead < length) {
                val count = inStream.read(buffer, offset + bytesRead, length - bytesRead)
                if (count == -1) {
                    throw SocketException("Connection closed prematurely while reading frame data")
                }
                bytesRead += count
            }
        }
    }

    @Volatile private var isRunning = false

    @Volatile private var socket: Socket? = null

    interface FrameCallback {
        fun onFrameReceived(jpegBytes: ByteArray, sequenceNumber: Int, timestampUs: Long)
        fun onError(error: Throwable)
    }

    suspend fun startStreaming(liveviewUrl: String, callback: FrameCallback) = withContext(Dispatchers.IO) {
        isRunning = true
        try {
            val url = URL(liveviewUrl)
            val host = url.host
            val port = SonyProtocolRules.effectivePort(url)
            if (!url.protocol.equals("http", ignoreCase = true)) throw IOException("Liveview deve ser http")

            // Resolve e exige rede local: a URL vem da câmera, mas não confiamos cegamente.
            val address = InetAddress.getByName(host)
            if (!SonyProtocolRules.isPrivateAddress(address)) throw IOException("Liveview fora da rede local: $host")

            Log.d(TAG, "Connecting to liveview socket: $host:$port")
            // Socket() + bind à rede da câmera + connect com timeout (o construtor Socket(host, port) não tem).
            val s = SonyNetwork.newSocket()
            socket = s
            s.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            s.tcpNoDelay = true
            s.receiveBufferSize = 256 * 1024
            s.soTimeout = 5000

            val path = if (url.file.isNullOrEmpty()) "/liveview/liveviewstream" else url.file
            val request = "GET $path HTTP/1.1\r\n" +
                "Host: $host:$port\r\n" +
                "Connection: keep-alive\r\n" +
                "Accept: */*\r\n\r\n"

            val out = s.getOutputStream()
            out.write(request.toByteArray(Charsets.US_ASCII))
            out.flush()

            val rawIn = BufferedInputStream(s.getInputStream(), 128 * 1024)
            skipHttpHeaders(rawIn)

            val window = ByteArray(SYNC_SIZE)
            val payloadRest = ByteArray(PAYLOAD_HEADER_SIZE - 4)

            while (isRunning && coroutineContext.isActive) {
                // 1. Common header (8) + start code do payload (4).
                readFully(rawIn, window, 0, SYNC_SIZE)
                if (!isFrameStart(window)) {
                    Log.w(TAG, "Quadro fora de sincronia; ressincronizando")
                    resync(rawIn, window)
                }
                val seqNum = ((window[2].toInt() and 0xFF) shl 8) or (window[3].toInt() and 0xFF)
                val timestamp = ((window[4].toLong() and 0xFF) shl 24) or
                    ((window[5].toLong() and 0xFF) shl 16) or
                    ((window[6].toLong() and 0xFF) shl 8) or
                    (window[7].toLong() and 0xFF)

                // 2. Restante do payload header (124 bytes).
                readFully(rawIn, payloadRest, 0, payloadRest.size)
                // JPEG size: bytes 4..6 do payload header = payloadRest[0..2]; padding: byte 7 = payloadRest[3].
                val jpegSize = ((payloadRest[0].toInt() and 0xFF) shl 16) or
                    ((payloadRest[1].toInt() and 0xFF) shl 8) or
                    (payloadRest[2].toInt() and 0xFF)
                val paddingSize = payloadRest[3].toInt() and 0xFF

                if (jpegSize <= 0 || jpegSize > MAX_JPEG_BYTES) {
                    // O próximo ciclo falha em isFrameStart e ressincroniza.
                    Log.w(TAG, "Invalid JPEG size: $jpegSize")
                    continue
                }

                // 3. JPEG: buffer NOVO a cada quadro (é entregue ao consumidor por referência).
                val jpegData = ByteArray(jpegSize)
                readFully(rawIn, jpegData, 0, jpegSize)

                // 4. Padding
                if (paddingSize > 0) {
                    readFully(rawIn, ByteArray(paddingSize), 0, paddingSize)
                }

                callback.onFrameReceived(jpegData, seqNum, timestamp)
            }
        } catch (e: Exception) {
            if (isRunning) {
                Log.e(TAG, "Streaming socket exception: ${e.message}")
                callback.onError(e)
            }
        } finally {
            close()
        }
    }

    /** Lê o cabeçalho HTTP de resposta até a linha em branco (limitado). */
    private fun skipHttpHeaders(inStream: InputStream) {
        var consecutiveEol = 0
        var total = 0
        while (true) {
            val b = inStream.read()
            if (b == -1) break
            if (++total > MAX_HTTP_HEADER) throw IOException("Cabeçalho HTTP do liveview grande demais")
            if (b == '\r'.code) {
                continue // CR não interrompe a contagem de "\n\n"/"\r\n\r\n"
            } else if (b == '\n'.code) {
                if (++consecutiveEol >= 2) break
            } else {
                consecutiveEol = 0
            }
        }
    }

    fun close() {
        isRunning = false
        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore close errors
        }
        socket = null
    }
}
