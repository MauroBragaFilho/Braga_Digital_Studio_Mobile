package com.bragastudio.mobile.coremedia.domain

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LutParserTest {

    private fun stream(text: String): InputStream = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    /** LUT identidade de lado [n]: R varia mais rápido, como no formato .cube. */
    private fun identityCube(n: Int, separator: String = " ", header: String = ""): String = buildString {
        append(header)
        append("LUT_3D_SIZE $n\n")
        for (b in 0 until n) {
            for (g in 0 until n) {
                for (r in 0 until n) {
                    val d = (n - 1).toFloat()
                    append("${r / d}$separator${g / d}$separator${b / d}\n")
                }
            }
        }
    }

    private fun parseError(text: String): String {
        try {
            LutParser.parse(stream(text))
        } catch (e: LutParseException) {
            return e.message ?: ""
        }
        fail("Esperava LutParseException")
        return ""
    }

    @Test
    fun lutIdentidade_eLidaComTamanhoEPreenchimentoCorretos() {
        val lut = LutParser.parse(stream(identityCube(2)))
        assertEquals(2, lut.size)
        assertEquals(2 * 2 * 2 * 4, lut.floatData.size)
        // Segunda entrada: R=1, G=0, B=0, A=1
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f), lut.floatData.copyOfRange(4, 8), 0f)
        // Última entrada: branco
        assertArrayEquals(floatArrayOf(1f, 1f, 1f, 1f), lut.floatData.copyOfRange(28, 32), 0f)
    }

    @Test
    fun separadoresTabEEspacosMultiplos_saoAceitos() {
        val tabs = LutParser.parse(stream(identityCube(2, separator = "\t")))
        assertEquals(2, tabs.size)
        val mixed = "TITLE \"x\"\n   LUT_3D_SIZE\t2  \n" +
            "0 \t 0   0\n1\t0\t0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n"
        assertEquals(2, LutParser.parse(stream(mixed)).size)
    }

    @Test
    fun comentariosEBomSaoIgnorados() {
        val text = "# gerado por teste\nTITLE \"Look\" # inline\n" + identityCube(2) + "# fim\n"
        assertEquals(2, LutParser.parse(stream(text)).size)
    }

    @Test
    fun domainMinMax_normalizaOsValoresPara01() {
        // Domínio -1..3: o valor 1.0 deve virar (1-(-1))/4 = 0.5
        val text = "DOMAIN_MIN -1 -1 -1\nDOMAIN_MAX 3 3 3\nLUT_3D_SIZE 2\n" +
            "-1 -1 -1\n3 -1 -1\n-1 3 -1\n3 3 -1\n-1 -1 3\n3 -1 3\n-1 3 3\n1 1 1\n"
        val lut = LutParser.parse(stream(text))
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), lut.floatData.copyOfRange(0, 4), 1e-6f)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f), lut.floatData.copyOfRange(4, 8), 1e-6f)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f, 1f), lut.floatData.copyOfRange(28, 32), 1e-6f)
        assertArrayEquals(floatArrayOf(-1f, -1f, -1f), lut.domainMin, 0f)
        assertArrayEquals(floatArrayOf(3f, 3f, 3f), lut.domainMax, 0f)
    }

    @Test
    fun domainInvalido_maxNaoMaiorQueMin_ERejeitado() {
        val msg = parseError("DOMAIN_MIN 1 1 1\nDOMAIN_MAX 0 0 0\nLUT_3D_SIZE 2\n")
        assertTrue(msg, msg.contains("DOMAIN"))
    }

    @Test
    fun tamanhoAbsurdo_eRejeitadoSemEstourarMemoriaNemInt() {
        // 2.000.000.000^3 * 4 estouraria Int; deve ser recusado pelo teto de 65 antes de alocar.
        val msg = parseError("LUT_3D_SIZE 2000000000\n0 0 0\n")
        assertTrue(msg, msg.contains("fora do permitido"))
        parseError("LUT_3D_SIZE 66\n")
        parseError("LUT_3D_SIZE 1\n")
        parseError("LUT_3D_SIZE 0\n")
        parseError("LUT_3D_SIZE -5\n")
        parseError("LUT_3D_SIZE 99999999999999\n") // nem cabe em Int
    }

    @Test
    fun tamanhoMaximoPermitido_65_AlocaOBufferEsperado() {
        val header = LutParser.peekHeader(stream("LUT_3D_SIZE 65\n"))
        assertEquals(LutParser.Header.Lut3D(65), header)
    }

    @Test
    fun lut1D_eRejeitadaComErroExplicito() {
        val msg = parseError("LUT_1D_SIZE 4\n0 0 0\n0.3 0.3 0.3\n0.6 0.6 0.6\n1 1 1\n")
        assertTrue(msg, msg.contains("1D"))
        assertEquals(LutParser.Header.Lut1D, LutParser.peekHeader(stream("TITLE x\nLUT_1D_SIZE 4\n")))
    }

    @Test
    fun dadosIncompletosOuSobrando_sãoRejeitados() {
        val incomplete = identityCube(2).lines().dropLast(2).joinToString("\n")
        assertTrue(parseError(incomplete).contains("incompleta"))
        val extra = identityCube(2) + "0 0 0\n"
        assertTrue(parseError(extra).contains("mais entradas"))
    }

    @Test
    fun dadosAntesDoTamanho_semTamanho_eValoresInvalidos_sãoRejeitados() {
        parseError("0 0 0\nLUT_3D_SIZE 2\n")
        parseError("TITLE sem dados\n")
        parseError("LUT_3D_SIZE 2\n0 0 abc\n")
        parseError("LUT_3D_SIZE 2\nNaN 0 0\n")
        parseError("LUT_3D_SIZE 2\n0 0\n")
        parseError("LUT_3D_SIZE 2\nLUT_3D_SIZE 2\n")
    }

    @Test
    fun arquivoAcimaDoTeto_eInterrompidoSemLerTudo() {
        // Fluxo "infinito" de comentários: o limite de 16 MB deve cortar a leitura.
        val endless = object : InputStream() {
            private val line = "# padding padding padding padding padding padding\n".toByteArray()
            private var pos = 0
            override fun read(): Int {
                val b = line[pos].toInt() and 0xFF
                pos = (pos + 1) % line.size
                return b
            }
        }
        try {
            LutParser.parse(endless)
            fail("Esperava LutParseException por tamanho")
        } catch (e: LutParseException) {
            assertTrue(e.message!!, e.message!!.contains("16"))
        }
    }

    @Test
    fun peekHeader_arquivoSemTamanho_eDesconhecido() {
        assertEquals(LutParser.Header.Unknown, LutParser.peekHeader(stream("# nada\nTITLE x\n")))
        assertEquals(LutParser.Header.Unknown, LutParser.peekHeader(stream("LUT_3D_SIZE 500\n")))
        assertEquals(LutParser.Header.Lut3D(33), LutParser.peekHeader(stream("# c\nLUT_3D_SIZE\t33\n")))
    }

    @Test
    fun getLutByteArray_arredondaEClampaEm0a255() {
        val data = LutParser.LutData(
            2,
            FloatArray(32).also {
                it[0] = -0.5f
                it[1] = 0.5f
                it[2] = 1f
                it[3] = 2f
            },
        )
        val bytes = LutParser.getLutByteArray(data)
        assertEquals(32, bytes.size)
        assertEquals(0, bytes[0].toInt() and 0xFF)
        assertEquals(128, bytes[1].toInt() and 0xFF)
        assertEquals(255, bytes[2].toInt() and 0xFF)
        assertEquals(255, bytes[3].toInt() and 0xFF)
    }

    @Test
    fun tokenize_removeComentarioEAparas() {
        assertEquals(listOf("A", "B", "C"), LutParser.tokenize("  A \t B   C  # x"))
        assertTrue(LutParser.tokenize("   # so comentario").isEmpty())
        assertTrue(LutParser.tokenize("").isEmpty())
    }
}
