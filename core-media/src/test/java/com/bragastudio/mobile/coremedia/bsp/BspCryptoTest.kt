package com.bragastudio.mobile.coremedia.bsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

class BspCryptoTest {

    // ---- SHA-256 / HMAC-SHA256 (RFC 4231) ---------------------------------------------

    @Test
    fun sha256_abc() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", BspCrypto.sha256("abc".toByteArray()).toHex())
    }

    @Test
    fun hmacSha256_rfc4231_case1() {
        val mac = BspCrypto.hmacSha256(ByteArray(20) { 0x0b }, "Hi There".toByteArray())
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", mac.toHex())
    }

    @Test
    fun hmacSha256_rfc4231_case2_andMultiPartInput() {
        val key = "Jefe".toByteArray()
        val whole = BspCrypto.hmacSha256(key, "what do ya want for nothing?".toByteArray())
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", whole.toHex())
        val parts = BspCrypto.hmacSha256(key, "what do ya ".toByteArray(), "want for nothing?".toByteArray())
        assertArrayEquals(whole, parts)
    }

    @Test(expected = IllegalArgumentException::class)
    fun hmacSha256_rejectsEmptyKey() {
        BspCrypto.hmacSha256(ByteArray(0), byteArrayOf(1))
    }

    // ---- HKDF-SHA256 (RFC 5869, apêndice A) -------------------------------------------

    @Test
    fun hkdf_rfc5869_case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", BspCrypto.hkdfExtract(salt, ikm).toHex())
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            BspCrypto.hkdf(ikm, salt, info, 42).toHex(),
        )
    }

    @Test
    fun hkdf_rfc5869_case2_longInputs() {
        val ikm = ByteArray(80) { it.toByte() }
        val salt = ByteArray(80) { (0x60 + it).toByte() }
        val info = ByteArray(80) { (0xb0 + it).toByte() }
        assertEquals("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244", BspCrypto.hkdfExtract(salt, ikm).toHex())
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87",
            BspCrypto.hkdf(ikm, salt, info, 82).toHex(),
        )
    }

    @Test
    fun hkdf_rfc5869_case3_emptySaltAndInfo() {
        val ikm = ByteArray(22) { 0x0b }
        assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", BspCrypto.hkdfExtract(ByteArray(0), ikm).toHex())
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            BspCrypto.hkdf(ikm, ByteArray(0), ByteArray(0), 42).toHex(),
        )
    }

    @Test
    fun hkdfExpand_isAStablePrefix() {
        val prk = BspCrypto.hkdfExtract(byteArrayOf(1, 2, 3), byteArrayOf(9, 9, 9))
        val long = BspCrypto.hkdfExpand(prk, byteArrayOf(7), 64)
        assertArrayEquals(long.copyOf(16), BspCrypto.hkdfExpand(prk, byteArrayOf(7), 16))
        assertArrayEquals(long.copyOf(20), BspCrypto.hkdfExpand(prk, byteArrayOf(7), 20))
    }

    @Test(expected = IllegalArgumentException::class)
    fun hkdfExpand_rejectsOutputBeyond255Blocks() {
        BspCrypto.hkdfExpand(ByteArray(32), ByteArray(0), 255 * 32 + 1)
    }

    // ---- AES-128-GCM (McGrew e Viega, casos 1, 2 e 4) ---------------------------------

    @Test
    fun aesGcm_knownVectors() {
        // caso 1: tudo vazio -> só a tag
        assertEquals("58e2fccefa7e3061367f1d57a4e7455a", BspCrypto.aesGcmSeal(ByteArray(16), ByteArray(12), ByteArray(0), ByteArray(0)).toHex())
        // caso 2: 16 zeros
        assertEquals(
            "0388dace60b6a392f328c2b971b2fe78" + "ab6e47d42cec13bdf53a67b21257bddf",
            BspCrypto.aesGcmSeal(ByteArray(16), ByteArray(12), ByteArray(0), ByteArray(16)).toHex(),
        )
        // caso 4: 60 bytes com AAD
        val key = hex("feffe9928665731c6d6a8f9467308308")
        val iv = hex("cafebabefacedbaddecaf888")
        val plain = hex(
            "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39",
        )
        val aad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        val expected = "42831ec2217774244b7221b784d0d49ce3aa212f2c02a4e035c17e2329aca12e" +
            "21d514b25466931c7d8f6a5aac84aa051ba30b396a0aac973d58e091" + "5bc94fbc3221a5db94fae95ae7121a47"
        assertEquals(expected, BspCrypto.aesGcmSeal(key, iv, aad, plain).toHex())
    }

    // ---- Autenticação e derivação de chave ---------------------------------------------

    private val kt = BspCrypto.sha256("token-de-teste".toByteArray())
    private val nonceR = ByteArray(16) { (it + 1).toByte() }
    private val nonceS = ByteArray(16) { (0xA0 + it).toByte() }

    @Test
    fun helloProof_isHmacOfNonceAndLabel() {
        val expected = BspCrypto.hmacSha256(kt, nonceR, "BSP-HELLO".toByteArray())
        assertArrayEquals(expected, BspCrypto.helloProof(kt, nonceR))
        // outra chave ou outro nonce = outra prova
        assertFalse(BspCrypto.constantTimeEquals(expected, BspCrypto.helloProof(BspCrypto.sha256("outro".toByteArray()), nonceR)))
        assertFalse(BspCrypto.constantTimeEquals(expected, BspCrypto.helloProof(kt, ByteArray(16))))
    }

    @Test
    fun welcomeProof_bindsBothNoncesAndDiffersFromHello() {
        val w = BspCrypto.welcomeProof(kt, nonceR, nonceS)
        assertEquals(32, w.size)
        assertFalse(BspCrypto.constantTimeEquals(w, BspCrypto.helloProof(kt, nonceR)))
        assertFalse(BspCrypto.constantTimeEquals(w, BspCrypto.welcomeProof(kt, nonceR, ByteArray(16))))
    }

    @Test
    fun constantTimeEquals_comparesContentAndLength() {
        assertTrue(BspCrypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(BspCrypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(BspCrypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertFalse(BspCrypto.constantTimeEquals(byteArrayOf(0, 2, 3), byteArrayOf(1, 2, 3)))
    }

    @Test
    fun deriveSessionKeys_followsTheSpecFormula() {
        val sessionId = 0x12345678
        val keys = BspCrypto.deriveSessionKeys(kt, nonceR, nonceS, sessionId)
        val info = "BSP v2 key".toByteArray() + byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val okm = BspCrypto.hkdf(kt, nonceR + nonceS, info, 20)
        assertArrayEquals(okm.copyOfRange(0, 16), keys.key)
        assertArrayEquals(okm.copyOfRange(16, 20), keys.nonceSalt)
        // K da spec (16 bytes) é o prefixo do mesmo HKDF
        assertArrayEquals(BspCrypto.hkdf(kt, nonceR + nonceS, info, 16), keys.key)
    }

    @Test
    fun crossImplementationVector_matchesThePythonSimulator() {
        // Mesmos insumos do teste do simulador Python (bsp_receiver_sim): token "token-de-teste",
        // nonceR = 01..10, nonceS = A0..AF, sessionId = 0x12345678. Valores calculados pelo Python.
        assertEquals("20fd0ba9f641280a22f72a1145a0892b225fb0b2fdd75a53ba4f83f4596c72ae", kt.toHex())
        assertEquals("d755f938554f211a0de3e5e8c33498eeac0f408caf34d21b903a514044554083", BspCrypto.helloProof(kt, nonceR).toHex())
        assertEquals("0a5809f5a82df32d07c82ec6c34ee2f7d58a8c29b7291765990292be3b7a4f91", BspCrypto.welcomeProof(kt, nonceR, nonceS).toHex())
        val keys = BspCrypto.deriveSessionKeys(kt, nonceR, nonceS, 0x12345678)
        assertEquals("6a684270cb9909b92542a254994d4077", keys.key.toHex())
        assertEquals("813d8333", keys.nonceSalt.toHex())
    }

    @Test
    fun deriveSessionKeys_changesWithEachInput() {
        val base = BspCrypto.deriveSessionKeys(kt, nonceR, nonceS, 1).key
        assertNotEquals(base.toHex(), BspCrypto.deriveSessionKeys(kt, nonceR, nonceS, 2).key.toHex())
        assertNotEquals(base.toHex(), BspCrypto.deriveSessionKeys(kt, nonceR, ByteArray(16), 1).key.toHex())
        assertNotEquals(base.toHex(), BspCrypto.deriveSessionKeys(kt, ByteArray(16), nonceS, 1).key.toHex())
        assertNotEquals(base.toHex(), BspCrypto.deriveSessionKeys(BspCrypto.sha256(byteArrayOf(1)), nonceR, nonceS, 1).key.toHex())
    }

    @Test
    fun sessionKeys_wipeZeroesTheMaterial() {
        val keys = BspCrypto.deriveSessionKeys(kt, nonceR, nonceS, 7)
        keys.wipe()
        assertTrue(keys.key.all { it == 0.toByte() })
        assertTrue(keys.nonceSalt.all { it == 0.toByte() })
    }

    // ---- Sequência estendida e janela anti-repetição ----------------------------------

    @Test
    fun sequenceExtender_countsRolloversAndAcceptsReordering() {
        val e = SequenceExtender()
        assertEquals(65_530L, e.extend(65_530))
        assertEquals(65_535L, e.extend(65_535))
        assertEquals(65_536L, e.extend(0)) // virou: rollover 1
        assertEquals(65_537L, e.extend(1))
        assertEquals("pacote atrasado de antes da virada", 65_534L, e.extend(65_534))
        assertEquals("o máximo não recua", 65_538L, e.extend(2))
        assertEquals(65_536L, e.estimate(0))
    }

    @Test
    fun sequenceExtender_secondRollover() {
        val e = SequenceExtender()
        e.extend(10)
        var s = 10
        var last = 10L
        // anda 3 voltas completas em passos de 20 000
        repeat(10) {
            s = (s + 20_000) and 0xFFFF
            val idx = e.extend(s)
            assertTrue(idx > last)
            assertEquals(20_000L, idx - last)
            last = idx
        }
    }

    @Test
    fun replayWindow_rejectsDuplicatesAndOldPackets() {
        val w = ReplayWindow(1024)
        assertFalse(w.isReplay(100))
        w.mark(100)
        assertTrue("duplicado", w.isReplay(100))
        assertFalse(w.isReplay(101))
        w.mark(101)
        assertFalse("fora de ordem dentro da janela", w.isReplay(99))
        w.mark(99)
        assertTrue(w.isReplay(99))
        w.mark(5_000)
        assertTrue("velho demais (fora da janela de 1024)", w.isReplay(100))
        assertTrue(w.isReplay(5_000 - 1024))
        assertFalse(w.isReplay(5_000 - 1023))
        assertFalse(w.isReplay(5_001))
    }

    @Test
    fun replayWindow_slidesAndForgetsOldBits() {
        val w = ReplayWindow(64)
        for (i in 0L..63L) w.mark(i)
        assertTrue(w.isReplay(10))
        w.mark(70)
        // os índices 64..69 são novos (nunca vistos); 0..6 saíram da janela de 64
        assertFalse(w.isReplay(65))
        assertTrue(w.isReplay(6))
        w.mark(65)
        assertTrue(w.isReplay(65))
        // salto enorme limpa tudo
        w.mark(10_000)
        assertFalse(w.isReplay(9_999))
        assertTrue(w.isReplay(70))
    }

    // ---- Cifra por pacote (AEAD do BSP) -----------------------------------------------

    private val ssrc = 0x0BADF00D
    private val keys = BspCrypto.deriveSessionKeys(kt, nonceR, nonceS, 42)

    private fun rtp(seq: Int, payload: ByteArray, ssrc: Int = this.ssrc): ByteArray {
        val p = ByteArray(12 + payload.size)
        writeRtpHeader(p, marker = true, payloadType = 96, seq = seq, timestamp = 123_456, ssrc = ssrc)
        System.arraycopy(payload, 0, p, 12, payload.size)
        return p
    }

    private fun sealer() = BspStreamSealer(keys.key, keys.nonceSalt, ssrc)
    private fun opener() = BspStreamOpener(keys.key, keys.nonceSalt, ssrc)

    private fun seal(s: BspStreamSealer, packet: ByteArray): ByteArray {
        val out = ByteArray(packet.size + 16)
        val n = s.seal(packet, packet.size, out)
        assertEquals(packet.size + 16, n)
        return out.copyOf(n)
    }

    @Test
    fun aead_roundTripKeepsHeaderInClearAndEncryptsPayload() {
        val payload = ByteArray(300) { (it * 7).toByte() }
        val plain = rtp(1000, payload)
        val sealed = seal(sealer(), plain)

        assertArrayEquals("cabeçalho RTP em claro (é o AAD)", plain.copyOf(12), sealed.copyOf(12))
        assertFalse("payload cifrado", payload.contentEquals(sealed.copyOfRange(12, 12 + payload.size)))

        val out = ByteArray(sealed.size)
        val n = opener().open(sealed, sealed.size, out)
        assertEquals(plain.size, n)
        assertArrayEquals(plain, out.copyOf(n))
    }

    @Test
    fun aead_nonceLayoutIsSaltSsrcIndex_andMatchesTheReferenceCipher() {
        val payload = ByteArray(50) { it.toByte() }
        val plain = rtp(0x1234, payload)
        val sealed = seal(sealer(), plain)

        val nonce = keys.nonceSalt + byteArrayOf(0x0B, 0xAD.toByte(), 0xF0.toByte(), 0x0D) + byteArrayOf(0, 0, 0x12, 0x34)
        val reference = BspCrypto.aesGcmSeal(keys.key, nonce, plain.copyOf(12), payload)
        assertArrayEquals("texto cifrado + tag idênticos aos da cifra de referência", reference, sealed.copyOfRange(12, sealed.size))
    }

    @Test
    fun aead_tamperedTagHeaderOrCiphertextIsRejected() {
        val sealed = seal(sealer(), rtp(7, ByteArray(100) { 1 }))
        val out = ByteArray(sealed.size)

        val badTag = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertEquals(-1, opener().open(badTag, badTag.size, out))

        val badBody = sealed.copyOf().also { it[20] = (it[20].toInt() xor 0x40).toByte() }
        assertEquals(-1, opener().open(badBody, badBody.size, out))

        // o cabeçalho é AAD: mexer no timestamp (ou no marker) invalida a tag
        val badHeader = sealed.copyOf().also { it[6] = (it[6].toInt() xor 1).toByte() }
        assertEquals(-1, opener().open(badHeader, badHeader.size, out))
        val badMarker = sealed.copyOf().also { it[1] = (it[1].toInt() xor 0x80).toByte() }
        assertEquals(-1, opener().open(badMarker, badMarker.size, out))

        assertTrue("o original continua válido", opener().open(sealed, sealed.size, out) > 0)
    }

    @Test
    fun aead_wrongKeyOrWrongSsrcOrTruncatedIsRejected() {
        val sealed = seal(sealer(), rtp(7, ByteArray(100) { 1 }))
        val out = ByteArray(sealed.size)
        val otherKeys = BspCrypto.deriveSessionKeys(kt, nonceR, nonceS, 43)
        assertEquals(-1, BspStreamOpener(otherKeys.key, otherKeys.nonceSalt, ssrc).open(sealed, sealed.size, out))
        assertEquals(-1, BspStreamOpener(keys.key, keys.nonceSalt, ssrc + 1).open(sealed, sealed.size, out))
        assertEquals(-1, opener().open(sealed, 27, out))
        assertEquals(-1, opener().open(sealed, 5, out))
    }

    @Test
    fun aead_replayedAndTooOldPacketsAreRejected() {
        val s = sealer()
        val o = opener()
        val out = ByteArray(2000)
        val first = seal(s, rtp(1, ByteArray(40)))
        assertTrue(o.open(first, first.size, out) > 0)
        assertEquals("repetição do mesmo datagrama", -1, o.open(first, first.size, out))

        // fora de ordem dentro da janela: aceita uma vez só
        val third = seal(s, rtp(3, ByteArray(40)))
        val second = seal(s, rtp(2, ByteArray(40)))
        assertTrue(o.open(third, third.size, out) > 0)
        assertTrue(o.open(second, second.size, out) > 0)
        assertEquals(-1, o.open(second, second.size, out))

        // avança mais de 1024: o primeiro fica velho demais mesmo que a tag valide
        var last = third
        for (seq in 4..1100) {
            last = seal(s, rtp(seq, ByteArray(10)))
            assertTrue(o.open(last, last.size, out) > 0)
        }
        assertEquals(-1, o.open(second, second.size, out))
    }

    @Test
    fun aead_survivesSequenceRolloverWithoutNonceReuse() {
        val s = sealer()
        val o = opener()
        val out = ByteArray(200)
        val nonces = HashSet<String>()
        for (seq in intArrayOf(65_533, 65_534, 65_535, 0, 1, 2)) {
            val sealed = seal(s, rtp(seq, ByteArray(20) { seq.toByte() }))
            assertTrue("pacote seq=$seq", o.open(sealed, sealed.size, out) > 0)
            // mesmo conteúdo de payload cifrado nunca se repete entre sequências (nonce diferente)
            assertTrue(nonces.add(sealed.copyOfRange(12, sealed.size).toHex()))
        }
    }

    @Test
    fun aead_sameSeqInTwoRolloversUsesDifferentNonces() {
        // seq=5 na volta 0 e na volta 1 com o mesmo payload: textos cifrados diferentes
        val s = sealer()
        val payload = ByteArray(30) { 9 }
        val a = seal(s, rtp(5, payload))
        s.seal(rtp(40_000, payload), 42, ByteArray(100)) // meio da volta
        s.seal(rtp(65_000, payload), 42, ByteArray(100))
        val b = seal(s, rtp(5, payload)) // volta seguinte
        assertNotEquals(a.copyOfRange(12, a.size).toHex(), b.copyOfRange(12, b.size).toHex())
    }
}
