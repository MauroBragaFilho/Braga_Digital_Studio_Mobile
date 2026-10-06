package com.bragastudio.mobile.featurepreview.components.scopes

/**
 * Lógica pura (sem dependência de Android) para converter os dados brutos dos
 * scopes do motor nativo em pixels ARGB. Fica separada dos composables para
 * rodar em Dispatchers.Default (M29) e para ser testável com JUnit puro.
 */
internal const val SCOPE_SIZE = 256
internal const val SCOPE_PIXELS = SCOPE_SIZE * SCOPE_SIZE

private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

/** Maior valor entre os histogramas informados (mínimo 1, para evitar divisão por zero). */
internal fun histogramPeak(vararg channels: IntArray?): Int {
    var peak = 1
    for (channel in channels) {
        if (channel == null) continue
        for (v in channel) if (v > peak) peak = v
    }
    return peak
}

/**
 * Waveform: [data] tem 256x256 entradas (linha = nível, coluna = x) com contagens
 * R/G/B/L empacotadas em 8 bits cada. Escreve em [out] (256x256, linha 0 = topo,
 * isto é, nível 255) sem alocar. Entradas ausentes ficam transparentes.
 */
internal fun waveformToPixels(data: IntArray, out: IntArray) {
    require(out.size >= SCOPE_PIXELS) { "buffer de saída pequeno demais" }
    if (data.size < SCOPE_PIXELS) {
        java.util.Arrays.fill(out, 0, SCOPE_PIXELS, 0)
        return
    }
    for (y in 0 until SCOPE_SIZE) {
        val rowOut = (SCOPE_SIZE - 1 - y) * SCOPE_SIZE // inverte: nível 0 embaixo
        val rowIn = y * SCOPE_SIZE
        for (x in 0 until SCOPE_SIZE) {
            val packed = data[rowIn + x]
            if (packed == 0) {
                out[rowOut + x] = 0
                continue
            }
            val rCount = (packed ushr 24) and 0xFF
            val gCount = (packed ushr 16) and 0xFF
            val bCount = (packed ushr 8) and 0xFF
            val lCount = packed and 0xFF

            val rInt = if (rCount > 0) 3 + rCount * 10 else 0
            val gInt = if (gCount > 0) 3 + gCount * 10 else 0
            val bInt = if (bCount > 0) 3 + bCount * 10 else 0
            val lInt = if (lCount > 0) 50 + lCount * 10 else 0

            val outR = (rInt + lInt).coerceIn(0, 255)
            val outG = (gInt + lInt).coerceIn(0, 255)
            val outB = (bInt + lInt).coerceIn(0, 255)

            val maxIntensity = maxOf(outR, maxOf(outG, outB))
            out[rowOut + x] = if (maxIntensity > 0) {
                argb(
                    maxIntensity,
                    (outR * 255 / maxIntensity).coerceIn(0, 255),
                    (outG * 255 / maxIntensity).coerceIn(0, 255),
                    (outB * 255 / maxIntensity).coerceIn(0, 255),
                )
            } else {
                0
            }
        }
    }
}

/**
 * Vectorscope: [data] tem 256x256 densidades (linha = V, coluna = U). Escreve em
 * [out] com V invertido (255 no topo). A cor de cada ponto é a croma máxima (Y=255).
 */
internal fun vectorscopeToPixels(data: IntArray, out: IntArray) {
    require(out.size >= SCOPE_PIXELS) { "buffer de saída pequeno demais" }
    if (data.size < SCOPE_PIXELS) {
        java.util.Arrays.fill(out, 0, SCOPE_PIXELS, 0)
        return
    }
    var maxDensity = 1
    for (i in 0 until SCOPE_PIXELS) if (data[i] > maxDensity) maxDensity = data[i]

    for (v in 0 until SCOPE_SIZE) {
        val rowOut = (SCOPE_SIZE - 1 - v) * SCOPE_SIZE
        val rowIn = v * SCOPE_SIZE
        for (u in 0 until SCOPE_SIZE) {
            val density = data[rowIn + u]
            if (density <= 0) {
                out[rowOut + u] = 0
                continue
            }
            // Intensidade-base 150 para o ponto sempre aparecer; satura em 255.
            val intensity = (150 + (density.toFloat() / maxDensity * 255 * 10)).toInt().coerceIn(0, 255)
            val y = 255f
            val cb = u - 128f
            val cr = v - 128f
            val r = (y + 1.402f * cr).toInt().coerceIn(0, 255)
            val g = (y - 0.344136f * cb - 0.714136f * cr).toInt().coerceIn(0, 255)
            val b = (y + 1.772f * cb).toInt().coerceIn(0, 255)
            out[rowOut + u] = argb(intensity, r, g, b)
        }
    }
}
