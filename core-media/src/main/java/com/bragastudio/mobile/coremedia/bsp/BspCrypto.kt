package com.bragastudio.mobile.coremedia.bsp

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Primitivas criptográficas do BSP v2 (`.docs/BSP_ESPECIFICACAO.md`, 4.1 e 5.3). Só javax.crypto:
 * HMAC-SHA256, HKDF-SHA256 (RFC 5869, implementado aqui) e AES-128-GCM. Sem dependência de Android,
 * para os vetores conhecidos rodarem como teste de unidade em JVM.
 *
 * Nada aqui registra em log: tokens, `Kt` e chaves nunca saem destas funções.
 */
object BspCrypto {
    private const val HMAC_ALGORITHM = "HmacSHA256"
    const val HASH_LEN = 32

    /** Tamanho da chave AES-128 e do sal de nonce (4 bytes) derivados juntos do HKDF. */
    const val KEY_LEN = 16
    const val NONCE_SALT_LEN = 4
    const val TAG_LEN = 16

    private val HELLO_LABEL = "BSP-HELLO".toByteArray(Charsets.US_ASCII)
    private val WELCOME_LABEL = "BSP-WELCOME".toByteArray(Charsets.US_ASCII)
    private val KEY_INFO = "BSP v2 key".toByteArray(Charsets.US_ASCII)

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** HMAC-SHA256 sobre a concatenação de [parts] (sem copiar para um buffer único). */
    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        require(key.isNotEmpty()) { "chave HMAC vazia" }
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    /** Comparação em tempo constante (duração independe de onde os arrays diferem). */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /**
     * AES-GCM de referência (um pacote, sem estado): devolve `texto cifrado || tag(16)`. Usado nos
     * testes com vetores conhecidos; o caminho de mídia usa [BspStreamSealer].
     */
    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    // ---- HKDF (RFC 5869) ---------------------------------------------------------------

    /** HKDF-Extract: `PRK = HMAC(salt, IKM)`. Sal vazio vale `HashLen` zeros (RFC 5869, 2.2). */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN) else salt
        return hmacSha256(effectiveSalt, ikm)
    }

    /** HKDF-Expand: `OKM = T(1) | T(2) | ...` com `T(i) = HMAC(PRK, T(i-1) | info | i)`; [length] até 255*32. */
    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * HASH_LEN)) { "tamanho de saída HKDF inválido: $length" }
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var produced = 0
        var counter = 1
        while (produced < length) {
            previous = hmacSha256(prk, previous, info, byteArrayOf(counter.toByte()))
            val n = minOf(HASH_LEN, length - produced)
            System.arraycopy(previous, 0, out, produced, n)
            produced += n
            counter++
        }
        return out
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray = hkdfExpand(hkdfExtract(salt, ikm), info, length)

    // ---- Autenticação do controle ------------------------------------------------------

    /** `proof = HMAC-SHA256(Kt, nonceR || "BSP-HELLO")` (4.1). [nonceR] são os 16 bytes decodificados. */
    fun helloProof(kt: ByteArray, nonceR: ByteArray): ByteArray = hmacSha256(kt, nonceR, HELLO_LABEL)

    /**
     * Prova da FONTE no WELCOME (extensão opcional, ver 12): `HMAC-SHA256(Kt, nonceR || nonceS || "BSP-WELCOME")`.
     * Deixa o receptor conferir que quem respondeu conhece `Kt` (autenticação mútua).
     */
    fun welcomeProof(kt: ByteArray, nonceR: ByteArray, nonceS: ByteArray): ByteArray = hmacSha256(kt, nonceR, nonceS, WELCOME_LABEL)

    /** Chave de mídia da sessão: AES-128 mais o sal de 4 bytes do nonce GCM. */
    class SessionKeys(val key: ByteArray, val nonceSalt: ByteArray) {
        fun wipe() {
            key.fill(0)
            nonceSalt.fill(0)
        }
    }

    /**
     * `K = HKDF-SHA256(ikm = Kt, salt = nonceR || nonceS, info = "BSP v2 key" || sessionId)` (5.3). O
     * `sessionId` entra como 4 bytes big-endian. Saem 20 bytes: os 16 primeiros são `K` (idênticos ao
     * que se obteria pedindo só 16, pois o HKDF-Expand é um prefixo estável) e os 4 seguintes são o
     * `salt32` do nonce GCM.
     */
    fun deriveSessionKeys(kt: ByteArray, nonceR: ByteArray, nonceS: ByteArray, sessionId: Int): SessionKeys {
        val info = ByteArray(KEY_INFO.size + 4)
        System.arraycopy(KEY_INFO, 0, info, 0, KEY_INFO.size)
        info[KEY_INFO.size] = (sessionId ushr 24).toByte()
        info[KEY_INFO.size + 1] = (sessionId ushr 16).toByte()
        info[KEY_INFO.size + 2] = (sessionId ushr 8).toByte()
        info[KEY_INFO.size + 3] = sessionId.toByte()
        val okm = hkdf(kt, nonceR + nonceS, info, KEY_LEN + NONCE_SALT_LEN)
        val keys = SessionKeys(okm.copyOfRange(0, KEY_LEN), okm.copyOfRange(KEY_LEN, KEY_LEN + NONCE_SALT_LEN))
        okm.fill(0)
        return keys
    }
}

/**
 * Estende o número de sequência RTP de 16 bits para 32 bits (`rollover * 65536 + seq`, 5.3) seguindo
 * o estimador da RFC 3711 (3.3.1): escolhe o rollover mais próximo do último número visto. Serve ao
 * emissor (sequência crescente; a retransmissão da Fase 2 reusa o mesmo rollover) e ao receptor.
 */
class SequenceExtender {
    private var started = false
    private var highestIndex = 0L

    /** Estima o índice estendido de [seq] (0..65535) SEM alterar o estado. */
    fun estimate(seq: Int): Long {
        if (!started) return seq.toLong()
        val base = (highestIndex and 0xFFFFL.inv()) or seq.toLong()
        var best = base
        var bestDistance = Math.abs(base - highestIndex)
        for (candidate in longArrayOf(base - 0x10000L, base + 0x10000L)) {
            if (candidate < 0) continue
            val d = Math.abs(candidate - highestIndex)
            if (d < bestDistance) {
                best = candidate
                bestDistance = d
            }
        }
        return best
    }

    /** Estima e registra [seq] como visto (o máximo só avança). */
    fun extend(seq: Int): Long {
        val index = estimate(seq)
        if (!started || index > highestIndex) highestIndex = index
        started = true
        return index
    }
}

/**
 * Janela anti-repetição deslizante por SSRC (5.3): 1024 pacotes. [isReplay] diz se o índice (32 bits
 * estendido) já foi visto ou é velho demais; [mark] o registra DEPOIS de a tag AEAD validar.
 */
class ReplayWindow(private val size: Int = DEFAULT_SIZE) {
    private val bits = LongArray((size + 63) / 64)
    private var highest = -1L

    fun isReplay(index: Long): Boolean {
        if (highest < 0) return false
        if (index > highest) return false
        val delta = highest - index
        if (delta >= size) return true
        return getBit(index)
    }

    fun mark(index: Long) {
        if (highest < 0) {
            highest = index
            setBit(index, true)
            return
        }
        if (index > highest) {
            val shift = index - highest
            if (shift >= size) {
                bits.fill(0L)
            } else {
                // limpa as posições que passam a representar índices novos
                var i = highest + 1
                while (i <= index) {
                    setBit(i, false)
                    i++
                }
            }
            highest = index
            setBit(index, true)
        } else if (highest - index < size) {
            setBit(index, true)
        }
    }

    private fun pos(index: Long) = (index % size).toInt()
    private fun getBit(index: Long): Boolean {
        val p = pos(index)
        return (bits[p ushr 6] ushr (p and 63)) and 1L == 1L
    }

    private fun setBit(index: Long, value: Boolean) {
        val p = pos(index)
        val mask = 1L shl (p and 63)
        bits[p ushr 6] = if (value) bits[p ushr 6] or mask else bits[p ushr 6] and mask.inv()
    }

    companion object {
        const val DEFAULT_SIZE = 1024
    }
}

/** Monta o nonce GCM de 12 bytes: `salt32(4) || ssrc(4) || índice32(4)` (5.3). */
internal fun fillGcmNonce(nonce: ByteArray, nonceSalt: ByteArray, ssrc: Int, index32: Long) {
    nonce[0] = nonceSalt[0]
    nonce[1] = nonceSalt[1]
    nonce[2] = nonceSalt[2]
    nonce[3] = nonceSalt[3]
    nonce[4] = (ssrc ushr 24).toByte()
    nonce[5] = (ssrc ushr 16).toByte()
    nonce[6] = (ssrc ushr 8).toByte()
    nonce[7] = ssrc.toByte()
    nonce[8] = (index32 ushr 24).toByte()
    nonce[9] = (index32 ushr 16).toByte()
    nonce[10] = (index32 ushr 8).toByte()
    nonce[11] = index32.toByte()
}

private const val RTP_HEADER_LEN = 12

/**
 * Cifra os pacotes RTP de UM fluxo (SSRC) de UMA sessão (5.3): o cabeçalho de 12 bytes sai em claro
 * e é o AAD; o payload sai cifrado, seguido da tag de 16 bytes. Usado por uma thread só (a de envio);
 * não aloca nada além do que o provedor JCA aloca internamente por `init`.
 */
class BspStreamSealer(key: ByteArray, nonceSalt: ByteArray, private val ssrc: Int) {
    private val nonceSalt = nonceSalt.copyOf()
    private val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val keySpec = SecretKeySpec(key.copyOf(), "AES")
    private val nonce = ByteArray(12)
    private val extender = SequenceExtender()

    /**
     * Lê o RTP em claro de [packet] (`length` bytes) e escreve o datagrama cifrado em [out]
     * (precisa de `length + 16` bytes). Devolve o tamanho final. [packet] e [out] não podem ser o mesmo array.
     */
    fun seal(packet: ByteArray, length: Int, out: ByteArray): Int {
        require(length >= RTP_HEADER_LEN) { "pacote RTP curto" }
        val seq = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val index = extender.extend(seq) and 0xFFFFFFFFL
        fillGcmNonce(nonce, nonceSalt, ssrc, index)
        System.arraycopy(packet, 0, out, 0, RTP_HEADER_LEN)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(BspCrypto.TAG_LEN * 8, nonce))
        cipher.updateAAD(packet, 0, RTP_HEADER_LEN)
        val n = cipher.doFinal(packet, RTP_HEADER_LEN, length - RTP_HEADER_LEN, out, RTP_HEADER_LEN)
        return RTP_HEADER_LEN + n
    }
}

/**
 * Lado receptor do AEAD (usado nos testes de unidade e como referência para o plugin): valida a tag,
 * decifra e aplica a janela anti-repetição. Devolve o tamanho do RTP em claro, ou -1 (pacote inválido,
 * repetido ou velho demais).
 */
class BspStreamOpener(key: ByteArray, nonceSalt: ByteArray, private val ssrc: Int) {
    private val nonceSalt = nonceSalt.copyOf()
    private val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val keySpec = SecretKeySpec(key.copyOf(), "AES")
    private val nonce = ByteArray(12)
    private val extender = SequenceExtender()
    private val window = ReplayWindow()

    fun open(datagram: ByteArray, length: Int, out: ByteArray): Int {
        if (length < RTP_HEADER_LEN + BspCrypto.TAG_LEN) return -1
        val packetSsrc = ((datagram[8].toInt() and 0xFF) shl 24) or ((datagram[9].toInt() and 0xFF) shl 16) or
            ((datagram[10].toInt() and 0xFF) shl 8) or (datagram[11].toInt() and 0xFF)
        if (packetSsrc != ssrc) return -1
        val seq = ((datagram[2].toInt() and 0xFF) shl 8) or (datagram[3].toInt() and 0xFF)
        val index = extender.estimate(seq) and 0xFFFFFFFFL
        if (window.isReplay(index)) return -1
        fillGcmNonce(nonce, nonceSalt, ssrc, index)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(BspCrypto.TAG_LEN * 8, nonce))
            cipher.updateAAD(datagram, 0, RTP_HEADER_LEN)
            System.arraycopy(datagram, 0, out, 0, RTP_HEADER_LEN)
            val n = cipher.doFinal(datagram, RTP_HEADER_LEN, length - RTP_HEADER_LEN, out, RTP_HEADER_LEN)
            // só depois de a tag validar o pacote passa a contar para a janela e para o rollover
            extender.extend(seq)
            window.mark(index)
            RTP_HEADER_LEN + n
        } catch (_: java.security.GeneralSecurityException) {
            -1
        }
    }
}
