package com.bragastudio.mobile.coremedia.domain

/**
 * Tabela ÚNICA de resolução (M17). Antes existiam duas cópias divergentes:
 * RecordManager ("4K"/"1440p"/else) e MediaGraph (idem + "HD/FHD/QHD/UHD" para o
 * NDI/BSP). Aqui os dois vocabulários caem na mesma tabela; qualquer valor
 * desconhecido vira Full HD (mesmo comportamento anterior).
 */
object ResolutionTable {
    const val DEFAULT_LABEL = "FHD"

    fun resolve(label: String?): Pair<Int, Int> = when (label?.trim()?.uppercase()) {
        "HD", "720P" -> 1280 to 720
        "QHD", "1440P", "2K" -> 2560 to 1440
        "UHD", "4K", "2160P" -> 3840 to 2160
        else -> 1920 to 1080 // FHD / 1080p / desconhecido
    }

    /** Resolução de gravação/captura (VideoSettings.resolution: "1080p", "1440p", "4K"). */
    fun recording(label: String?): Pair<Int, Int> = resolve(label)

    /** Resolução de stream NDI/BSP (preset: "HD", "FHD", "QHD", "UHD"). */
    fun stream(preset: String?): Pair<Int, Int> = resolve(preset)
}

/** Descrição de um encoder de hardware/software capaz de atender o pedido. */
data class EncoderInfo(
    val name: String,
    /** Pares (profile, level) suportados, na codificação de MediaCodecInfo.CodecProfileLevel. */
    val profileLevels: List<Pair<Int, Int>>,
    /** Maior bitrate aceito pelo encoder (bps), ou null se desconhecido. */
    val maxBitrateBps: Int? = null,
)

/** Consulta de capacidades de encoder (implementada com MediaCodecList no Android; fake nos testes). */
interface EncoderProbe {
    /** Devolve um encoder que aceita [mime] em [width]x[height]@[fps], ou null. */
    fun find(mime: String, width: Int, height: Int, fps: Int, hdr10: Boolean): EncoderInfo?
}

/** Resultado do planejamento: tudo que o RecordManager precisa para configurar o MediaFormat. */
data class VideoPlan(
    val mime: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val encoderName: String,
    val profile: Int?,
    val level: Int?,
    /** Avisos para a UI (ex.: "H.265 indisponível; gravando em H.264"). */
    val notices: List<String>,
)

object VideoPlanner {
    const val MIME_AVC = "video/avc"
    const val MIME_HEVC = "video/hevc"

    // Valores de MediaCodecInfo.CodecProfileLevel (copiados para manter este objeto
    // livre de android.jar e testável em JVM).
    const val AVC_PROFILE_BASELINE = 0x01
    const val AVC_PROFILE_MAIN = 0x02
    const val AVC_PROFILE_HIGH = 0x08
    const val HEVC_PROFILE_MAIN = 0x01
    const val HEVC_PROFILE_MAIN10 = 0x02

    /**
     * Escolhe codec/fps que o aparelho realmente suporta.
     * Ordem de tentativa: (codec pedido, fps pedido) → (H.264, fps pedido, se o pedido era H.265)
     * → as mesmas combinações com fps reduzido a 30. Em HDR só HEVC Main10 é aceito.
     * Devolve null se nada serve (o chamador reporta o erro sem criar codec).
     */
    fun plan(
        codecPreference: String,
        resolutionLabel: String,
        fps: Int,
        bitrateMbps: Int,
        hdr: Boolean,
        probe: EncoderProbe,
        portraitFrame: Boolean = false,
    ): VideoPlan? {
        val (tableWidth, tableHeight) = ResolutionTable.recording(resolutionLabel)
        // Quadro retrato (aparelho em pé no início): largura e altura trocadas, fixas até o fim do take.
        val (width, height) = if (portraitFrame) tableHeight to tableWidth else tableWidth to tableHeight
        val wantsHevc = hdr || codecPreference.equals("H.265", ignoreCase = true)
        val mimes = when {
            hdr -> listOf(MIME_HEVC)
            wantsHevc -> listOf(MIME_HEVC, MIME_AVC)
            else -> listOf(MIME_AVC)
        }
        val safeFps = fps.coerceIn(1, 240)
        val fpsCandidates = if (safeFps > 30) listOf(safeFps, 30) else listOf(safeFps)

        for (candidateFps in fpsCandidates) {
            for (mime in mimes) {
                // Capacidade é independente da orientação do quadro: consulta sempre com as dimensões da tabela.
                val info = probe.find(mime, tableWidth, tableHeight, candidateFps, hdr) ?: continue
                val notices = mutableListOf<String>()
                if (wantsHevc && !hdr && mime == MIME_AVC) {
                    notices += "H.265 indisponível em ${width}x$height@$candidateFps neste aparelho; gravando em H.264."
                }
                if (candidateFps != safeFps) {
                    notices += "${width}x$height@$safeFps não suportado; gravando a $candidateFps fps."
                }
                val requestedBps = bitrateMbps.coerceAtLeast(1).toLong() * 1_000_000L
                val maxBps = info.maxBitrateBps?.toLong()
                val bitrate = if (maxBps != null && maxBps > 0 && requestedBps > maxBps) {
                    notices += "Bitrate limitado a ${maxBps / 1_000_000} Mbps pelo encoder."
                    maxBps
                } else {
                    requestedBps
                }
                val (profile, level) = pickProfile(mime, hdr, info.profileLevels)
                return VideoPlan(
                    mime = mime,
                    width = width,
                    height = height,
                    fps = candidateFps,
                    bitrateBps = bitrate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    encoderName = info.name,
                    profile = profile,
                    level = level,
                    notices = notices,
                )
            }
        }
        return null
    }

    /**
     * Escolhe o melhor perfil suportado (e o maior nível desse perfil). Perfil e nível
     * são definidos juntos — informar só um deles faz alguns encoders rejeitarem o configure.
     * Sem nenhum perfil conhecido, devolve (null, null) e o encoder usa o padrão dele.
     */
    fun pickProfile(mime: String, hdr: Boolean, supported: List<Pair<Int, Int>>): Pair<Int?, Int?> {
        val preferred = when {
            mime == MIME_HEVC && hdr -> listOf(HEVC_PROFILE_MAIN10)
            mime == MIME_HEVC -> listOf(HEVC_PROFILE_MAIN)
            else -> listOf(AVC_PROFILE_HIGH, AVC_PROFILE_MAIN, AVC_PROFILE_BASELINE)
        }
        for (profile in preferred) {
            val level = supported.filter { it.first == profile }.maxOfOrNull { it.second }
            if (level != null) return profile to level
        }
        return null to null
    }
}
