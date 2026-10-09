package com.bragastudio.mobile.coremedia.bsp

import android.util.Log
import java.io.IOException
import java.net.BindException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

private const val TAG = "BspFeedback"

/**
 * Quem reage ao retorno do receptor. Fase 1: [onPli] pede IDR e [onReport] só alimenta as estatísticas.
 * [onNack] e [onSrEcho] são os PONTOS DE EXTENSÃO da Fase 2 (retransmissão, RTT fino): hoje não fazem nada.
 */
interface BspFeedbackListener {
    fun onReport(from: InetAddress, report: BspFeedbackMessage.Report)
    fun onPli(from: InetAddress, pli: BspFeedbackMessage.Pli)

    /** FASE 2 (NACK/retransmissão). Não implementado. */
    fun onNack(from: InetAddress, nack: BspFeedbackMessage.Nack) {}

    /** FASE 2 (eco do SR). Não implementado. */
    fun onSrEcho(from: InetAddress) {}
}

/**
 * Escuta o retorno binário UDP na `feedbackPort` (6.1). Só aceita datagramas de endereços de
 * sessões conhecidas ([isKnownSender]) e que passem pelo parser com limites ([BspFeedbackParser]);
 * o resto é descartado em silêncio. Uma thread; buffer fixo; sem log por pacote.
 *
 * Segurança (limite honesto da Fase 1): o retorno não tem autenticação por pacote; um host da LAN que
 * falsifique o IP do receptor poderia pedir IDR (limitado a 1 por 500 ms pelo chamador) ou poluir as
 * estatísticas, mas não derruba a sessão nem lê mídia.
 */
class BspFeedbackReceiver(
    private val listener: BspFeedbackListener,
    private val isKnownSender: (InetAddress) -> Boolean,
) {
    companion object {
        const val DEFAULT_PORT = 7071
        private const val MAX_DATAGRAM = 128
    }

    @Volatile private var socket: DatagramSocket? = null

    @Volatile private var thread: Thread? = null

    @Volatile var port = 0
        private set

    /** Abre a porta (cai para uma livre se [preferredPort] estiver ocupada) e devolve a escolhida. */
    @Synchronized
    fun start(preferredPort: Int = DEFAULT_PORT): Int {
        if (socket != null) return port
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(preferredPort))
            }
        } catch (e: BindException) {
            DatagramSocket(null).apply { bind(InetSocketAddress(0)) }
        }
        socket = s
        port = s.localPort
        thread = Thread({ loop(s) }, "bsp-feedback").apply {
            isDaemon = true
            start()
        }
        return port
    }

    @Synchronized
    fun stop() {
        val s = socket ?: return
        socket = null
        s.close()
        thread?.join(1000)
        thread = null
        port = 0
    }

    private fun loop(s: DatagramSocket) {
        val buf = ByteArray(MAX_DATAGRAM)
        val packet = DatagramPacket(buf, buf.size)
        while (!s.isClosed) {
            try {
                packet.length = buf.size
                s.receive(packet)
            } catch (e: IOException) {
                if (!s.isClosed) Log.w(TAG, "receive falhou: ${e.javaClass.simpleName}")
                break
            }
            val from = packet.address ?: continue
            if (!isKnownSender(from)) continue
            when (val msg = BspFeedbackParser.parse(buf, packet.length)) {
                is BspFeedbackMessage.Report -> listener.onReport(from, msg)
                is BspFeedbackMessage.Pli -> listener.onPli(from, msg)
                is BspFeedbackMessage.Nack -> listener.onNack(from, msg)
                BspFeedbackMessage.SrEcho -> listener.onSrEcho(from)
                null -> Unit
            }
        }
    }
}
