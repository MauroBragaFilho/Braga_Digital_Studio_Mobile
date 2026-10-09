package com.bragastudio.mobile.coremedia.bsp

import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BspFeedbackParserTest {

    /** Monta um pacote de retorno: 'B','F', versão, tipo, len (u16 LE) e o corpo. */
    private fun packet(type: Int, body: ByteArray, version: Int = 2, magic0: Char = 'B', magic1: Char = 'F', lenOverride: Int? = null): ByteArray {
        val len = lenOverride ?: body.size
        return byteArrayOf(magic0.code.toByte(), magic1.code.toByte(), version.toByte(), type.toByte(), (len and 0xFF).toByte(), (len ushr 8).toByte()) + body
    }

    private fun le32(v: Long) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())

    private fun report(): ByteArray = le32(123_456) + le16(4_321) + byteArrayOf(26) + le16(12) + le16(-300 and 0xFFFF) + le16(45) + le16(9_000) + byteArrayOf(5)

    private fun parse(p: ByteArray) = BspFeedbackParser.parse(p, p.size)

    @Test
    fun reportIsParsedFieldByField() {
        val r = parse(packet(1, report())) as BspFeedbackMessage.Report
        assertEquals(123_456L, r.tsMs)
        assertEquals(4_321, r.highestSeqVideo)
        assertEquals(26, r.lossFracVideo)
        assertEquals(12, r.jitterMs)
        assertEquals("i16 com sinal", -300, r.delayGradientUs)
        assertEquals(45, r.bufferMs)
        assertEquals(9_000, r.recvKbps)
        assertEquals(5, r.lossFracAudio)
        assertEquals(26 * 100f / 255f, r.videoLossPercent, 0.001f)
        assertEquals(100f, (parse(packet(1, report().also { it[6] = 255.toByte() })) as BspFeedbackMessage.Report).videoLossPercent, 0.001f)
    }

    @Test
    fun pliAndNackAreParsed() {
        val pli = parse(packet(3, le32(0xDEADBEEFL))) as BspFeedbackMessage.Pli
        assertEquals(0xDEADBEEFL, pli.ssrc)
        val nack = parse(packet(2, le32(7) + le16(1000) + le16(0b101))) as BspFeedbackMessage.Nack
        assertEquals(7L, nack.ssrc)
        assertEquals(1000, nack.baseSeq)
        assertEquals(5, nack.bitmask)
        assertTrue(parse(packet(4, ByteArray(8))) is BspFeedbackMessage.SrEcho)
    }

    @Test
    fun invalidHeadersAreRejected() {
        assertNull(parse(packet(3, le32(1), magic0 = 'X')))
        assertNull(parse(packet(3, le32(1), magic1 = 'x')))
        assertNull("versão 1", parse(packet(3, le32(1), version = 1)))
        assertNull("versão 3", parse(packet(3, le32(1), version = 3)))
        assertNull("tipo desconhecido", parse(packet(9, le32(1))))
        assertNull("tipo 0", parse(packet(0, le32(1))))
    }

    @Test
    fun lengthMismatchesAreRejected() {
        assertNull("len maior que o corpo", parse(packet(3, le32(1), lenOverride = 5)))
        assertNull("len menor que o corpo", parse(packet(3, le32(1), lenOverride = 3)))
        assertNull("PLI com corpo errado", parse(packet(3, le32(1) + byteArrayOf(0))))
        assertNull("REPORT curto", parse(packet(1, report().copyOf(15))))
        assertNull("REPORT longo", parse(packet(1, report() + byteArrayOf(0))))
        assertNull("NACK curto", parse(packet(2, ByteArray(7))))
        assertNull("SR_ECHO enorme", parse(packet(4, ByteArray(65))))
    }

    @Test
    fun truncatedAndEmptyInputsAreRejected() {
        assertNull(BspFeedbackParser.parse(ByteArray(0), 0))
        assertNull(BspFeedbackParser.parse(ByteArray(5), 5))
        assertNull("length acima do buffer", BspFeedbackParser.parse(ByteArray(6), 100))
        val full = packet(3, le32(1))
        for (cut in 0 until full.size) assertNull("cortado em $cut", BspFeedbackParser.parse(full, cut))
        assertNotNull(parse(full))
    }

    @Test
    fun randomGarbageNeverThrows() {
        val rnd = Random(12345)
        repeat(20_000) {
            val len = rnd.nextInt(80)
            val buf = ByteArray(len).also { rnd.nextBytes(it) }
            // metade dos casos começa com um cabeçalho plausível para exercitar os corpos
            if (len >= 6 && rnd.nextBoolean()) {
                buf[0] = 'B'.code.toByte()
                buf[1] = 'F'.code.toByte()
                buf[2] = 2
                buf[3] = (1 + rnd.nextInt(4)).toByte()
                buf[4] = (len - 6).toByte()
                buf[5] = 0
            }
            BspFeedbackParser.parse(buf, len) // não pode lançar
        }
    }
}

class BspTxtTest {

    @Test
    fun txtHasTheSpecRecords() {
        val txt = BspTxt.build("uuid-1234", "BDSM (Scorpio)", 1920, 1080, 30, 8080, live = false)
        assertEquals("2", txt["v"])
        assertEquals("uuid-1234", txt["id"])
        assertEquals("BDSM (Scorpio)", txt["name"])
        assertEquals("h264", txt["vc"])
        assertEquals("aac", txt["ac"])
        assertEquals("1920x1080@30", txt["res"])
        assertEquals("link", txt["auth"])
        assertEquals("8080", txt["link"])
        assertEquals("idle", txt["st"])
        assertEquals("live", BspTxt.build("i", "n", 1280, 720, 60, 8080, live = true)["st"])
        assertEquals("1280x720@60", BspTxt.build("i", "n", 1280, 720, 60, 8080, live = true)["res"])
    }

    @Test
    fun txtNeverCarriesAnAddressOrSecret() {
        val txt = BspTxt.build("uuid", "BDSM (X)", 1920, 1080, 30, 8080, live = false)
        val all = txt.entries.joinToString(" ") { "${it.key}=${it.value}" }
        assertFalse(Regex("""\d+\.\d+\.\d+\.\d+""").containsMatchIn(all))
        assertFalse(txt.keys.any { it.contains("token", true) || it.contains("key", true) || it.contains("ip", true) })
    }

    @Test
    fun audioRecordIsOmittedWithoutAudio() {
        assertFalse(BspTxt.build("i", "n", 1920, 1080, 30, 8080, live = false, hasAudio = false).containsKey("ac"))
    }

    @Test
    fun instanceNameIsTheNdiStyleNameAndFitsADnsLabel() {
        assertEquals("BDSM (Scorpio)", BspTxt.instanceName("BDSM", "Scorpio"))
        val long = BspTxt.instanceName("BDSM", "ç".repeat(100))
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= 63)
        assertTrue("sem partir caractere", long.none { it == '�' })
        assertEquals("BDSM (AB)", BspTxt.instanceName("BDSM", "A\u0000B"))
    }

    @Test
    fun clampKeepsEntriesWithin255BytesAndTotalBudget() {
        val big = BspTxt.clamp(mapOf("name" to "x".repeat(1000)))
        assertTrue("name=".length + big["name"]!!.length <= 255)
        val many = (1..200).associate { "k$it" to "v".repeat(40) }
        val clamped = BspTxt.clamp(many)
        val total = clamped.entries.sumOf { it.key.length + 1 + it.value.length + 1 }
        assertTrue(total <= 1300)
        assertTrue(clamped.size < 200)
        assertEquals("ordem preservada", "k1", clamped.keys.first())
        assertTrue("chave vazia ou com = é descartada", BspTxt.clamp(mapOf("" to "a", "a=b" to "c")).keys.none { it.isEmpty() || it.contains('=') })
    }

    @Test
    fun truncateUtf8NeverSplitsACodePoint() {
        assertEquals("abc", BspTxt.truncateUtf8("abc", 10))
        assertEquals("ab", BspTxt.truncateUtf8("abç", 3)) // ç ocupa 2 bytes: não cabe
        assertEquals("abç", BspTxt.truncateUtf8("abç", 4))
        val emoji = "a😀b" // 😀 = 4 bytes
        assertEquals("a", BspTxt.truncateUtf8(emoji, 3))
        assertEquals("a😀", BspTxt.truncateUtf8(emoji, 5))
    }

    @Test
    fun serviceTypeIsBspTcp() {
        assertEquals("_bsp._tcp", BspTxt.SERVICE_TYPE)
    }
}
