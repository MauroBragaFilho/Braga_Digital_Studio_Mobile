package com.bragastudio.mobile.coremedia.bsp

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Thread de envio com UDP real em loopback: pacing, AEAD por sessão e pausa. */
class BspSenderTest {
    private val frameNs = 33_333_333L
    private lateinit var videoQueue: PacedPacketQueue
    private lateinit var audioQueue: PacedPacketQueue
    private lateinit var sender: BspSender
    private lateinit var receiver: DatagramSocket

    @Before
    fun setUp() {
        videoQueue = PacedPacketQueue(256, BspMediaPipeline.SLOT_SIZE, frameNs * 2)
        audioQueue = PacedPacketQueue(64, BspMediaPipeline.SLOT_SIZE, Long.MAX_VALUE / 4)
        sender = BspSender(videoQueue, audioQueue, BspMediaPipeline.SLOT_SIZE)
        receiver = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).also { it.soTimeout = 2000 }
        sender.start()
    }

    @After
    fun tearDown() {
        sender.stop()
        receiver.close()
    }

    private fun target(id: Int = 1, video: BspStreamSealer? = null, audio: BspStreamSealer? = null) = BspMediaTarget(id, InetSocketAddress("127.0.0.1", receiver.localPort), video, audio)

    private fun rtp(seq: Int, ssrc: Int, size: Int = 100): ByteArray {
        val p = ByteArray(size)
        writeRtpHeader(p, true, 96, seq, 1000L + seq, ssrc)
        for (i in 12 until size) p[i] = (seq + i).toByte()
        return p
    }

    private fun enqueueFrame(vararg packets: ByteArray, key: Boolean = false) {
        videoQueue.beginFrame(key)
        packets.forEach { videoQueue.send(it, it.size) }
        videoQueue.endFrame(System.nanoTime(), frameNs)
        sender.wake()
    }

    private fun receiveOne(): ByteArray {
        val buf = ByteArray(2000)
        val dp = DatagramPacket(buf, buf.size)
        receiver.receive(dp)
        return buf.copyOf(dp.length)
    }

    @Test
    fun plainPacketsReachTheReceiverInOrderAndSpreadOverTime() {
        sender.addTarget(target())
        val packets = (1..10).map { rtp(it, 0x11) }
        val started = System.nanoTime()
        enqueueFrame(*packets.toTypedArray())
        val received = (1..10).map { receiveOne() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        packets.forEachIndexed { i, p -> assertArrayEquals(p, received[i]) }
        // o espaçamento exato (1/3 do intervalo) é verificado em PacedPacketQueueTest; aqui só a entrega, com folga p/ máquina carregada
        assertTrue("entrega de um quadro de 10 pacotes: ${elapsedMs}ms", elapsedMs < 2000)
        assertEquals(10L, sender.packetsSent)
        assertEquals(1000L, sender.bytesSent)
    }

    @Test
    fun sealedPacketsAreDecryptableOnlyWithTheSessionKey() {
        val kt = BspCrypto.sha256("t".toByteArray())
        val keys = BspCrypto.deriveSessionKeys(kt, ByteArray(16) { 1 }, ByteArray(16) { 2 }, 99)
        val ssrc = 0x2222
        sender.addTarget(target(video = BspStreamSealer(keys.key, keys.nonceSalt, ssrc)))
        val packet = rtp(5, ssrc, 200)
        enqueueFrame(packet)
        val datagram = receiveOne()
        assertEquals(packet.size + 16, datagram.size)
        assertFalse("payload não vai em claro", packet.copyOfRange(12, 200).contentEquals(datagram.copyOfRange(12, 200)))
        val out = ByteArray(datagram.size)
        val n = BspStreamOpener(keys.key, keys.nonceSalt, ssrc).open(datagram, datagram.size, out)
        assertEquals(packet.size, n)
        assertArrayEquals(packet, out.copyOf(n))
        assertEquals((packet.size + 16).toLong(), sender.bytesSent)
    }

    @Test
    fun eachTargetGetsItsOwnCiphertextWithItsOwnKey() {
        val receiver2 = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).also { it.soTimeout = 2000 }
        try {
            val ssrc = 0x33
            val kA = BspCrypto.deriveSessionKeys(ByteArray(32) { 1 }, ByteArray(16), ByteArray(16), 1)
            val kB = BspCrypto.deriveSessionKeys(ByteArray(32) { 2 }, ByteArray(16), ByteArray(16), 2)
            sender.addTarget(target(1, BspStreamSealer(kA.key, kA.nonceSalt, ssrc)))
            sender.addTarget(BspMediaTarget(2, InetSocketAddress("127.0.0.1", receiver2.localPort), BspStreamSealer(kB.key, kB.nonceSalt, ssrc), null))
            val packet = rtp(1, ssrc, 120)
            enqueueFrame(packet)
            val a = receiveOne()
            val buf = ByteArray(2000)
            val dp = DatagramPacket(buf, buf.size)
            receiver2.receive(dp)
            val b = buf.copyOf(dp.length)
            assertFalse(a.contentEquals(b))
            val out = ByteArray(200)
            assertEquals(packet.size, BspStreamOpener(kA.key, kA.nonceSalt, ssrc).open(a, a.size, out))
            assertEquals(packet.size, BspStreamOpener(kB.key, kB.nonceSalt, ssrc).open(b, b.size, out))
            assertEquals(-1, BspStreamOpener(kA.key, kA.nonceSalt, ssrc).open(b, b.size, out))
        } finally {
            receiver2.close()
        }
    }

    @Test
    fun pausedTargetsReceiveNothingAndResumeWorks() {
        val t = target()
        t.paused = true
        sender.addTarget(t)
        assertFalse(sender.hasTargets)
        enqueueFrame(rtp(1, 1))
        receiver.soTimeout = 400
        try {
            receiveOne()
            throw AssertionError("alvo pausado não deve receber")
        } catch (_: SocketTimeoutException) {
            // esperado
        }
        t.paused = false
        assertTrue(sender.hasTargets)
        receiver.soTimeout = 2000
        enqueueFrame(rtp(2, 1))
        val d = receiveOne()
        assertEquals(2, ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF))
    }

    @Test
    fun removedTargetStopsReceiving() {
        sender.addTarget(target(7))
        enqueueFrame(rtp(1, 1))
        receiveOne()
        sender.removeTarget(7)
        assertFalse(sender.hasTargets)
        enqueueFrame(rtp(2, 1))
        receiver.soTimeout = 400
        try {
            receiveOne()
            throw AssertionError("alvo removido não deve receber")
        } catch (_: SocketTimeoutException) {
            // esperado
        }
    }

    @Test
    fun audioPacketsAreSentImmediatelyEvenWithVideoPending() {
        sender.addTarget(target())
        // vídeo grande em pacing + um pacote de áudio logo depois
        enqueueFrame(*(1..30).map { rtp(it, 0x11) }.toTypedArray())
        audioQueue.beginFrame(true)
        val audio = rtp(77, 0x99, 60)
        audioQueue.send(audio, audio.size)
        audioQueue.endFrame(System.nanoTime(), 0)
        sender.wake()
        val first = (1..8).map { receiveOne() }
        assertTrue("o áudio não espera o quadro de vídeo inteiro", first.any { it.size == 60 })
    }
}
