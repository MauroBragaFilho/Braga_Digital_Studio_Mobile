package com.bragastudio.mobile.network.sony

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.SocketException
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyProtocolTest {

    private val camera = InetAddress.getByName("192.168.122.1")

    // ---- SSDP / URLs (M27) -----------------------------------------------------------------

    @Test
    fun `location no mesmo host e rede privada e aceito`() {
        val url = SonyProtocolRules.validateLocation("http://192.168.122.1:64321/dd.xml", camera)
        assertNotNull(url)
        assertEquals(64321, url!!.port)
    }

    @Test
    fun `location de outro host e rejeitado`() {
        assertNull(SonyProtocolRules.validateLocation("http://192.168.122.99:64321/dd.xml", camera))
        assertNull(SonyProtocolRules.validateLocation("http://evil.example.com/dd.xml", camera))
    }

    @Test
    fun `location de resposta vinda de rede publica ou loopback e rejeitado`() {
        val publicIp = InetAddress.getByName("8.8.8.8")
        assertNull(SonyProtocolRules.validateLocation("http://8.8.8.8/dd.xml", publicIp))
        val loopback = InetAddress.getByName("127.0.0.1")
        assertNull(SonyProtocolRules.validateLocation("http://127.0.0.1/dd.xml", loopback))
    }

    @Test
    fun `location com esquema nao http ou credenciais e rejeitado`() {
        assertNull(SonyProtocolRules.validateLocation("https://192.168.122.1/dd.xml", camera))
        assertNull(SonyProtocolRules.validateLocation("file:///etc/passwd", camera))
        assertNull(SonyProtocolRules.validateLocation("http://user:pw@192.168.122.1/dd.xml", camera))
        assertNull(SonyProtocolRules.validateLocation("nao e url", camera))
    }

    @Test
    fun `faixas privadas`() {
        for (ip in listOf("10.1.2.3", "172.16.0.5", "172.31.255.1", "192.168.0.1", "169.254.1.1")) {
            assertTrue(ip, SonyProtocolRules.isPrivateAddress(InetAddress.getByName(ip)))
        }
        for (ip in listOf("8.8.8.8", "172.32.0.1", "1.1.1.1", "127.0.0.1", "239.255.255.250", "0.0.0.0")) {
            assertFalse(ip, SonyProtocolRules.isPrivateAddress(InetAddress.getByName(ip)))
        }
    }

    @Test
    fun `porta efetiva trata -1`() {
        assertEquals(80, SonyProtocolRules.effectivePort(URL("http://192.168.122.1/dd.xml")))
        assertEquals(8080, SonyProtocolRules.effectivePort(URL("http://192.168.122.1:8080/dd.xml")))
    }

    @Test
    fun `mesma origem so vale para o mesmo host`() {
        val dd = URL("http://192.168.122.1:64321/dd.xml")
        assertTrue(SonyProtocolRules.sameHost(URL("http://192.168.122.1:8080/sony"), dd))
        assertFalse(SonyProtocolRules.sameHost(URL("http://10.0.0.5:8080/sony"), dd))
    }

    @Test
    fun `leitura limitada falha acima do teto`() {
        val ok = SonyProtocolRules.readLimited(ByteArrayInputStream(ByteArray(100)), 100)
        assertEquals(100, ok.size)
        assertThrows(IOException::class.java) {
            SonyProtocolRules.readLimited(ByteArrayInputStream(ByteArray(101)), 100)
        }
    }

    // ---- JSON-RPC (M28) ----------------------------------------------------------------------

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `isOk exige resposta sem erro`() {
        val ok = json.decodeFromString(SonyJsonRpcResponse.serializer(), """{"id":1,"result":[0]}""")
        val err = json.decodeFromString(SonyJsonRpcResponse.serializer(), """{"id":1,"error":[40402,"Already running"]}""")
        assertTrue(ok.isOk())
        assertFalse(err.isOk())
        // falha de requisicao (resposta nula) NAO e sucesso — era o bug de `null?.error == null`
        val none: SonyJsonRpcResponse? = null
        assertFalse(none.isOk())
        assertEquals(40402, err.errorCode())
        assertNull(ok.errorCode())
        assertNull(none.errorCode())
    }

    @Test
    fun `ids do JSON-RPC serializam e resposta com results tambem decodifica`() {
        val r = json.decodeFromString(SonyJsonRpcResponse.serializer(), """{"id":7,"results":["http://x"]}""")
        assertEquals(7, r.id)
        assertEquals(JsonPrimitive("http://x"), r.results!![0])
    }

    // ---- Liveview (M28): ressincronizacao -------------------------------------------------------

    private fun frameStart(seq: Int): ByteArray {
        val w = ByteArray(12)
        w[0] = 0xFF.toByte()
        w[1] = 0x01
        w[2] = (seq shr 8).toByte()
        w[3] = seq.toByte()
        w[8] = 0x24
        w[9] = 0x35
        w[10] = 0x68
        w[11] = 0x79
        return w
    }

    @Test
    fun `isFrameStart reconhece 0xFF mais start code`() {
        assertTrue(SonyLiveviewSocketReader.isFrameStart(frameStart(1)))
        val bad = frameStart(1).also { it[10] = 0 }
        assertFalse(SonyLiveviewSocketReader.isFrameStart(bad))
        val bad2 = frameStart(1).also { it[0] = 0 }
        assertFalse(SonyLiveviewSocketReader.isFrameStart(bad2))
    }

    @Test
    fun `resync descarta lixo e para no proximo quadro`() {
        // 12 bytes de lixo ja lidos na janela + mais lixo no fluxo (inclui 0xFF soltos) + quadro valido
        val garbage = byteArrayOf(0x11, 0xFF.toByte(), 0x22, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x24, 0x35)
        val stream = garbage + frameStart(42) + byteArrayOf(9, 9, 9)
        val window = ByteArray(12) { 0x55 }
        SonyLiveviewSocketReader.resync(ByteArrayInputStream(stream), window)
        assertArrayEquals(frameStart(42), window)
    }

    @Test
    fun `resync sem quadro valido termina com erro no fim do fluxo`() {
        val window = ByteArray(12)
        assertThrows(SocketException::class.java) {
            SonyLiveviewSocketReader.resync(ByteArrayInputStream(ByteArray(500) { 0xFF.toByte() }), window)
        }
    }
}
