package com.bragastudio.mobile.core.recording

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Funções puras de formatação/normalização dos metadados de gravação (M7/M39).
 *
 * Convenções do [com.bragastudio.mobile.core.database.RecordingEntity] (escritas pelo
 * RecordManager): resolução como rótulo ("1080p", "1440p", "4K") ou "LxA"; codec "H.264"/"H.265";
 * bitrate em Mbps inteiros. Estas funções aceitam também as variações (bps, mime, "1920x1080")
 * para que a leitura seja tolerante a registros antigos ou importados.
 */
object RecordingFormat {

    /** Extensões de vídeo aceitas na importação de arquivos do disco. */
    val VIDEO_EXTENSIONS: Set<String> = setOf("mp4", "mov", "mkv")

    fun isVideoFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in VIDEO_EXTENSIONS

    /** "mm:ss" abaixo de 1 h; "h:mm:ss" a partir de 1 h. Valores negativos viram zero. */
    fun formatDuration(durationMs: Long): String {
        val totalSec = max(0L, durationMs) / 1000
        val h = totalSec / 3600
        val m = (totalSec / 60) % 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    /** Rótulo de resolução a partir das dimensões reais (usa o lado menor: vale para retrato). */
    fun resolutionFromSize(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return UNKNOWN
        val shortSide = min(width, height)
        val longSide = max(width, height)
        return when {
            longSide >= 3800 || shortSide >= 2100 -> "4K"
            shortSide >= 1400 -> "1440p"
            shortSide >= 1000 -> "1080p"
            shortSide >= 700 -> "720p"
            else -> "${shortSide}p"
        }
    }

    /**
     * Normaliza o texto salvo no banco para um rótulo exibível. Aceita "1080p"/"4K" (devolve
     * como está) e "1920x1080" (converte). Vazio vira [UNKNOWN].
     */
    fun resolutionLabel(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return UNKNOWN
        val parts = text.lowercase(Locale.ROOT).split('x')
        if (parts.size == 2) {
            val w = parts[0].trim().toIntOrNull()
            val h = parts[1].trim().toIntOrNull()
            if (w != null && h != null) return resolutionFromSize(w, h)
        }
        return text
    }

    /** "H.264"/"H.265" a partir de qualquer grafia comum (mime, hevc, avc, h264...). */
    fun codecLabel(raw: String?): String {
        val t = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            t.isEmpty() -> UNKNOWN
            "hevc" in t || "265" in t -> "H.265"
            "avc" in t || "264" in t -> "H.264"
            "av01" in t || t == "av1" -> "AV1"
            "vp9" in t -> "VP9"
            else -> raw!!.trim()
        }
    }

    /** Converte o mime de uma trilha de vídeo (video/hevc, video/avc...) no rótulo do codec. */
    fun codecFromMime(mime: String?): String = codecLabel(mime)

    fun audioCodecFromMime(mime: String?): String {
        val t = mime?.lowercase(Locale.ROOT).orEmpty()
        return when {
            t.isEmpty() -> UNKNOWN
            "mp4a" in t || "aac" in t -> "AAC"
            "opus" in t -> "Opus"
            "mpeg" in t -> "MP3"
            "raw" in t -> "PCM"
            else -> t.substringAfter('/').uppercase(Locale.ROOT)
        }
    }

    /**
     * Normaliza o bitrate para Mbps. O RecordManager grava Mbps (valores pequenos); registros
     * importados ou futuros podem trazer bps. Heurística: acima de 1.000 é bps.
     */
    fun bitrateToMbps(bitrate: Int): Int = if (bitrate > BPS_THRESHOLD) (bitrate / 1_000_000.0).roundToInt() else max(0, bitrate)

    fun bitrateLabel(bitrate: Int): String {
        val mbps = bitrateToMbps(bitrate)
        return if (mbps > 0) "$mbps Mbps" else UNKNOWN
    }

    fun fpsLabel(fps: Int): String = if (fps > 0) "${fps}fps" else UNKNOWN

    /** Bitrate médio em Mbps a partir de tamanho e duração (para arquivos importados). */
    fun averageMbps(sizeBytes: Long, durationMs: Long): Int {
        if (sizeBytes <= 0 || durationMs <= 0) return 0
        val bps = sizeBytes * 8.0 / (durationMs / 1000.0)
        return (bps / 1_000_000.0).roundToInt()
    }

    /** FPS a partir da contagem de quadros e da duração. 0 se não for possível calcular. */
    fun fpsFromFrameCount(frameCount: Long, durationMs: Long): Int {
        if (frameCount <= 0 || durationMs <= 0) return 0
        return (frameCount * 1000.0 / durationMs).roundToInt()
    }

    const val UNKNOWN = "—"
    private const val BPS_THRESHOLD = 1000
}
