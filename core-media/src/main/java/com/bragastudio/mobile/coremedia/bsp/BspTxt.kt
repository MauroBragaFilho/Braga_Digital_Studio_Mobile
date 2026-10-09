package com.bragastudio.mobile.coremedia.bsp

/** Dados do anúncio mDNS `_bsp._tcp` (3). */
data class BspAdvert(
    /** Nome de instância (rótulo DNS, até 63 bytes UTF-8). */
    val instanceName: String,
    val port: Int,
    val txt: Map<String, String>,
)

/**
 * Montagem pura do anúncio mDNS/DNS-SD do BSP (`.docs/BSP_ESPECIFICACAO.md`, 3): nome de instância e
 * registros TXT. Cada par `chave=valor` respeita o limite de 255 bytes do DNS-SD e o TXT inteiro fica
 * abaixo de 1300 bytes. O IP nunca entra no anúncio (a rede já o informa), nem o token.
 */
object BspTxt {
    const val SERVICE_TYPE = "_bsp._tcp"
    private const val MAX_LABEL_BYTES = 63
    private const val MAX_ENTRY_BYTES = 255
    private const val MAX_TXT_BYTES = 1300

    /** Nome de instância: `BDSM (nome da fonte)`, o mesmo visto no NDI, cortado em 63 bytes UTF-8 sem partir caractere. */
    fun instanceName(machineName: String, sourceName: String): String = truncateUtf8("$machineName ($sourceName)".filter { !it.isISOControl() }, MAX_LABEL_BYTES)

    /**
     * TXT da spec: `v=2`, `id`, `name`, `vc`, `ac`, `res`, `auth=link`, `link` (porta do BDSM Link) e
     * `st=idle|live`. Anuncia só o que a fonte implementa HOJE: `vc=h264` e `ac=aac` (HEVC e Opus são
     * previstos mas não existem; ver desvios em 0).
     */
    fun build(
        deviceId: String,
        displayName: String,
        width: Int,
        height: Int,
        fps: Int,
        linkPort: Int,
        live: Boolean,
        hasAudio: Boolean = true,
    ): Map<String, String> {
        val txt = LinkedHashMap<String, String>()
        txt["v"] = BspProtocol.VERSION.toString()
        txt["id"] = deviceId
        txt["name"] = displayName
        txt["vc"] = "h264"
        if (hasAudio) txt["ac"] = "aac"
        txt["res"] = "${width}x$height@$fps"
        txt["auth"] = "link"
        txt["link"] = linkPort.toString()
        txt["st"] = if (live) "live" else "idle"
        return clamp(txt)
    }

    /** Garante os limites do DNS-SD: corta valores longos e descarta o excedente do total. */
    fun clamp(txt: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var total = 0
        for ((k, v) in txt) {
            val key = k.filter { it.code in 0x20..0x7E && it != '=' }.take(32)
            if (key.isEmpty()) continue
            val value = truncateUtf8(v.filter { !it.isISOControl() }, MAX_ENTRY_BYTES - key.length - 1)
            val size = key.length + 1 + value.toByteArray(Charsets.UTF_8).size + 1
            if (total + size > MAX_TXT_BYTES) break
            total += size
            out[key] = value
        }
        return out
    }

    fun truncateUtf8(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val sb = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + n > maxBytes) break
            sb.appendCodePoint(cp)
            bytes += n
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
