package com.bragastudio.mobile.coremedia.bsp

/** Utilidades do formato Annex-B (NALs separados por `00 00 01` ou `00 00 00 01`). Fora do caminho quente. */
object AnnexB {
    /** Separa [data] em NAL units cruas (sem start code e sem os zeros finais). Sem start code: um NAL com tudo. */
    fun split(data: ByteArray, offset: Int = 0, length: Int = data.size): List<ByteArray> {
        val end = offset + length
        val starts = ArrayList<Int>()
        var i = offset
        while (i + 2 < end) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return if (length > 0) listOf(data.copyOfRange(offset, end)) else emptyList()
        val out = ArrayList<ByteArray>(starts.size)
        for (k in starts.indices) {
            val s = starts[k]
            var e = if (k + 1 < starts.size) starts[k + 1] - 3 else end
            while (e > s && data[e - 1] == 0.toByte()) e--
            if (e > s) out += data.copyOfRange(s, e)
        }
        return out
    }

    /** SPS (tipo 7) e PPS (tipo 8) crus (sem start code) de um buffer Annex-B, ou null para o que faltar. */
    fun parameterSets(data: ByteArray, offset: Int = 0, length: Int = data.size): Pair<ByteArray?, ByteArray?> {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        for (nal in split(data, offset, length)) {
            when (nal[0].toInt() and 0x1F) {
                7 -> if (sps == null) sps = nal
                8 -> if (pps == null) pps = nal
            }
        }
        return sps to pps
    }
}
