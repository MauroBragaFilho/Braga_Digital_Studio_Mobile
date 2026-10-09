package com.bragastudio.mobile.coremedia.bsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H264RtpPacketizerTest {

    private val packets = mutableListOf<ByteArray>()

    private fun packetizer(
        initialSeq: Int = 1000,
        ssrc: Int = 0x11223344,
        maxPayload: Int = H264RtpPacketizer.MAX_RTP_PAYLOAD,
    ) = H264RtpPacketizer(
        sink = RtpPacketSink { buf, len -> packets += buf.copyOf(len) },
        payloadType = 96,
        ssrc = ssrc,
        initialSequenceNumber = initialSeq,
        maxPayload = maxPayload,
    )

    private val startCode4 = byteArrayOf(0, 0, 0, 1)
    private val startCode3 = byteArrayOf(0, 0, 1)

    /** NAL sintético: header + corpo sem sequências 00 00 0x (não confunde o parser Annex-B). */
    private fun nal(header: Int, size: Int): ByteArray = ByteArray(size) { i -> if (i == 0) header.toByte() else ((i % 200) + 5).toByte() }

    private fun marker(p: ByteArray) = (p[1].toInt() and 0x80) != 0
    private fun payloadType(p: ByteArray) = p[1].toInt() and 0x7F
    private fun seq(p: ByteArray) = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
    private fun timestamp(p: ByteArray): Long = ((p[4].toLong() and 0xFF) shl 24) or ((p[5].toLong() and 0xFF) shl 16) or
        ((p[6].toLong() and 0xFF) shl 8) or (p[7].toLong() and 0xFF)
    private fun payload(p: ByteArray) = p.copyOfRange(12, p.size)
    private fun nalType(payload: ByteArray) = payload[0].toInt() and 0x1F

    /** Lê um STAP-A: devolve as NALs agregadas. */
    private fun stapNals(payload: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var i = 1
        while (i + 2 <= payload.size) {
            val len = ((payload[i].toInt() and 0xFF) shl 8) or (payload[i + 1].toInt() and 0xFF)
            out += payload.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
        }
        assertEquals("o STAP-A não pode ter sobras", payload.size, i)
        return out
    }

    @Test
    fun singleNal_isSentAsOnePacketWithMarkerAndRtpHeader() {
        val nal = nal(0x41, 100)
        packetizer().sendEncodedFrame(startCode4 + nal, presentationTimeUs = 0)

        assertEquals(1, packets.size)
        val p = packets[0]
        assertEquals(0x80, p[0].toInt() and 0xFF) // V=2, P=0, X=0, CC=0
        assertEquals(96, payloadType(p))
        assertTrue("marker no único NAL do frame", marker(p))
        assertEquals(1000, seq(p))
        assertEquals(
            0x11223344,
            ((p[8].toInt() and 0xFF) shl 24) or ((p[9].toInt() and 0xFF) shl 16) or
                ((p[10].toInt() and 0xFF) shl 8) or (p[11].toInt() and 0xFF),
        )
        assertArrayEquals(nal, payload(p))
    }

    @Test
    fun threeByteStartCode_isAlsoSplitCorrectly() {
        val nal = nal(0x41, 50)
        packetizer().sendEncodedFrame(startCode3 + nal, 0)
        assertEquals(1, packets.size)
        assertArrayEquals(nal, payload(packets[0]))
    }

    @Test
    fun dataWithoutStartCode_isASingleNal() {
        val nal = nal(0x41, 40)
        packetizer().sendEncodedFrame(nal, 0)
        assertEquals(1, packets.size)
        assertArrayEquals(nal, payload(packets[0]))
    }

    @Test
    fun spsPpsInTheFrame_areAggregatedInOneStapAThenIdr() {
        val sps = nal(0x67, 20)
        val pps = nal(0x68, 8)
        val idr = nal(0x65, 300)
        packetizer().sendEncodedFrame(startCode4 + sps + startCode4 + pps + startCode3 + idr, 33_333)

        assertEquals("STAP-A (SPS+PPS) + IDR", 2, packets.size)
        val stap = payload(packets[0])
        assertEquals(24, nalType(stap))
        // NRI do STAP-A = o maior NRI das NALs agregadas (SPS/PPS: 3)
        assertEquals(0x60, stap[0].toInt() and 0x60)
        val inside = stapNals(stap)
        assertEquals(2, inside.size)
        assertArrayEquals(sps, inside[0])
        assertArrayEquals(pps, inside[1])
        assertArrayEquals(idr, payload(packets[1]))
        assertEquals(listOf(false, true), packets.map { marker(it) })
        assertEquals("mesmo timestamp em todo o quadro", 1, packets.map { timestamp(it) }.distinct().size)
    }

    @Test
    fun idrWithoutInBandParameterSets_getsTheCachedOnesAsStapA() {
        val sps = nal(0x67, 25)
        val pps = nal(0x68, 6)
        val idr = nal(0x65, 200)
        val p = packetizer()
        p.setParameterSets(sps, pps)
        p.sendEncodedFrame(startCode4 + idr, 0)

        assertEquals(2, packets.size)
        val inside = stapNals(payload(packets[0]))
        assertArrayEquals(sps, inside[0])
        assertArrayEquals(pps, inside[1])
        assertArrayEquals(idr, payload(packets[1]))
        assertFalse("o STAP-A não leva o marker", marker(packets[0]))
        assertTrue(marker(packets[1]))
    }

    @Test
    fun nonIdrFrame_neverGetsParameterSets() {
        val p = packetizer()
        p.setParameterSets(nal(0x67, 25), nal(0x68, 6))
        p.sendEncodedFrame(startCode4 + nal(0x41, 200), 0)
        assertEquals(1, packets.size)
        assertEquals(1, nalType(payload(packets[0])))
    }

    @Test
    fun idrWithoutCachedParameterSets_isSentAlone() {
        packetizer().sendEncodedFrame(startCode4 + nal(0x65, 200), 0)
        assertEquals(1, packets.size)
    }

    @Test
    fun frameWithOnlyParameterSets_putsTheMarkerOnTheStapA() {
        packetizer().sendEncodedFrame(startCode4 + nal(0x67, 20) + startCode4 + nal(0x68, 8), 0)
        assertEquals(1, packets.size)
        assertEquals(24, nalType(payload(packets[0])))
        assertTrue(marker(packets[0]))
    }

    @Test
    fun parameterSetsTooBigForOneStap_areSentOneByOne() {
        val sps = nal(0x67, 700)
        val pps = nal(0x68, 700)
        packetizer().sendEncodedFrame(startCode4 + sps + startCode4 + pps + startCode4 + nal(0x65, 50), 0)
        assertEquals(3, packets.size)
        assertArrayEquals(sps, payload(packets[0]))
        assertArrayEquals(pps, payload(packets[1]))
    }

    @Test
    fun largeNal_isFragmentedInFuA_withStartEndBitsAndMarkerOnlyOnLast() {
        val big = nal(0x41, 4000) // header 0x41: nri=2, type=1
        packetizer().sendEncodedFrame(startCode4 + big, 0)

        assertTrue(packets.size > 2)
        val maxPayload = H264RtpPacketizer.MAX_RTP_PAYLOAD
        packets.forEach { assertTrue(it.size - 12 <= maxPayload) }

        val first = payload(packets.first())
        val last = payload(packets.last())
        // FU indicator: NRI do NAL original (0x60) | tipo 28
        assertEquals((0x41 and 0x60) or 28, first[0].toInt() and 0xFF)
        assertEquals(0x80 or 1, first[1].toInt() and 0xFF) // S=1, tipo original 1
        assertEquals(0x40 or 1, last[1].toInt() and 0xFF) // E=1
        packets.subList(1, packets.size - 1).forEach {
            assertEquals(1, payload(it)[1].toInt() and 0xFF) // meio: sem S nem E
        }
        assertEquals(List(packets.size - 1) { false } + true, packets.map { marker(it) })

        // Remontagem: header original + concatenação dos fragmentos == NAL original
        val rebuilt = ByteArray(1 + packets.sumOf { payload(it).size - 2 })
        rebuilt[0] = ((first[0].toInt() and 0x60) or (first[1].toInt() and 0x1F)).toByte()
        var off = 1
        packets.forEach {
            val body = payload(it).copyOfRange(2, payload(it).size)
            System.arraycopy(body, 0, rebuilt, off, body.size)
            off += body.size
        }
        assertArrayEquals(big, rebuilt)
    }

    @Test
    fun fuA_testVector_threeFragments() {
        // payload mínimo (64): cada pacote carrega 2 bytes de cabeçalho FU + até 62 de corpo.
        val p = packetizer(maxPayload = 64)
        val big = ByteArray(1 + 130) { i -> if (i == 0) 0x41 else (0xA1 + (i % 50)).toByte() }
        p.sendEncodedFrame(startCode4 + big, 0)
        // 130 bytes de corpo / 62 = 3 fragmentos (62 + 62 + 6)
        assertEquals(3, packets.size)
        assertEquals(listOf(64 + 12, 64 + 12, 6 + 2 + 12), packets.map { it.size })
        assertEquals(0x80 or 1, payload(packets[0])[1].toInt() and 0xFF)
        assertEquals(1, payload(packets[1])[1].toInt() and 0xFF)
        assertEquals(0x40 or 1, payload(packets[2])[1].toInt() and 0xFF)
        assertEquals(listOf(false, false, true), packets.map { marker(it) })
    }

    @Test
    fun payloadNeverExceeds1200Bytes() {
        packetizer().sendEncodedFrame(startCode4 + nal(0x67, 100) + startCode4 + nal(0x68, 20) + startCode4 + nal(0x65, 50_000), 0)
        assertTrue(packets.all { it.size - 12 <= 1200 })
        assertEquals(1200, H264RtpPacketizer.MAX_RTP_PAYLOAD)
    }

    @Test
    fun nalExactlyAtLimit_isSingleNal_andOneByteMoreIsFragmented() {
        packetizer().sendEncodedFrame(startCode4 + nal(0x41, H264RtpPacketizer.MAX_RTP_PAYLOAD), 0)
        assertEquals(1, packets.size)

        packets.clear()
        packetizer().sendEncodedFrame(startCode4 + nal(0x41, H264RtpPacketizer.MAX_RTP_PAYLOAD + 1), 0)
        assertEquals(2, packets.size)
    }

    @Test
    fun sequenceNumbers_incrementByOne_andWrapAt16Bits() {
        val p = packetizer(initialSeq = 0xFFFE)
        p.sendEncodedFrame(startCode4 + nal(0x41, 10), 0)
        p.sendEncodedFrame(startCode4 + nal(0x41, 10), 33_333)
        p.sendEncodedFrame(startCode4 + nal(0x41, 10), 66_666)

        assertEquals(listOf(0xFFFE, 0xFFFF, 0x0000), packets.map { seq(it) })
        assertEquals(1, p.nextSequenceNumber)
    }

    @Test
    fun timestamp_isPtsConvertedTo90kHz() {
        val p = packetizer()
        p.sendEncodedFrame(startCode4 + nal(0x41, 10), 1_000_000) // 1 s
        p.sendEncodedFrame(startCode4 + nal(0x41, 10), 33_333) // 33,333 ms
        p.sendEncodedFrame(startCode4 + nal(0x41, 10), 0)

        assertEquals(90_000L, timestamp(packets[0]))
        assertEquals(2_999L, timestamp(packets[1])) // 33_333 * 90000 / 1e6 = 2999,97 -> trunca
        assertEquals(0L, timestamp(packets[2]))
    }

    @Test
    fun sendAccessUnit_usesTheGivenRtpTimestampAndMasksTo32Bits() {
        val data = startCode4 + nal(0x41, 10)
        packetizer().sendAccessUnit(data, 0, data.size, 0x1_0000_0005L)
        assertEquals(5L, timestamp(packets[0]))
    }

    @Test
    fun sendAccessUnit_respectsOffsetAndLength() {
        val inner = startCode4 + nal(0x41, 10)
        val padded = ByteArray(7) { 9 } + inner + ByteArray(5) { 9 }
        packetizer().sendAccessUnit(padded, 7, inner.size, 0)
        assertEquals(1, packets.size)
        assertArrayEquals(nal(0x41, 10), payload(packets[0]))
    }

    @Test
    fun stats_countPacketsAndBytes() {
        val p = packetizer()
        p.sendEncodedFrame(startCode4 + nal(0x41, 100), 0)
        assertEquals(1L, p.packetsSent)
        assertEquals(112L, p.bytesSent) // 12 de header RTP + 100 de NAL
    }

    @Test
    fun emptyOrStartCodeOnlyInput_sendsNothing() {
        val p = packetizer()
        p.sendEncodedFrame(ByteArray(0), 0)
        p.sendEncodedFrame(startCode4, 0)
        assertTrue(packets.isEmpty())
    }

    @Test
    fun trailingZerosOfFourByteStartCodes_doNotLeakIntoTheNal() {
        // NAL, depois start code de 4 bytes: o 00 final da NAL anterior pertence ao start code
        val a = nal(0x41, 30)
        val b = nal(0x41, 30)
        packetizer().sendEncodedFrame(startCode4 + a + startCode4 + b, 0)
        assertEquals(2, packets.size)
        assertArrayEquals(a, payload(packets[0]))
        assertArrayEquals(b, payload(packets[1]))
    }
}
