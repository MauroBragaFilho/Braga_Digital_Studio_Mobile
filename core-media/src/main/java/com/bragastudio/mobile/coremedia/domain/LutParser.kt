package com.bragastudio.mobile.coremedia.domain

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader

/** Erro de formato/limite ao ler um .cube. A mensagem é exibível ao usuário (pt-BR). */
class LutParseException(message: String) : Exception(message)

/**
 * Parser de LUT 3D no formato Adobe/Resolve .cube.
 *
 * Regras (M20/B54):
 *  - tokens separados por qualquer espaço/tab (`trim().split(Regex("\\s+"))`);
 *  - comentários `#` (inclusive no fim da linha) e `TITLE` ignorados;
 *  - `DOMAIN_MIN`/`DOMAIN_MAX` respeitados: os valores são normalizados para 0..1;
 *  - `LUT_3D_SIZE` entre 2 e [MAX_LUT_SIZE], com tamanho calculado em Long (sem overflow de Int);
 *  - `LUT_1D_SIZE` é rejeitado com erro explícito (não há suporte a LUT 1D);
 *  - arquivo limitado a [MAX_FILE_BYTES]; contagem de entradas deve bater exatamente.
 */
class LutParser {

    /**
     * @property size lado da grade 3D (N).
     * @property floatData N*N*N entradas RGBA (A = 1), R varia mais rápido, já normalizadas por DOMAIN_*.
     * @property domainMin/domainMax domínio declarado no arquivo (padrão 0..1), só informativo.
     */
    class LutData(
        val size: Int,
        val floatData: FloatArray,
        val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
        val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
    )

    /** Resultado da leitura só do cabeçalho (usado na sincronização do disco, sem ler os dados). */
    sealed class Header {
        data class Lut3D(val size: Int) : Header()
        object Lut1D : Header()
        object Unknown : Header()
    }

    companion object {
        private const val TAG = "LutParser"

        /** Teto de tamanho do arquivo (uma LUT 65^3 em texto tem ~8 MB). */
        const val MAX_FILE_BYTES: Long = 16L * 1024 * 1024

        const val MIN_LUT_SIZE = 2
        const val MAX_LUT_SIZE = 65

        private val WHITESPACE = Regex("\\s+")

        // ------------------------------------------------------------ API com Context/arquivos

        fun parseFromUri(context: Context, uri: android.net.Uri): LutData? {
            return try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return null
                inputStream.use { parse(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse LUT from URI: ${e.message}")
                null
            }
        }

        fun parseFromFile(file: File): LutData? = tryParseFromFile(file).getOrNull()

        /** Como [parseFromFile], mas devolve o motivo da falha (para mostrar ao usuário). */
        fun tryParseFromFile(file: File): Result<LutData> {
            return try {
                if (file.length() > MAX_FILE_BYTES) {
                    return Result.failure(LutParseException("Arquivo LUT maior que ${MAX_FILE_BYTES / (1024 * 1024)} MB."))
                }
                FileInputStream(file).use { Result.success(parse(it)) }
            } catch (e: LutParseException) {
                Log.e(TAG, "LUT inválida (${file.name}): ${e.message}")
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse LUT from file: ${e.message}")
                Result.failure(e)
            }
        }

        fun parseFromAssets(context: Context, fileName: String): LutData? = try {
            context.assets.open(fileName).use { parse(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LUT from assets: ${e.message}")
            null
        }

        /** Lê só o cabeçalho (primeiras linhas) para classificar o arquivo sem carregar os dados. */
        fun peekHeader(file: File): Header = try {
            FileInputStream(file).use { peekHeader(it) }
        } catch (e: Exception) {
            Header.Unknown
        }

        // ------------------------------------------------------------ núcleo (puro, testável)

        fun peekHeader(inputStream: InputStream): Header {
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            var lines = 0
            while (lines < MAX_HEADER_LINES) {
                val raw = reader.readLine() ?: break
                lines++
                val tokens = tokenize(raw)
                if (tokens.isEmpty()) continue
                when (tokens[0].uppercase()) {
                    "LUT_1D_SIZE" -> return Header.Lut1D

                    "LUT_3D_SIZE" -> {
                        val n = tokens.getOrNull(1)?.toIntOrNull()
                        return if (n != null && n in MIN_LUT_SIZE..MAX_LUT_SIZE) Header.Lut3D(n) else Header.Unknown
                    }
                }
            }
            return Header.Unknown
        }

        /**
         * Lê um .cube completo. Lança [LutParseException] com mensagem exibível em caso de
         * formato inválido, LUT 1D, tamanho fora dos limites ou arquivo acima de [MAX_FILE_BYTES].
         * Não fecha [inputStream].
         */
        fun parse(inputStream: InputStream): LutData {
            val reader = BufferedReader(InputStreamReader(LimitedInputStream(inputStream, MAX_FILE_BYTES), Charsets.UTF_8))

            var size = 0
            var expectedValues = 0L // size^3 * 4 como Long
            var data: FloatArray? = null
            var cursor = 0 // próxima posição livre em data
            val dMin = floatArrayOf(0f, 0f, 0f)
            val dMax = floatArrayOf(1f, 1f, 1f)
            var customDomain = false
            var domainChecked = false

            while (true) {
                val raw = reader.readLine() ?: break
                val tokens = tokenize(raw)
                if (tokens.isEmpty()) continue
                val head = tokens[0]

                val asFloat = head.toFloatOrNull()
                if (asFloat != null) {
                    // Linha de dados: R G B
                    val buffer = data ?: throw LutParseException("Valores encontrados antes de LUT_3D_SIZE.")
                    if (!domainChecked) {
                        checkDomain(dMin, dMax)
                        domainChecked = true
                    }
                    if (tokens.size < 3) throw LutParseException("Linha de dados inválida: esperado R G B.")
                    if (cursor + 4 > buffer.size) throw LutParseException("O arquivo tem mais entradas do que o LUT_3D_SIZE declara.")
                    for (c in 0 until 3) {
                        val v = tokens[c].toFloatOrNull()
                        if (v == null || v.isNaN() || v.isInfinite()) throw LutParseException("Valor inválido na LUT: '${tokens[c]}'.")
                        buffer[cursor + c] = if (customDomain) {
                            val span = dMax[c] - dMin[c]
                            (v - dMin[c]) / span
                        } else {
                            v
                        }
                    }
                    buffer[cursor + 3] = 1.0f
                    cursor += 4
                    continue
                }

                when (head.uppercase()) {
                    "LUT_3D_SIZE" -> {
                        if (data != null) throw LutParseException("LUT_3D_SIZE repetido.")
                        val n = tokens.getOrNull(1)?.toIntOrNull()
                            ?: throw LutParseException("LUT_3D_SIZE inválido.")
                        if (n < MIN_LUT_SIZE || n > MAX_LUT_SIZE) {
                            throw LutParseException("LUT_3D_SIZE $n fora do permitido ($MIN_LUT_SIZE a $MAX_LUT_SIZE).")
                        }
                        size = n
                        // Long: com Int, size*size*size*4 estouraria para tamanhos absurdos.
                        expectedValues = n.toLong() * n * n * 4L
                        if (expectedValues > Int.MAX_VALUE) throw LutParseException("LUT grande demais.")
                        data = FloatArray(expectedValues.toInt())
                    }

                    "LUT_1D_SIZE" ->
                        throw LutParseException("LUT 1D não é suportada; use uma LUT 3D (.cube com LUT_3D_SIZE).")

                    "DOMAIN_MIN" -> readTriple(tokens, dMin, "DOMAIN_MIN")

                    "DOMAIN_MAX" -> readTriple(tokens, dMax, "DOMAIN_MAX")

                    // TITLE, LUT_*_INPUT_RANGE e metadados desconhecidos são ignorados.
                    else -> Unit
                }
                customDomain = dMin.any { it != 0f } || dMax.any { it != 1f }
            }

            checkDomain(dMin, dMax)
            val buffer = data ?: throw LutParseException("LUT_3D_SIZE não encontrado.")
            if (cursor.toLong() != expectedValues) {
                throw LutParseException("LUT incompleta: ${cursor / 4} entradas, esperado ${expectedValues / 4} (tamanho $size).")
            }
            return LutData(size, buffer, dMin, dMax)
        }

        /** DOMAIN_MAX precisa ser maior que DOMAIN_MIN em cada canal (evita divisão por zero). */
        private fun checkDomain(dMin: FloatArray, dMax: FloatArray) {
            for (c in 0 until 3) {
                if (!(dMax[c] > dMin[c])) throw LutParseException("DOMAIN_MAX deve ser maior que DOMAIN_MIN.")
            }
        }

        private fun readTriple(tokens: List<String>, out: FloatArray, name: String) {
            if (tokens.size < 4) throw LutParseException("$name inválido.")
            for (c in 0 until 3) {
                val v = tokens[c + 1].toFloatOrNull()
                if (v == null || v.isNaN() || v.isInfinite()) throw LutParseException("$name inválido.")
                out[c] = v
            }
        }

        /** Remove comentário `#`, aparas e quebra por qualquer espaço/tab. Linha vazia -> lista vazia. */
        internal fun tokenize(rawLine: String): List<String> {
            val hash = rawLine.indexOf('#')
            // Remove BOM UTF-8 (arquivos exportados no Windows) antes de aparar.
            val line = (if (hash >= 0) rawLine.substring(0, hash) else rawLine).trimStart('\uFEFF').trim()
            if (line.isEmpty()) return emptyList()
            return line.split(WHITESPACE)
        }

        fun getLutByteArray(lutData: LutData): ByteArray {
            val floatArray = lutData.floatData
            val byteArray = ByteArray(floatArray.size)
            for (i in floatArray.indices) {
                // Clamp entre 0.0 e 1.0, depois multiplica por 255
                val clamped = floatArray[i].coerceIn(0.0f, 1.0f)
                byteArray[i] = (clamped * 255.0f + 0.5f).toInt().toByte()
            }
            return byteArray
        }

        private const val MAX_HEADER_LINES = 200
    }

    /** Falha ao ultrapassar [limit] bytes lidos (protege contra arquivo gigante sem cabeçalho). */
    private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) add(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) add(n.toLong())
            return n
        }

        private fun add(n: Long) {
            count += n
            if (count > limit) throw LutParseException("Arquivo LUT maior que ${limit / (1024 * 1024)} MB.")
        }
    }
}
