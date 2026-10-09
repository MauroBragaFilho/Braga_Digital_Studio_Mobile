package com.bragastudio.mobile.coremedia.bsp

import java.io.IOException
import java.io.InputStream
import java.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Códigos de erro do controle (4.3). */
object BspError {
    const val AUTH = "AUTH"
    const val BUSY = "BUSY"
    const val VERSION = "VERSION"
    const val CODEC = "CODEC"
    const val FORMAT = "FORMAT"
    const val INTERNAL = "INTERNAL"
    const val RATE = "RATE"
}

/** Linha do controle maior que [BspLimits.MAX_LINE_BYTES] (4): erro e fechamento. */
class BspLineTooLongException : IOException("linha de controle acima do limite")

/**
 * Leitor de linhas (`\n`) com TETO de tamanho. O `BufferedReader.readLine` não tem limite: um par
 * mal-intencionado poderia mandar gigabytes sem quebra de linha. Aqui o buffer é fixo e uma linha
 * maior que [maxLine] lança [BspLineTooLongException]. Um `SocketTimeoutException` do stream não
 * perde nada do que já foi lido: a leitura continua da próxima chamada.
 */
class BoundedLineReader(private val input: InputStream, private val maxLine: Int = BspLimits.MAX_LINE_BYTES) {
    private val chunk = ByteArray(1024)
    private var chunkPos = 0
    private var chunkLen = 0
    private val line = ByteArray(maxLine)
    private var lineLen = 0

    /** Próxima linha em UTF-8 (sem o `\n` e sem `\r` final), ou null no fim do stream. */
    fun readLine(): String? {
        while (true) {
            if (chunkPos >= chunkLen) {
                val n = input.read(chunk, 0, chunk.size)
                if (n < 0) return null
                chunkPos = 0
                chunkLen = n
                if (n == 0) continue
            }
            while (chunkPos < chunkLen) {
                val b = chunk[chunkPos++]
                if (b == '\n'.code.toByte()) {
                    var end = lineLen
                    if (end > 0 && line[end - 1] == '\r'.code.toByte()) end--
                    val s = String(line, 0, end, Charsets.UTF_8)
                    lineLen = 0
                    return s
                }
                // estourou o teto: o chamador responde ERROR e fecha a conexão (não há como resincronizar)
                if (lineLen >= maxLine) throw BspLineTooLongException()
                line[lineLen++] = b
            }
        }
    }
}

/** HELLO do receptor já validado (limites de tamanho, base64 e tipos). */
class BspHello(
    val clientId: String,
    val clientName: String,
    /** Os 16 bytes do `nonceR`. */
    val nonceR: ByteArray,
    val videoCodecs: List<String>,
    /** null = o receptor não declarou `caps.audio` (assume AAC). */
    val audioCodecs: List<String>?,
    /** null = o receptor não declarou `caps.aead`. */
    val aead: Boolean?,
    val udpPort: Int?,
    val proof: ByteArray,
)

/** Resultado da leitura de um HELLO. */
sealed class HelloParse {
    class Ok(val hello: BspHello) : HelloParse()

    /** HELLO recusado: [code] vai no `ERROR`; [supportedVersions] só em `VERSION`. */
    class Rejected(val code: String, val message: String, val supportedVersions: List<Int>? = null) : HelloParse()
}

/** Parâmetros que a fonte escolheu (vão no WELCOME). Valores não nulos são obrigatórios; sps/pps podem faltar até o encoder produzi-los. */
class BspVideoParams(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val ssrc: Int,
    val payloadType: Int,
    val sps: ByteArray?,
    val pps: ByteArray?,
    val orientation: Int,
)

class BspAudioParams(
    val rate: Int,
    val channels: Int,
    val ssrc: Int,
    val payloadType: Int,
    val config: ByteArray,
)

class BspNetParams(
    val mtu: Int,
    val latencyMs: Int,
    val fecK: Int,
    val nack: Boolean,
    val feedbackPort: Int,
    val aead: Boolean,
)

/** Metadados lentos da fonte (4.2). */
data class BspMeta(
    val orientation: Int = 0,
    val lens: String = "",
    val battery: Int = -1,
    val rec: Boolean = false,
    val thermal: Int = 0,
)

/** Fornece os metadados lentos da fonte (lente, REC, orientação); bateria e térmico são lidos pelo gerente. */
fun interface BspMetaSource {
    fun current(): BspMeta
}

/**
 * Mensagens do controle BSP v2: montagem e leitura com limites (org.json, escape correto).
 * Campos desconhecidos são ignorados (4). Nada daqui registra em log.
 */
object BspProtocol {
    const val VERSION = 2
    const val AUTH_SCHEME = "link-token"
    const val NONCE_LEN = 16
    private const val MAX_FIELD = 128
    private const val MAX_LIST = 16

    private val b64 = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    fun encode(bytes: ByteArray): String = b64.encodeToString(bytes)

    /** Decodifica base64 padrão; null se inválido ou se passar de [maxBytes]. */
    fun decode(text: String?, maxBytes: Int): ByteArray? {
        if (text == null || text.length > (maxBytes + 2) / 3 * 4 + 4) return null
        return try {
            b64d.decode(text).takeIf { it.size <= maxBytes }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** Lê uma linha como objeto JSON; null se malformada ou se não for um objeto. */
    fun parseLine(line: String): JSONObject? {
        if (line.isBlank()) return null
        return try {
            JSONObject(line)
        } catch (_: JSONException) {
            null
        }
    }

    fun typeOf(msg: JSONObject): String = msg.optString("type", "").take(32)

    /** Valida o HELLO. Tudo que não for versão incompatível vira `AUTH` (não vaza o que faltou antes de autenticar). */
    fun parseHello(msg: JSONObject): HelloParse {
        val version = msg.optInt("protocolVersion", -1)
        if (version != VERSION) {
            return HelloParse.Rejected(BspError.VERSION, "versão não suportada; suportadas: $VERSION", listOf(VERSION))
        }
        val invalid = HelloParse.Rejected(BspError.AUTH, "autenticação inválida")
        val clientId = msg.optString("clientId", "")
        if (clientId.isEmpty() || clientId.length > BspLimits.MAX_CLIENT_ID) return invalid
        val clientName = msg.optString("clientName", "").filter { !it.isISOControl() }.trim().take(BspLimits.MAX_CLIENT_NAME)
        val nonce = decode(msg.optString("nonce", null), NONCE_LEN)
        if (nonce == null || nonce.size != NONCE_LEN) return invalid
        val auth = msg.optJSONObject("auth") ?: return invalid
        if (auth.optString("scheme", "") != AUTH_SCHEME) return invalid
        val proof = decode(auth.optString("proof", null), BspCrypto.HASH_LEN)
        if (proof == null || proof.size != BspCrypto.HASH_LEN) return invalid

        val caps = msg.optJSONObject("caps")
        val video = stringList(caps?.optJSONArray("video"))
        val audio = if (caps?.has("audio") == true) stringList(caps.optJSONArray("audio")) else null
        val aead = if (caps?.has("aead") == true) caps.optBoolean("aead", true) else null
        val udpPort = msg.optInt("udpPort", -1).takeIf { it in MIN_UDP_PORT..MAX_UDP_PORT }
        return HelloParse.Ok(BspHello(clientId, clientName, nonce, video, audio, aead, udpPort, proof))
    }

    const val MIN_UDP_PORT = 1024
    const val MAX_UDP_PORT = 65535

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>(minOf(array.length(), MAX_LIST))
        for (i in 0 until minOf(array.length(), MAX_LIST)) {
            val s = array.optString(i, "")
            if (s.isNotEmpty()) out += s.take(MAX_FIELD).lowercase()
        }
        return out
    }

    /** Porta UDP do `READY`, ou null se faltar ou estiver fora de 1024..65535. */
    fun readyPort(msg: JSONObject): Int? = msg.optInt("udpPort", -1).takeIf { it in MIN_UDP_PORT..MAX_UDP_PORT }

    // ---- Montagem ---------------------------------------------------------------------

    fun error(code: String, message: String, supportedVersions: List<Int>? = null): String {
        val o = JSONObject().put("type", "ERROR").put("code", code).put("message", message)
        if (supportedVersions != null) {
            val arr = JSONArray()
            supportedVersions.forEach { arr.put(it) }
            o.put("supported", arr)
        }
        return o.toString()
    }

    fun welcome(
        sessionId: Int,
        deviceName: String,
        nonceS: ByteArray,
        video: BspVideoParams,
        audio: BspAudioParams?,
        net: BspNetParams,
        keyframeIntervalMs: Int,
        sourceProof: ByteArray,
    ): String {
        val v = JSONObject()
            .put("codec", "h264")
            .put("width", video.width)
            .put("height", video.height)
            .put("fps", video.fps)
            .put("bitrateKbps", video.bitrateKbps)
            .put("ssrc", video.ssrc.toLong() and 0xFFFFFFFFL)
            .put("pt", video.payloadType)
            .put("orientation", video.orientation)
        // SPS/PPS só existem depois que o encoder os entrega (a câmera pode ainda não ter produzido quadro);
        // quando faltam, o receptor os recebe no fluxo (STAP-A antes de cada IDR, 5.2).
        video.sps?.let { v.put("sps", encode(it)) }
        video.pps?.let { v.put("pps", encode(it)) }
        val o = JSONObject()
            .put("type", "WELCOME")
            .put("protocolVersion", VERSION)
            .put("sessionId", sessionId.toLong() and 0xFFFFFFFFL)
            .put("deviceName", deviceName)
            .put("nonce", encode(nonceS))
            .put("video", v)
        if (audio != null) {
            o.put(
                "audio",
                JSONObject()
                    .put("codec", "aac")
                    .put("rate", audio.rate)
                    .put("channels", audio.channels)
                    .put("ssrc", audio.ssrc.toLong() and 0xFFFFFFFFL)
                    .put("pt", audio.payloadType)
                    .put("config", encode(audio.config)),
            )
        }
        o.put(
            "net",
            JSONObject()
                .put("mtu", net.mtu)
                .put("latencyMs", net.latencyMs)
                .put("fecK", net.fecK)
                .put("nack", net.nack)
                .put("feedbackPort", net.feedbackPort)
                .put("aead", net.aead),
        )
        o.put("keyframeIntervalMs", keyframeIntervalMs)
        o.put("auth", JSONObject().put("proof", encode(sourceProof)))
        return o.toString()
    }

    fun heartbeat(id: Long, tsMs: Long): String = JSONObject().put("type", "HEARTBEAT").put("id", id).put("ts", tsMs).toString()

    fun heartbeatAck(id: Long, tsMs: Long): String = JSONObject().put("type", "HEARTBEAT_ACK").put("id", id).put("ts", tsMs).toString()

    fun start(): String = JSONObject().put("type", "START").toString()

    fun stop(reason: String): String = JSONObject().put("type", "STOP").put("reason", reason).toString()

    fun bye(): String = JSONObject().put("type", "BYE").toString()

    fun meta(meta: BspMeta): String = JSONObject()
        .put("type", "META")
        .put("orientation", meta.orientation)
        .put("lens", meta.lens)
        .put("battery", meta.battery)
        .put("rec", meta.rec)
        .put("thermal", meta.thermal)
        .toString()

    /** Relatório de remetente (mapeamento NTP <-> RTP, 6.1) enviado pelo controle a cada 1 s. */
    fun senderReport(ntpSec: Long, ntpFrac: Long, video: BspStreamReport, audio: BspStreamReport?): String {
        val o = JSONObject().put("type", "SR").put("ntpSec", ntpSec).put("ntpFrac", ntpFrac).put("video", video.toJson())
        if (audio != null) o.put("audio", audio.toJson())
        return o.toString()
    }
}

/** Pedaço do SR de um fluxo: o RTP timestamp correspondente ao NTP do relatório mais as contagens. */
class BspStreamReport(val ssrc: Int, val rtpTimestamp: Long, val packets: Long, val octets: Long) {
    fun toJson(): JSONObject = JSONObject()
        .put("ssrc", ssrc.toLong() and 0xFFFFFFFFL)
        .put("rtp", rtpTimestamp and 0xFFFFFFFFL)
        .put("packets", packets)
        .put("octets", octets)
}

/** Conversões de tempo NTP (RFC 5905, segundos desde 1900) usadas no SR. */
object BspNtp {
    private const val UNIX_TO_NTP_SECONDS = 2_208_988_800L

    fun seconds(unixMs: Long): Long = Math.floorDiv(unixMs, 1000L) + UNIX_TO_NTP_SECONDS

    /** Parte fracionária de 32 bits (1/2^32 s) do milissegundo corrente. */
    fun fraction(unixMs: Long): Long = Math.floorMod(unixMs, 1000L) * (1L shl 32) / 1000L
}
