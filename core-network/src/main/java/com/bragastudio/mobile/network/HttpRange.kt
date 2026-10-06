package com.bragastudio.mobile.network

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteWriteChannel
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resultado da interpretação de um cabeçalho `Range`. */
internal sealed class RangeResult {
    /** Sem Range (ou Range não suportado/inválido): responde o arquivo inteiro com 200. */
    object Full : RangeResult()

    /** Intervalo [start, endInclusive] satisfazível: responde 206. */
    data class Partial(val start: Long, val endInclusive: Long) : RangeResult()

    /** Intervalo fora do arquivo: responde 416. */
    object Unsatisfiable : RangeResult()
}

/**
 * Interpreta `Range: bytes=...` (RFC 7233) para um arquivo de [length] bytes. Só um
 * intervalo por requisição é atendido; vários intervalos ou sintaxe inválida resultam
 * em [RangeResult.Full] (o servidor tem o direito de ignorar o Range).
 */
internal fun parseRange(header: String?, length: Long): RangeResult {
    if (header.isNullOrBlank()) return RangeResult.Full
    val h = header.trim()
    if (!h.startsWith("bytes=", ignoreCase = true)) return RangeResult.Full
    val spec = h.substring(6).trim()
    if (spec.isEmpty() || spec.contains(',')) return RangeResult.Full
    val dash = spec.indexOf('-')
    if (dash < 0) return RangeResult.Full
    val first = spec.substring(0, dash).trim()
    val last = spec.substring(dash + 1).trim()

    if (first.isEmpty()) {
        // Sufixo: "-N" = últimos N bytes.
        val n = last.toLongOrNull() ?: return RangeResult.Full
        if (n <= 0 || length == 0L) return RangeResult.Unsatisfiable
        return RangeResult.Partial(maxOf(0L, length - n), length - 1)
    }
    val start = first.toLongOrNull() ?: return RangeResult.Full
    if (start < 0) return RangeResult.Full
    val end = if (last.isEmpty()) length - 1 else (last.toLongOrNull() ?: return RangeResult.Full)
    if (start >= length) return RangeResult.Unsatisfiable
    if (end < start) return RangeResult.Full
    return RangeResult.Partial(start, minOf(end, length - 1))
}

private fun contentTypeFor(file: File): ContentType = when (file.extension.lowercase(Locale.ROOT)) {
    "mp4", "m4v" -> ContentType.parse("video/mp4")
    "mov" -> ContentType.parse("video/quicktime")
    "mkv" -> ContentType.parse("video/x-matroska")
    "jpg", "jpeg" -> ContentType.Image.JPEG
    "png" -> ContentType.Image.PNG
    else -> ContentType.Application.OctetStream
}

/** Conteúdo que transmite o intervalo [start, endInclusive] de um arquivo, em blocos. */
private class FileRangeContent(
    private val file: File,
    private val start: Long,
    private val endInclusive: Long,
    private val fileLength: Long,
    private val type: ContentType,
    override val status: HttpStatusCode,
    private val onFinished: (() -> Unit)?,
) : OutgoingContent.WriteChannelContent() {

    override val contentLength: Long = endInclusive - start + 1
    override val contentType: ContentType = type

    override suspend fun writeTo(channel: ByteWriteChannel) {
        withContext(Dispatchers.IO) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(256 * 1024)
                var remaining = contentLength
                while (remaining > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) throw IOException("Arquivo mudou durante o download")
                    channel.writeFully(buf, 0, n)
                    remaining -= n
                }
            }
        }
        channel.flush()
        // "Concluído" = entregou até o último byte (também vale para o fim de uma retomada).
        if (endInclusive == fileLength - 1) onFinished?.invoke()
    }
}

/**
 * Responde [file] com suporte a `Range` (retomada de downloads de GB). [onFinished] só
 * roda quando o último byte foi realmente escrito, sem erro de conexão.
 */
internal suspend fun ApplicationCall.respondFileWithRange(file: File, onFinished: (() -> Unit)? = null) {
    val length = file.length()
    response.header(HttpHeaders.AcceptRanges, "bytes")
    val type = contentTypeFor(file)
    when (val range = parseRange(request.header(HttpHeaders.Range), length)) {
        RangeResult.Unsatisfiable -> {
            response.header(HttpHeaders.ContentRange, "bytes */$length")
            respond(HttpStatusCode.RequestedRangeNotSatisfiable)
        }

        RangeResult.Full -> {
            if (length == 0L) {
                respond(HttpStatusCode.OK, ByteArray(0))
            } else {
                respond(FileRangeContent(file, 0, length - 1, length, type, HttpStatusCode.OK, onFinished))
            }
        }

        is RangeResult.Partial -> {
            response.header(HttpHeaders.ContentRange, "bytes ${range.start}-${range.endInclusive}/$length")
            respond(FileRangeContent(file, range.start, range.endInclusive, length, type, HttpStatusCode.PartialContent, onFinished))
        }
    }
}
