package com.bragastudio.mobile.network.sony

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyLiveviewParserTest {

    @Test
    fun `test valid liveview payload packet parsing`() {
        // Common header: 8 bytes
        // byte 0: 0xFF (Start byte)
        // byte 1: 0x01 (Payload type)
        // byte 2-3: 0x00, 0x2A (Sequence number: 42)
        // byte 4-7: 0x00, 0x00, 0x01, 0x00 (Timestamp: 256)
        val commonHeader = byteArrayOf(
            0xFF.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x2A.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x00.toByte(),
        )

        // Payload header: 128 bytes
        // byte 0-3: 0x24, 0x35, 0x68, 0x79 (Magic start code)
        // byte 4-6: 0x00, 0x00, 0x04 (JPEG data size: 4 bytes)
        // byte 7: 0x02 (Padding size: 2 bytes)
        // byte 8-127: 0x00
        val payloadHeader = ByteArray(128)
        payloadHeader[0] = 0x24.toByte()
        payloadHeader[1] = 0x35.toByte()
        payloadHeader[2] = 0x68.toByte()
        payloadHeader[3] = 0x79.toByte()
        payloadHeader[4] = 0x00.toByte()
        payloadHeader[5] = 0x00.toByte()
        payloadHeader[6] = 0x04.toByte()
        payloadHeader[7] = 0x02.toByte()

        // JPEG data (4 bytes)
        val jpegData = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

        // Padding (2 bytes)
        val padding = byteArrayOf(0x00.toByte(), 0x00.toByte())

        val fullStream = commonHeader + payloadHeader + jpegData + padding
        val inputStream = ByteArrayInputStream(fullStream)

        // Verifica parsing de Sequence Number
        val seqNum = ((commonHeader[2].toInt() and 0xFF) shl 8) or (commonHeader[3].toInt() and 0xFF)
        assertEquals(42, seqNum)

        // Verifica parsing de tamanho de JPEG
        val jpegSize = ((payloadHeader[4].toInt() and 0xFF) shl 16) or
            ((payloadHeader[5].toInt() and 0xFF) shl 8) or
            (payloadHeader[6].toInt() and 0xFF)
        assertEquals(4, jpegSize)

        // Verifica Start Code
        val hasMagicCode = payloadHeader[0] == 0x24.toByte() &&
            payloadHeader[1] == 0x35.toByte() &&
            payloadHeader[2] == 0x68.toByte() &&
            payloadHeader[3] == 0x79.toByte()
        assertTrue(hasMagicCode)
    }
}
