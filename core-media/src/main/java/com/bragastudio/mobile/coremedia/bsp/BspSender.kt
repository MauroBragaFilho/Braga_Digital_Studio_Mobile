package com.bragastudio.mobile.coremedia.bsp

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

private const val TAG = "BspSender"

/** Destino de mídia de uma sessão: endereço UDP do receptor e, com AEAD, um cifrador por fluxo. */
class BspMediaTarget(
    val sessionId: Int,
    internal val address: InetSocketAddress,
    internal val videoSealer: BspStreamSealer?,
    internal val audioSealer: BspStreamSealer?,
) {
    /** O receptor pediu STOP (ou o Monitor fechou): não envia mídia, mas mantém a sessão. */
    @Volatile var paused = false
}

/**
 * Thread única de saída de mídia do BSP (11): tira da fila os pacotes de vídeo e áudio quando o
 * horário de pacing vence, cifra por sessão (AEAD, se houver) e envia por UDP. Dois sockets, um por
 * tipo, para o DSCP diferir: vídeo AF41 e áudio EF (5.4); o efeito depende do roteador/WMM (não
 * garantido). Sem alocação por pacote: buffers e [DatagramPacket] reaproveitados.
 */
class BspSender(
    private val videoQueue: PacedPacketQueue,
    private val audioQueue: PacedPacketQueue,
    private val slotSize: Int,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    companion object {
        const val DSCP_AF41_TOS = 0x88 // DSCP 34 << 2
        const val DSCP_EF_TOS = 0xB8 // DSCP 46 << 2
        private const val MIN_WAIT_NS = 50_000L
    }

    private val targets = CopyOnWriteArrayList<BspMediaTarget>()
    private val bytes = AtomicLong(0)
    private val packets = AtomicLong(0)
    private val errors = AtomicLong(0)

    @Volatile private var running = false

    @Volatile private var thread: Thread? = null
    private var videoSocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null

    /** Bytes de datagramas efetivamente enviados (todas as sessões). */
    val bytesSent: Long get() = bytes.get()
    val packetsSent: Long get() = packets.get()
    val sendErrors: Long get() = errors.get()

    val hasTargets: Boolean get() = targets.any { !it.paused }

    @Synchronized
    fun start() {
        if (running) return
        videoSocket = DatagramSocket().also { setTos(it, DSCP_AF41_TOS) }
        audioSocket = DatagramSocket().also { setTos(it, DSCP_EF_TOS) }
        running = true
        thread = Thread({ loop() }, "bsp-sender").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY + 1
            start()
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        val t = thread
        LockSupport.unpark(t)
        t?.join(1000)
        thread = null
        videoSocket?.close()
        audioSocket?.close()
        videoSocket = null
        audioSocket = null
        targets.clear()
        videoQueue.clear()
        audioQueue.clear()
    }

    fun addTarget(target: BspMediaTarget) {
        targets.removeAll { it.sessionId == target.sessionId }
        targets.add(target)
    }

    fun removeTarget(sessionId: Int) {
        targets.removeAll { it.sessionId == sessionId }
    }

    fun target(sessionId: Int): BspMediaTarget? = targets.firstOrNull { it.sessionId == sessionId }

    /** Acorda a thread de envio (chamado pelo produtor depois de `endFrame`). */
    fun wake() = LockSupport.unpark(thread)

    private fun setTos(socket: DatagramSocket, tos: Int) {
        try {
            socket.trafficClass = tos
        } catch (e: Exception) {
            Log.w(TAG, "DSCP não aplicado: ${e.javaClass.simpleName}")
        }
    }

    private fun loop() {
        val plain = ByteArray(slotSize)
        val out = ByteArray(slotSize + BspCrypto.TAG_LEN)
        val datagram = DatagramPacket(out, 0, 0)
        while (running) {
            val now = nanoClock()
            // áudio primeiro: pacotes pequenos e sensíveis a atraso
            var length = audioQueue.pollDue(now, plain)
            var audio = true
            if (length < 0) {
                length = videoQueue.pollDue(now, plain)
                audio = false
            }
            if (length > 0) {
                transmit(plain, length, audio, out, datagram)
                continue
            }
            val next = minOf(videoQueue.nextDueNs(), audioQueue.nextDueNs())
            if (next == Long.MAX_VALUE) {
                LockSupport.park(this)
            } else {
                LockSupport.parkNanos(this, maxOf(next - now, MIN_WAIT_NS))
            }
        }
    }

    private fun transmit(plain: ByteArray, length: Int, audio: Boolean, out: ByteArray, datagram: DatagramPacket) {
        val socket = (if (audio) audioSocket else videoSocket) ?: return
        for (target in targets) {
            if (target.paused) continue
            try {
                val sealer = if (audio) target.audioSealer else target.videoSealer
                val n = if (sealer != null) {
                    sealer.seal(plain, length, out)
                } else {
                    System.arraycopy(plain, 0, out, 0, length)
                    length
                }
                datagram.setData(out, 0, n)
                datagram.socketAddress = target.address
                socket.send(datagram)
                bytes.addAndGet(n.toLong())
                packets.incrementAndGet()
            } catch (e: IOException) {
                // rede indisponível/destino inalcançável: perda esperada em UDP; só conta
                errors.incrementAndGet()
            } catch (e: RuntimeException) {
                // falha de cifra/tamanho: não derruba a thread de envio
                errors.incrementAndGet()
            } catch (e: java.security.GeneralSecurityException) {
                errors.incrementAndGet()
            }
        }
    }
}
