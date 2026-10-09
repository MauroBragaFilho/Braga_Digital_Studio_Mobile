package com.bragastudio.mobile.coremedia.bsp

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BspProtocolTest {

    private val b64 = Base64.getEncoder()
    private val nonce16 = ByteArray(16) { (it + 1).toByte() }
    private val proof32 = ByteArray(32) { (it * 3).toByte() }

    private fun helloJson(
        version: Int = 2,
        clientId: String = "obs-1234",
        name: String = "OBS (PC)",
        nonce: ByteArray = nonce16,
        proof: ByteArray = proof32,
        scheme: String = "link-token",
        extra: String = "",
    ): String {
        val caps = """{"video":["h264","hevc"],"audio":["aac","opus"],"nack":true,"fec":true,"aead":true}"""
        return "{\"type\":\"HELLO\",\"protocolVersion\":$version,\"clientId\":\"$clientId\",\"clientName\":\"$name\"," +
            "\"nonce\":\"${b64.encodeToString(nonce)}\",\"caps\":$caps,\"udpPort\":50123," +
            "\"auth\":{\"scheme\":\"$scheme\",\"proof\":\"${b64.encodeToString(proof)}\"}$extra}"
    }

    private fun parse(json: String): HelloParse = BspProtocol.parseHello(BspProtocol.parseLine(json)!!)

    // ---- HELLO -------------------------------------------------------------------------

    @Test
    fun validHello_isParsedWithAllFields() {
        val ok = parse(helloJson()) as HelloParse.Ok
        val h = ok.hello
        assertEquals("obs-1234", h.clientId)
        assertEquals("OBS (PC)", h.clientName)
        assertArrayEquals(nonce16, h.nonceR)
        assertArrayEquals(proof32, h.proof)
        assertEquals(listOf("h264", "hevc"), h.videoCodecs)
        assertEquals(listOf("aac", "opus"), h.audioCodecs)
        assertEquals(true, h.aead)
        assertEquals(50123, h.udpPort)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val extra = ""","futuro":{"a":[1,2,3]},"x":"y""""
        assertTrue(parse(helloJson(extra = extra)) is HelloParse.Ok)
    }

    @Test
    fun otherProtocolVersionIsRejectedWithVersionAndSupportedList() {
        for (v in intArrayOf(1, 3, 0, 99)) {
            val r = parse(helloJson(version = v)) as HelloParse.Rejected
            assertEquals(BspError.VERSION, r.code)
            assertEquals(listOf(2), r.supportedVersions)
        }
        val json = BspProtocol.error(BspError.VERSION, "x", listOf(2))
        assertEquals(2, JSONObject(json).getJSONArray("supported").getInt(0))
    }

    @Test
    fun missingOrInvalidFields_becomeAuthErrors() {
        val cases = listOf(
            helloJson(clientId = ""),
            helloJson(clientId = "x".repeat(65)),
            helloJson(nonce = ByteArray(15)),
            helloJson(nonce = ByteArray(17)),
            helloJson(proof = ByteArray(31)),
            helloJson(proof = ByteArray(33)),
            helloJson(scheme = "senha"),
            """{"type":"HELLO","protocolVersion":2}""",
            """{"type":"HELLO","protocolVersion":2,"clientId":"a","nonce":"???","auth":{"scheme":"link-token","proof":"???"}}""",
            """{"type":"HELLO","protocolVersion":2,"clientId":"a","nonce":"${b64.encodeToString(nonce16)}"}""",
            """{"type":"HELLO","protocolVersion":"dois","clientId":"a"}""",
        )
        for (c in cases) {
            val r = parse(c)
            assertTrue("deveria recusar: $c", r is HelloParse.Rejected)
            r as HelloParse.Rejected
            assertTrue(r.code == BspError.AUTH || r.code == BspError.VERSION)
        }
    }

    @Test
    fun udpPortOutOfRangeIsDropped_andOptionalCapsAreNull() {
        val json = "{\"type\":\"HELLO\",\"protocolVersion\":2,\"clientId\":\"a\",\"nonce\":\"${b64.encodeToString(nonce16)}\",\"udpPort\":80," +
            "\"auth\":{\"scheme\":\"link-token\",\"proof\":\"${b64.encodeToString(proof32)}\"}}"
        val h = (parse(json) as HelloParse.Ok).hello
        assertNull(h.udpPort)
        assertNull(h.audioCodecs)
        assertNull(h.aead)
        assertTrue(h.videoCodecs.isEmpty())
        assertNull(BspProtocol.readyPort(JSONObject("""{"type":"READY","udpPort":70000}""")))
        assertNull(BspProtocol.readyPort(JSONObject("""{"type":"READY"}""")))
        assertEquals(50000, BspProtocol.readyPort(JSONObject("""{"type":"READY","udpPort":50000}""")))
    }

    @Test
    fun controlCharactersAndLengthInNamesAreSanitized() {
        val r = parse(helloJson(name = "A\\u0000B\\n" + "x".repeat(100))) as HelloParse.Ok
        assertTrue(r.hello.clientName.none { it.isISOControl() })
        assertEquals(BspLimits.MAX_CLIENT_NAME, r.hello.clientName.length)
    }

    @Test
    fun capsListsAreCappedAtSixteenEntries() {
        val many = (1..40).joinToString(",") { "\"c$it\"" }
        val json = "{\"type\":\"HELLO\",\"protocolVersion\":2,\"clientId\":\"a\",\"nonce\":\"${b64.encodeToString(nonce16)}\",\"caps\":{\"video\":[$many]}," +
            "\"auth\":{\"scheme\":\"link-token\",\"proof\":\"${b64.encodeToString(proof32)}\"}}"
        assertEquals(16, (parse(json) as HelloParse.Ok).hello.videoCodecs.size)
    }

    // ---- parseLine ---------------------------------------------------------------------

    @Test
    fun malformedLinesReturnNull() {
        for (bad in listOf("", "   ", "not json", "{", "{\"a\":", "[1,2,3]", "123", "\"text\"", "{'a':}", "{\"type\":\"HELLO\"")) {
            assertNull("linha: <$bad>", BspProtocol.parseLine(bad))
        }
        assertNotNull(BspProtocol.parseLine("""{"type":"BYE"}"""))
    }

    @Test
    fun typeOfIsBoundedAndTolerantOfMissingType() {
        assertEquals("", BspProtocol.typeOf(JSONObject("{}")))
        assertEquals(32, BspProtocol.typeOf(JSONObject("""{"type":"${"A".repeat(500)}"}""")).length)
    }

    // ---- Base64 ------------------------------------------------------------------------

    @Test
    fun base64DecodeEnforcesLimits() {
        assertArrayEquals(nonce16, BspProtocol.decode(b64.encodeToString(nonce16), 16))
        assertNull(BspProtocol.decode(b64.encodeToString(nonce16), 15))
        assertNull(BspProtocol.decode("@@@@", 16))
        assertNull(BspProtocol.decode(null, 16))
        assertNull(BspProtocol.decode("A".repeat(10_000), 16))
    }

    // ---- Montagem das mensagens --------------------------------------------------------

    @Test
    fun welcomeFollowsTheSpecShapeAndUsesUnsignedIds() {
        val video = BspVideoParams(1920, 1080, 30, 10_000, ssrc = -2, payloadType = 96, sps = byteArrayOf(0x67, 1), pps = byteArrayOf(0x68, 2), orientation = 90)
        val audio = BspAudioParams(48_000, 2, ssrc = 2222, payloadType = 97, config = byteArrayOf(0x11, 0x90.toByte()))
        val net = BspNetParams(1200, 80, 0, false, 7071, true)
        val json = JSONObject(BspProtocol.welcome(305419896, "Scorpio", nonce16, video, audio, net, 2000, proof32))
        assertEquals("WELCOME", json.getString("type"))
        assertEquals(2, json.getInt("protocolVersion"))
        assertEquals(305419896L, json.getLong("sessionId"))
        assertEquals("Scorpio", json.getString("deviceName"))
        assertArrayEquals(nonce16, Base64.getDecoder().decode(json.getString("nonce")))
        val v = json.getJSONObject("video")
        assertEquals("h264", v.getString("codec"))
        assertEquals(1920, v.getInt("width"))
        assertEquals(4294967294L, v.getLong("ssrc"))
        assertEquals(96, v.getInt("pt"))
        assertEquals(90, v.getInt("orientation"))
        assertArrayEquals(byteArrayOf(0x67, 1), Base64.getDecoder().decode(v.getString("sps")))
        val a = json.getJSONObject("audio")
        assertEquals("aac", a.getString("codec"))
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), Base64.getDecoder().decode(a.getString("config")))
        val n = json.getJSONObject("net")
        assertEquals(1200, n.getInt("mtu"))
        assertEquals(7071, n.getInt("feedbackPort"))
        assertTrue(n.getBoolean("aead"))
        assertFalse(n.getBoolean("nack"))
        assertEquals(2000, json.getInt("keyframeIntervalMs"))
        assertArrayEquals(proof32, Base64.getDecoder().decode(json.getJSONObject("auth").getString("proof")))
        assertFalse("a linha não pode ter quebra", BspProtocol.welcome(1, "x", nonce16, video, null, net, 2000, proof32).contains('\n'))
    }

    @Test
    fun welcomeOmitsMissingSpsPpsAndAudio() {
        val video = BspVideoParams(1280, 720, 30, 6000, 5, 96, null, null, 0)
        val json = JSONObject(BspProtocol.welcome(1, "x", nonce16, video, null, BspNetParams(1200, 80, 0, false, 7071, true), 2000, proof32))
        assertFalse(json.getJSONObject("video").has("sps"))
        assertFalse(json.getJSONObject("video").has("pps"))
        assertFalse(json.has("audio"))
    }

    @Test
    fun deviceNameWithQuotesAndBackslashesIsEscapedCorrectly() {
        val video = BspVideoParams(1920, 1080, 30, 10_000, 1, 96, null, null, 0)
        val line = BspProtocol.welcome(1, "Câmera \"A\\B\"", nonce16, video, null, BspNetParams(1200, 80, 0, false, 7071, true), 2000, proof32)
        assertEquals("Câmera \"A\\B\"", JSONObject(line).getString("deviceName"))
    }

    @Test
    fun smallMessagesCarryTheirFields() {
        assertEquals("BYE", JSONObject(BspProtocol.bye()).getString("type"))
        assertEquals("START", JSONObject(BspProtocol.start()).getString("type"))
        val stop = JSONObject(BspProtocol.stop("monitor_closed"))
        assertEquals("STOP", stop.getString("type"))
        assertEquals("monitor_closed", stop.getString("reason"))
        val hb = JSONObject(BspProtocol.heartbeat(7, 1234))
        assertEquals(7L, hb.getLong("id"))
        assertEquals(1234L, hb.getLong("ts"))
        assertEquals("HEARTBEAT_ACK", JSONObject(BspProtocol.heartbeatAck(7, 1234)).getString("type"))
        val err = JSONObject(BspProtocol.error(BspError.BUSY, "cheio"))
        assertEquals("BUSY", err.getString("code"))
        assertFalse(err.has("supported"))
        val meta = JSONObject(BspProtocol.meta(BspMeta(orientation = 90, lens = "1x", battery = 73, rec = true, thermal = 2)))
        assertEquals("META", meta.getString("type"))
        assertEquals(90, meta.getInt("orientation"))
        assertEquals("1x", meta.getString("lens"))
        assertEquals(73, meta.getInt("battery"))
        assertTrue(meta.getBoolean("rec"))
    }

    @Test
    fun senderReportMapsNtpToRtpForBothStreams() {
        val line = BspProtocol.senderReport(3_900_000_000L, 123, BspStreamReport(1, 90_000, 10, 2000), BspStreamReport(2, 48_000, 5, 900))
        val j = JSONObject(line)
        assertEquals("SR", j.getString("type"))
        assertEquals(3_900_000_000L, j.getLong("ntpSec"))
        assertEquals(90_000L, j.getJSONObject("video").getLong("rtp"))
        assertEquals(48_000L, j.getJSONObject("audio").getLong("rtp"))
        assertFalse(JSONObject(BspProtocol.senderReport(1, 1, BspStreamReport(1, 1, 1, 1), null)).has("audio"))
    }

    @Test
    fun ntpConversion() {
        // 1970-01-01 = 2208988800 s de NTP
        assertEquals(2_208_988_800L, BspNtp.seconds(0))
        assertEquals(0L, BspNtp.fraction(0))
        assertEquals(2_208_988_801L, BspNtp.seconds(1000))
        // meio segundo = 2^31
        assertEquals(1L shl 31, BspNtp.fraction(500))
        assertEquals(2_208_988_800L + 1_700_000_000L, BspNtp.seconds(1_700_000_000_000L))
    }

    // ---- Leitor de linhas com teto -----------------------------------------------------

    private fun reader(text: String, max: Int = BspLimits.MAX_LINE_BYTES) = BoundedLineReader(ByteArrayInputStream(text.toByteArray()), max)

    @Test
    fun linesAreSplitOnNewlineAndCrIsStripped() {
        val r = reader("um\ndois\r\n\ntres")
        assertEquals("um", r.readLine())
        assertEquals("dois", r.readLine())
        assertEquals("", r.readLine())
        assertNull("a última linha sem \\n não é entregue (fim do stream)", r.readLine())
    }

    @Test
    fun lineOfExactlyTheLimitIsAcceptedAndOneMoreByteFails() {
        val ok = "a".repeat(BspLimits.MAX_LINE_BYTES)
        assertEquals(ok, reader("$ok\n").readLine())
        try {
            reader("$ok" + "b\n").readLine()
            fail("deveria estourar o limite de 8 KiB")
        } catch (_: BspLineTooLongException) {
            // esperado
        }
    }

    @Test
    fun endlessInputWithoutNewlineStopsAtTheLimit() {
        val endless = object : InputStream() {
            var served = 0L
            override fun read(): Int = 'x'.code.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                for (i in 0 until len) b[off + i] = 'x'.code.toByte()
                served += len
                return len
            }
        }
        try {
            BoundedLineReader(endless).readLine()
            fail("deveria estourar")
        } catch (_: BspLineTooLongException) {
            assertTrue("não pode ler muito além do teto", endless.served < 20_000)
        }
    }

    @Test
    fun multiByteUtf8IsDecodedAndTimeoutsDoNotLoseData() {
        // entrega "olá\n" aos pedaços, com um timeout no meio
        val parts = listOf("ol".toByteArray(), byteArrayOf(0xC3.toByte()), byteArrayOf(0xA1.toByte(), '\n'.code.toByte()))
        var i = 0
        var timedOut = false
        val stream = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (i == 1 && !timedOut) {
                    timedOut = true
                    throw SocketTimeoutException()
                }
                if (i >= parts.size) return -1
                val p = parts[i++]
                System.arraycopy(p, 0, b, off, p.size)
                return p.size
            }
        }
        val r = BoundedLineReader(stream)
        try {
            r.readLine()
            fail("o timeout deveria subir")
        } catch (_: SocketTimeoutException) {
            // esperado: o estado parcial fica no leitor
        }
        assertEquals("olá", r.readLine())
    }

    @Test
    fun readerEndsCleanlyOnClosedStreamAndPropagatesIo() {
        assertNull(reader("").readLine())
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("caiu")
        }
        try {
            BoundedLineReader(broken).readLine()
            fail()
        } catch (_: IOException) {
            // esperado
        }
    }
}
