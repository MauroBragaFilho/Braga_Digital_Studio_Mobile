package com.braga.bdsm.network.sony

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.Socket
import java.net.SocketException
import java.net.URL
import kotlin.coroutines.coroutineContext

class SonyLiveviewSocketReader {

    companion object {
        private const val TAG = "SonyLiveviewReader"
        private const val COMMON_HEADER_SIZE = 8
        private const val PAYLOAD_HEADER_SIZE = 128
        private val PAYLOAD_START_CODE = byteArrayOf(0x24.toByte(), 0x35.toByte(), 0x68.toByte(), 0x79.toByte())
    }

    private var isRunning = false
    private var socket: Socket? = null

    interface FrameCallback {
        fun onFrameReceived(jpegBytes: ByteArray, sequenceNumber: Int, timestampUs: Long)
        fun onError(error: Throwable)
    }

    suspend fun startStreaming(liveviewUrl: String, callback: FrameCallback) = withContext(Dispatchers.IO) {
        isRunning = true
        try {
            val url = URL(liveviewUrl)
            val host = url.host
            val port = if (url.port != -1) url.port else 8080

            Log.d(TAG, "Connecting to liveview socket: $host:$port (path=${url.path})")
            val s = Socket(host, port)
            s.tcpNoDelay = true
            s.sendBufferSize = 64 * 1024
            s.receiveBufferSize = 256 * 1024
            s.soTimeout = 5000
            socket = s

            // HTTP GET Request para a URL do Liveview
            val path = if (url.path.isNullOrEmpty()) "/liveview/liveviewstream" else url.path
            val request = "GET $path HTTP/1.1\r\n" +
                    "Host: $host:$port\r\n" +
                    "Connection: keep-alive\r\n" +
                    "Accept: */*\r\n\r\n"

            val out = s.getOutputStream()
            out.write(request.toByteArray(Charsets.US_ASCII))
            out.flush()

            val rawIn = BufferedInputStream(s.getInputStream(), 128 * 1024)

            // Ler cabeçalho HTTP de resposta até \r\n\r\n
            skipHttpHeaders(rawIn)

            val commonHeader = ByteArray(COMMON_HEADER_SIZE)
            val payloadHeader = ByteArray(PAYLOAD_HEADER_SIZE)

            while (isRunning && coroutineContext.isActive) {
                // 1. Common Header (8 bytes)
                readFully(rawIn, commonHeader, 0, COMMON_HEADER_SIZE)

                if (commonHeader[0] != 0xFF.toByte()) {
                    Log.w(TAG, "Invalid start byte in common header: ${commonHeader[0]}")
                    continue
                }
                val seqNum = ((commonHeader[2].toInt() and 0xFF) shl 8) or (commonHeader[3].toInt() and 0xFF)
                val timestamp = ((commonHeader[4].toLong() and 0xFF) shl 24) or
                        ((commonHeader[5].toLong() and 0xFF) shl 16) or
                        ((commonHeader[6].toLong() and 0xFF) shl 8) or
                        (commonHeader[7].toLong() and 0xFF)

                // 2. Payload Header (128 bytes)
                readFully(rawIn, payloadHeader, 0, PAYLOAD_HEADER_SIZE)

                if (payloadHeader[0] != PAYLOAD_START_CODE[0] ||
                    payloadHeader[1] != PAYLOAD_START_CODE[1] ||
                    payloadHeader[2] != PAYLOAD_START_CODE[2] ||
                    payloadHeader[3] != PAYLOAD_START_CODE[3]
                ) {
                    Log.w(TAG, "Invalid payload header start code")
                    continue
                }

                // JPEG data size (3 bytes: offset 4, 5, 6)
                val jpegSize = ((payloadHeader[4].toInt() and 0xFF) shl 16) or
                        ((payloadHeader[5].toInt() and 0xFF) shl 8) or
                        (payloadHeader[6].toInt() and 0xFF)

                // Padding size (offset 7)
                val paddingSize = payloadHeader[7].toInt() and 0xFF

                if (jpegSize <= 0 || jpegSize > 2 * 1024 * 1024) {
                    Log.w(TAG, "Invalid JPEG size: $jpegSize")
                    continue
                }

                // 3. JPEG Data (jpegSize bytes)
                val jpegData = ByteArray(jpegSize)
                readFully(rawIn, jpegData, 0, jpegSize)

                // 4. Padding (paddingSize bytes)
                if (paddingSize > 0) {
                    val padding = ByteArray(paddingSize)
                    readFully(rawIn, padding, 0, paddingSize)
                }

                // Notificar frame mais recente (Anti-lag)
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

    private fun skipHttpHeaders(inStream: InputStream) {
        var consecutiveEol = 0
        while (true) {
            val b = inStream.read()
            if (b == -1) break
            if (b == '\r'.code || b == '\n'.code) {
                consecutiveEol++
                if (consecutiveEol >= 4) break
            } else {
                consecutiveEol = 0
            }
        }
    }

    private fun readFully(inStream: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var bytesRead = 0
        while (bytesRead < length) {
            val count = inStream.read(buffer, offset + bytesRead, length - bytesRead)
            if (count == -1) {
                throw SocketException("Connection closed prematurely while reading frame data")
            }
            bytesRead += count
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
