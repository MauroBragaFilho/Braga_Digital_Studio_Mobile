package com.bragastudio.mobile.coremedia.bsp

import com.bragastudio.mobile.coremedia.domain.BspManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AacRtpPacketizerTest {
    private val packets = mutableListOf<ByteArray>()

    private fun packetizer(seq: Int = 500, ssrc: Int = 0x01020304) = AacRtpPacketizer(RtpPacketSink { b, n -> packets += b.copyOf(n) }, payloadType = 97, ssrc = ssrc, initialSequenceNumber = seq)

    private fun ts(p: ByteArray): Long = ((p[4].toLong() and 0xFF) shl 24) or ((p[5].toLong() and 0xFF) shl 16) or ((p[6].toLong() and 0xFF) shl 8) or (p[7].toLong() and 0xFF)

    @Test
    fun audioSpecificConfigFor48kStereoIs1190() {
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), AacConfig.audioSpecificConfig(48_000, 2))
        assertArrayEquals(byteArrayOf(0x12, 0x10), AacConfig.audioSpecificConfig(44_100, 2))
        assertArrayEquals(byteArrayOf(0x11, 0x88.toByte()), AacConfig.audioSpecificConfig(48_000, 1))
        assertNull(AacConfig.audioSpecificConfig(12_345, 2))
        assertNull(AacConfig.audioSpecificConfig(48_000, 0))
    }

    @Test
    fun rfc3640AacHbrPayloadLayout() {
        val au = ByteArray(300) { (it + 1).toByte() }
        packetizer().sendAccessUnit(au, 0, au.size, 96_000)
        assertEquals(1, packets.size)
        val p = packets[0]
        assertEquals(0x80, p[0].toInt() and 0xFF)
        assertEquals("marker + PT 97", 0x80 or 97, p[1].toInt() and 0xFF)
        assertEquals(500, ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF))
        assertEquals(96_000L, ts(p))
        // AU-headers-length = 16 bits
        assertEquals(0x00, p[12].toInt() and 0xFF)
        assertEquals(0x10, p[13].toInt() and 0xFF)
        // AU-header: size (13 bits) = 300, index (3 bits) = 0  -> 300 << 3 = 2400 = 0x0960
        assertEquals(0x09, p[14].toInt() and 0xFF)
        assertEquals(0x60, p[15].toInt() and 0xFF)
        assertEquals(12 + 4 + 300, p.size)
        assertArrayEquals(au, p.copyOfRange(16, p.size))
    }

    @Test
    fun auHeaderEncodesTheSizeInThe13BitField() {
        // tamanho 8191 (máximo) = 0x1FFF -> header = 0x1FFF << 3 = 0xFFF8
        val au = ByteArray(8191) { 1 }
        packetizer().sendAccessUnit(au, 0, au.size, 0)
        assertEquals(0xFF, packets[0][14].toInt() and 0xFF)
        assertEquals(0xF8, packets[0][15].toInt() and 0xFF)
        // tamanho 1 -> 1 << 3 = 8
        packets.clear()
        packetizer().sendAccessUnit(byteArrayOf(7), 0, 1, 0)
        assertEquals(0x00, packets[0][14].toInt() and 0xFF)
        assertEquals(0x08, packets[0][15].toInt() and 0xFF)
    }

    @Test
    fun oversizedOrEmptyAccessUnitsAreDropped() {
        val p = packetizer()
        p.sendAccessUnit(ByteArray(8192), 0, 8192, 0)
        p.sendAccessUnit(ByteArray(10), 0, 0, 0)
        assertTrue(packets.isEmpty())
        assertEquals(0L, p.packetsSent)
    }

    @Test
    fun sequenceIncrementsAndWrapsAndOffsetIsRespected() {
        val p = packetizer(seq = 0xFFFF)
        val buf = ByteArray(10) { it.toByte() }
        p.sendAccessUnit(buf, 2, 5, 1)
        p.sendAccessUnit(buf, 2, 5, 2)
        assertEquals(0xFFFF, ((packets[0][2].toInt() and 0xFF) shl 8) or (packets[0][3].toInt() and 0xFF))
        assertEquals(0, ((packets[1][2].toInt() and 0xFF) shl 8) or (packets[1][3].toInt() and 0xFF))
        assertArrayEquals(byteArrayOf(2, 3, 4, 5, 6), packets[0].copyOfRange(16, packets[0].size))
        assertEquals(2L, p.packetsSent)
        assertEquals(2L * (12 + 4 + 5), p.bytesSent)
    }

    @Test
    fun timestampIsMaskedTo32Bits() {
        packetizer().sendAccessUnit(byteArrayOf(1), 0, 1, 0x1_0000_0010L)
        assertEquals(0x10L, ts(packets[0]))
    }
}

class RtpClockTest {
    @Test
    fun rtpAdvancesAtTheStreamClockRate() {
        val video = RtpClock(90_000, rtpBase = 1_000, originNs = 5_000_000_000L)
        assertEquals(1_000L, video.rtpAt(5_000_000_000L))
        assertEquals(1_000L + 90_000L, video.rtpAt(6_000_000_000L))
        assertEquals(1_000L + 45_000L, video.rtpAt(5_500_000_000L))
        val audio = RtpClock(48_000, rtpBase = 0, originNs = 5_000_000_000L)
        assertEquals(48_000L, audio.rtpAt(6_000_000_000L))
        assertEquals(960L, audio.rtpAt(5_020_000_000L))
    }

    @Test
    fun videoAndAudioShareTheSameCaptureInstant() {
        val origin = 123_456_789_000L
        val video = RtpClock(90_000, 0, origin)
        val audio = RtpClock(48_000, 0, origin)
        val capture = origin + 2_500_000_000L // 2,5 s
        assertEquals(225_000L, video.rtpAt(capture))
        assertEquals(120_000L, audio.rtpAt(capture))
        // 225000/90000 == 120000/48000 == 2,5 s
        assertEquals(video.rtpAt(capture) / 90_000.0, audio.rtpAt(capture) / 48_000.0, 1e-9)
    }

    @Test
    fun wrapsAt32BitsAndHandlesTimesBeforeTheOrigin() {
        val c = RtpClock(90_000, rtpBase = 0xFFFFFFF0L, originNs = 0)
        assertEquals(0xFFFFFFF0L, c.rtpAt(0))
        assertEquals((0xFFFFFFF0L + 90_000L) and 0xFFFFFFFFL, c.rtpAt(1_000_000_000L))
        assertEquals(0xFFFFFFF0L - 90_000L, c.rtpAt(-1_000_000_000L))
    }

    @Test
    fun longSessionsDoNotOverflow() {
        // 3 dias a 90 kHz: delta em ns * taxa estouraria Long se multiplicasse direto
        val c = RtpClock(90_000, 0, 0)
        val threeDaysNs = 3L * 24 * 3600 * 1_000_000_000L
        assertEquals((3L * 24 * 3600 * 90_000L) and 0xFFFFFFFFL, c.rtpAt(threeDaysNs))
    }
}

class PtsDomainTest {
    @Test
    fun ptsAlreadyOnTheMonotonicClockIsKept() {
        val d = PtsDomain({ 50_000_000_000L }, { 80_000_000_000L })
        assertEquals(49_990_000_000L, d.toMonotonicNs(49_990_000L)) // 49,99 s em µs
        assertEquals(PtsDomain.Mode.MONOTONIC, d.mode)
        assertEquals("o critério não muda depois", 50_010_000_000L, d.toMonotonicNs(50_010_000L))
    }

    @Test
    fun ptsOnTheBoottimeClockIsShiftedToMonotonic() {
        // boottime está 30 s à frente do monotônico (aparelho dormiu)
        val d = PtsDomain({ 50_000_000_000L }, { 80_000_000_000L })
        val mono = d.toMonotonicNs(79_990_000L) // quadro de 10 ms atrás no relógio boottime
        assertEquals(PtsDomain.Mode.BOOTTIME, d.mode)
        assertEquals(49_990_000_000L, mono)
    }

    @Test
    fun ptsOnNeitherClockFallsBackToFirstFrameOffset() {
        val d = PtsDomain({ 50_000_000_000L }, { 80_000_000_000L })
        val first = d.toMonotonicNs(1_000_000_000L * 1_000L / 1_000L) // PTS ~1000 s: longe dos dois
        assertEquals(PtsDomain.Mode.FIRST_FRAME, d.mode)
        assertEquals("o primeiro quadro cai no instante de chegada", 50_000_000_000L, first)
        assertEquals(50_033_000_000L, d.toMonotonicNs(1_000_000_000L + 33_000L))
    }
}

class AudioTimestampSmootherTest {
    @Test
    fun advancesExactly1024PerFrameWhenPtsIsClean() {
        val s = AudioTimestampSmoother()
        var pts = 10_000L
        val out = (0 until 5).map { s.next(pts).also { pts += 1024 } }
        assertEquals(listOf(10_000L, 11_024L, 12_048L, 13_072L, 14_096L), out)
    }

    @Test
    fun smoothsJitterInsteadOfCopyingIt() {
        val s = AudioTimestampSmoother()
        s.next(0)
        // PTS oscilando +-100 ticks em torno do ideal: a saída varia bem menos
        val jitter = longArrayOf(100, -100, 80, -80, 100, -100)
        var ideal = 1024L
        val outs = jitter.map { j -> s.next(ideal + j).also { ideal += 1024 } }
        val deltas = outs.zipWithNext { a, b -> b - a }
        assertTrue("passos próximos de 1024: $deltas", deltas.all { it in 1000..1050 })
    }

    @Test
    fun followsSlowDriftAndResyncsOnBigDiscontinuity() {
        val s = AudioTimestampSmoother()
        s.next(0)
        var pts = 1024L
        var last = 0L
        // deriva de +2 ticks por quadro: em 200 quadros a saída acompanha
        repeat(200) {
            last = s.next(pts)
            pts += 1026
        }
        assertTrue("acompanhou a deriva (|erro|=${pts - 1026 - last})", Math.abs((pts - 1026) - last) < 64)
        // buraco de 100 ms (4800 ticks): ressincroniza de uma vez
        val resync = s.next(pts + 4800)
        assertEquals(pts + 4800, resync)
    }

    @Test
    fun handlesThe32BitWrap() {
        val s = AudioTimestampSmoother()
        val start = 0xFFFFFC00L // 1024 antes do wrap
        assertEquals(start, s.next(start))
        assertEquals(0L, s.next(0))
        assertEquals(1024L, s.next(1024))
    }
}

class AudioPtsTest {
    @Test
    fun blockStartIsNowMinusDurationButNeverBeforeThePreviousEnd() {
        assertEquals(980_000L, AudioPts.blockStartUs(1_000_000, 20_000, lastEndUs = 0))
        assertEquals(985_000L, AudioPts.blockStartUs(1_000_000, 20_000, lastEndUs = 985_000))
    }
}

class BspBitrateTest {
    @Test
    fun bitrateScalesWithAreaAndIsClamped() {
        assertEquals(10_000_000, BspManager.bitrateBpsFor(1920, 1080))
        assertEquals(10_000_000, BspManager.bitrateBpsFor(1080, 1920))
        assertEquals(4_444_444, BspManager.bitrateBpsFor(1280, 720))
        assertEquals(17_777_777, BspManager.bitrateBpsFor(2560, 1440))
        assertEquals(25_000_000, BspManager.bitrateBpsFor(3840, 2160))
        assertEquals(4_000_000, BspManager.bitrateBpsFor(640, 360))
    }
}

class AnnexBTest {
    private val sc4 = byteArrayOf(0, 0, 0, 1)
    private val sc3 = byteArrayOf(0, 0, 1)

    @Test
    fun splitsMixedStartCodesAndTrimsTrailingZeros() {
        val a = byteArrayOf(0x67, 1, 2)
        val b = byteArrayOf(0x68, 3)
        val c = byteArrayOf(0x65, 4, 5, 6)
        val parts = AnnexB.split(sc4 + a + sc4 + b + sc3 + c)
        assertEquals(3, parts.size)
        assertArrayEquals(a, parts[0])
        assertArrayEquals(b, parts[1])
        assertArrayEquals(c, parts[2])
    }

    @Test
    fun noStartCodeIsOneNalAndEmptyIsNothing() {
        assertEquals(1, AnnexB.split(byteArrayOf(0x41, 9, 9)).size)
        assertTrue(AnnexB.split(ByteArray(0)).isEmpty())
        assertTrue(AnnexB.split(sc4).isEmpty())
    }

    @Test
    fun extractsSpsAndPps() {
        val sps = byteArrayOf(0x67, 1, 2, 3)
        val pps = byteArrayOf(0x68, 9)
        val (s, p) = AnnexB.parameterSets(sc4 + sps + sc4 + pps + sc4 + byteArrayOf(0x65, 1))
        assertArrayEquals(sps, s)
        assertArrayEquals(pps, p)
        val (s2, p2) = AnnexB.parameterSets(sc4 + byteArrayOf(0x41, 1))
        assertNull(s2)
        assertNull(p2)
    }
}
