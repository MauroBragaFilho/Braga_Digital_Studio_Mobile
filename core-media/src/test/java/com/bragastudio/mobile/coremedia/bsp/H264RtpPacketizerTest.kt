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
        burst: Int = 0,
        pauser: (Long) -> Unit = {},
    ) = H264RtpPacketizer(
        sink = RtpPacketSink { packets += it.copyOf() },
        payloadType = 96,
        ssrc = ssrc,
        initialSequenceNumber = initialSeq,
        pacingBurstPackets = burst,
        pacingPauseNanos = 1,
        pauser = pauser,
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

    @Test
    fun singleNal_isSentAsOnePacketWithMarkerAndRtpHeader() {
        val nal = nal(0x65, 100)
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
    fun multipleNals_markerOnlyOnLastPacket_sameTimestamp() {
        val sps = nal(0x67, 20)
        val pps = nal(0x68, 8)
        val idr = nal(0x65, 300)
        packetizer().sendEncodedFrame(startCode4 + sps + startCode4 + pps + startCode3 + idr, 33_333)

        assertEquals(3, packets.size)
        assertArrayEquals(sps, payload(packets[0]))
        assertArrayEquals(pps, payload(packets[1]))
        assertArrayEquals(idr, payload(packets[2]))
        assertEquals(listOf(false, false, true), packets.map { marker(it) })
        assertEquals(1, packets.map { timestamp(it) }.distinct().size)
    }

    @Test
    fun largeNal_isFragmentedInFuA_withStartEndBitsAndMarkerOnlyOnLast() {
        val big = nal(0x65, 4000) // header 0x65: nri=3, type=5
        packetizer().sendEncodedFrame(startCode4 + big, 0)

        assertTrue(packets.size > 2)
        val maxPayload = H264RtpPacketizer.MAX_RTP_PAYLOAD
        packets.forEach { assertTrue(it.size - 12 <= maxPayload) }

        val first = payload(packets.first())
        val last = payload(packets.last())
        // FU indicator: NRI do NAL original (0x60) | tipo 28
        assertEquals((0x65 and 0x60) or 28, first[0].toInt() and 0xFF)
        assertEquals(0x80 or 5, first[1].toInt() and 0xFF)           // S=1, tipo original 5
        assertEquals(0x40 or 5, last[1].toInt() and 0xFF)            // E=1
        packets.subList(1, packets.size - 1).forEach {
            assertEquals(5, payload(it)[1].toInt() and 0xFF)         // meio: sem S nem E
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
    fun nalExactlyAtMtuLimit_isSingleNal_andOneByteMoreIsFragmented() {
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
    }

    @Test
    fun timestamp_isPtsConvertedTo90kHz() {
        val p = packetizer()
        p.sendEncodedFrame(startCode4 + nal(0x41, 10), 1_000_000)  // 1 s
        p.sendEncodedFrame(startCode4 + nal(0x41, 10), 33_333)     // 33,333 ms
        p.sendEncodedFrame(startCode4 + nal(0x41, 10), 0)

        assertEquals(90_000L, timestamp(packets[0]))
        assertEquals(2_999L, timestamp(packets[1])) // 33_333 * 90000 / 1e6 = 2999,97 -> trunca
        assertEquals(0L, timestamp(packets[2]))
    }

    @Test
    fun timestamp_wrapsAt32Bits() {
        // 2^32 ticks de 90 kHz = 47721858,84 s; PTS logo acima disso deve voltar a valores pequenos.
        val ptsUs = 47_721_859L * 1_000_000L
        packetizer().sendEncodedFrame(startCode4 + nal(0x41, 10), ptsUs)
        val expected = (ptsUs * 90_000L / 1_000_000L) and 0xFFFFFFFFL
        assertEquals(expected, timestamp(packets[0]))
        assertTrue(expected < 90_000L * 2)
    }

    @Test
    fun stats_countPacketsAndBytes() {
        val p = packetizer()
        p.sendEncodedFrame(startCode4 + nal(0x41, 100), 0)
        val (count, bytes) = p.stats
        assertEquals(1L, count)
        assertEquals(112L, bytes) // 12 de header RTP + 100 de NAL
    }

    @Test
    fun pacing_pausesAfterEveryBurstOfPacketsOfALargeFrame() {
        var pauses = 0
        val p = packetizer(burst = 5, pauser = { pauses++ })
        p.sendEncodedFrame(startCode4 + nal(0x65, 20_000), 0)
        assertEquals(packets.size / 5, pauses)
        assertTrue("o frame grande precisa de várias rajadas", pauses >= 2)
    }

    @Test
    fun pacing_doesNotPauseForSmallFrames() {
        var pauses = 0
        val p = packetizer(burst = 12, pauser = { pauses++ })
        p.sendEncodedFrame(startCode4 + nal(0x41, 500), 0)
        assertEquals(0, pauses)
        assertFalse(packets.isEmpty())
    }
}
