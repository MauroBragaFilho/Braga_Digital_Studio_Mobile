package com.bragastudio.mobile.featuresettings

import com.bragastudio.mobile.core.recording.RecordingGroup
import com.bragastudio.mobile.core.recording.RecordingItem
import com.bragastudio.mobile.coremedia.domain.LutParser
import java.text.Normalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Regras puras (sem Android) da UX v2: presets de qualidade, busca de ajustes, ordenação de
// gravações e pré-visualização de LUT. Todas testadas em JVM.

/**
 * Preset de qualidade de gravação. Cada preset fixa resolução, fps, codec e bitrate; qualquer
 * combinação diferente é "Personalizada" (as opções avançadas continuam editáveis).
 */
enum class QualityPreset(
    val resolution: Resolution,
    val fps: Fps,
    val codec: Codec,
    val bitrateMbps: Int,
) {
    /** Arquivos menores: 1080p, H.265, 25 Mbps. */
    ECONOMIC(Resolution.P1080, Fps.F30, Codec.H265, 25),

    /** Padrão do app: 1080p, H.264 (abre em qualquer lugar), 50 Mbps. */
    BALANCED(Resolution.P1080, Fps.F30, Codec.H264, 50),

    /** Melhor imagem: 4K, H.265, 100 Mbps. */
    MAX(Resolution.UHD4K, Fps.F30, Codec.H265, 100),
    ;

    /** "1080p · 30 fps" (resolução e quadros por segundo, o que a pessoa reconhece). */
    val shortFormat: String get() = QualityText.format(resolution, fps.value)

    companion object {
        /** Preset que coincide exatamente com a configuração atual, ou null (personalizada). */
        fun detect(resolution: Resolution, fps: Int, codec: Codec, bitrateMbps: Int): QualityPreset? = entries.firstOrNull {
            it.resolution == resolution && it.fps.value == fps && it.codec == codec && it.bitrateMbps == bitrateMbps
        }
    }
}

object QualityText {
    /** "4K · 30 fps". */
    fun format(resolution: Resolution, fps: Int): String = "${resolution.persisted} · $fps fps"

    /** Megabytes por minuto de vídeo para um bitrate em Mbps (1 Mbps = 7,5 MB/min). */
    fun mbPerMinute(bitrateMbps: Int): Int = (bitrateMbps * 60 / 8f).toInt()
}

/**
 * "Apagar com desfazer": os itens ficam só ESCONDIDOS da lista enquanto o aviso de desfazer está na
 * tela; a exclusão real (arquivo + registro) só acontece depois ([release] após apagar de verdade).
 * Se o app morrer antes, nada foi apagado (o padrão seguro).
 */
class PendingRemoval {
    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    fun hide(ids: Collection<String>) = _hidden.update { it + ids }

    /** Usuário tocou em Desfazer: os itens voltam à lista. */
    fun restore(ids: Collection<String>) = _hidden.update { it - ids.toSet() }

    /** A exclusão real terminou: não precisa mais esconder (o item já saiu da fonte de dados). */
    fun release(ids: Collection<String>) = restore(ids)

    fun <T> filterVisible(items: List<T>, idOf: (T) -> String): List<T> {
        val h = _hidden.value
        return if (h.isEmpty()) items else items.filter { idOf(it) !in h }
    }
}

/** Situação da transmissão NDI, em palavras que a pessoa entende. */
enum class NdiPhase { OFF, NO_NETWORK, WAITING_RECEIVER, LIVE }

object NdiStatus {
    fun phase(enabled: Boolean, hasNetwork: Boolean, connections: Int): NdiPhase = when {
        !enabled -> NdiPhase.OFF
        !hasNetwork -> NdiPhase.NO_NETWORK
        connections > 0 -> NdiPhase.LIVE
        else -> NdiPhase.WAITING_RECEIVER
    }

    /** Nome que OBS/vMix mostram: máquina fixa "BDSM" + nome do sender entre parênteses. */
    fun sourceLabel(streamName: String): String = "BDSM (${streamName.ifBlank { "…" }})"
}

/** Estados da tela simples de NDI (UX v3): desativado, iniciando, ativo ou com erro. */
enum class NdiScreenPhase { OFF, STARTING, ACTIVE, ERROR }

object NdiScreenLogic {
    /** Tempo que o envio pode levar para iniciar antes de a tela oferecer "Tentar novamente". */
    const val START_TIMEOUT_MS = 8_000L

    /**
     * [enabled] = o usuário ligou a transmissão; [active] = o sender NDI está de fato no ar;
     * [failed] = o motor reportou erro ou estourou o tempo de início.
     */
    fun phase(enabled: Boolean, active: Boolean, failed: Boolean): NdiScreenPhase = when {
        !enabled -> NdiScreenPhase.OFF
        active -> NdiScreenPhase.ACTIVE
        failed -> NdiScreenPhase.ERROR
        else -> NdiScreenPhase.STARTING
    }
}

/** Busca nos ajustes: ignora maiúsculas e acentos ("camera" encontra "Câmera"). */
object SettingsSearch {
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()
        .trim()

    /** Todas as palavras da busca precisam aparecer em algum dos [haystacks]. Busca vazia casa tudo. */
    fun matches(query: String, vararg haystacks: String): Boolean {
        val terms = normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        val text = normalize(haystacks.joinToString(" "))
        return terms.all { text.contains(it) }
    }
}

/** Ordenação da galeria de gravações. */
enum class RecordingSort { NEWEST, OLDEST, LARGEST }

object RecordingSorting {
    const val SIZE_GROUP_LABEL = "Por tamanho"

    /**
     * Reagrupa o resultado do [com.bragastudio.mobile.core.recording.RecordingListBuilder]
     * (que já vem por dia, mais recente primeiro ou mais antigo primeiro). "Maiores primeiro"
     * vira uma lista única, sem grupos de data, ordenada por tamanho decrescente (empate: mais nova).
     */
    fun arrange(groups: List<RecordingGroup>, sort: RecordingSort): List<RecordingGroup> {
        if (sort != RecordingSort.LARGEST) return groups
        val all = groups.flatMap { it.items }
            .sortedWith(compareByDescending<RecordingItem> { it.sizeBytes }.thenByDescending { it.createdAt })
        return if (all.isEmpty()) emptyList() else listOf(RecordingGroup(SIZE_GROUP_LABEL, all))
    }

    /** O builder só conhece "mais nova primeiro" ou "mais antiga primeiro". */
    fun newestFirstForBuilder(sort: RecordingSort): Boolean = sort != RecordingSort.OLDEST
}

/** Pré-visualização do efeito de uma LUT numa cena de amostra (CPU, miniatura pequena). */
object LutPreview {
    const val WIDTH = 96
    const val HEIGHT = 56

    /**
     * Cena sintética: faixa de cores saturadas (varredura de matiz) em cima, tons de pele no
     * meio e rampa de cinza embaixo. Devolve ARGB.
     */
    fun sampleScene(width: Int = WIDTH, height: Int = HEIGHT): IntArray {
        val out = IntArray(width * height)
        val bandA = height * 2 / 5
        val bandB = height * 3 / 5
        for (y in 0 until height) {
            for (x in 0 until width) {
                val t = x / (width - 1f).coerceAtLeast(1f)
                val rgb: FloatArray = when {
                    y < bandA -> hueToRgb(t, saturation = 0.75f, value = 0.55f + 0.4f * (y / bandA.toFloat()))
                    y < bandB -> skin(t)
                    else -> floatArrayOf(t, t, t)
                }
                out[y * width + x] = pack(rgb[0], rgb[1], rgb[2])
            }
        }
        return out
    }

    /** Aplica a LUT 3D (interpolação trilinear) a cada pixel ARGB. */
    fun apply(lut: LutParser.LutData, argb: IntArray): IntArray {
        val n = lut.size
        val data = lut.floatData
        val max = (n - 1).toFloat()
        val out = IntArray(argb.size)
        fun at(r: Int, g: Int, b: Int, c: Int): Float = data[((b * n + g) * n + r) * 4 + c]
        for (i in argb.indices) {
            val p = argb[i]
            val rf = ((p shr 16) and 0xFF) / 255f * max
            val gf = ((p shr 8) and 0xFF) / 255f * max
            val bf = (p and 0xFF) / 255f * max
            val r0 = rf.toInt().coerceIn(0, n - 1)
            val g0 = gf.toInt().coerceIn(0, n - 1)
            val b0 = bf.toInt().coerceIn(0, n - 1)
            val r1 = (r0 + 1).coerceAtMost(n - 1)
            val g1 = (g0 + 1).coerceAtMost(n - 1)
            val b1 = (b0 + 1).coerceAtMost(n - 1)
            val dr = rf - r0
            val dg = gf - g0
            val db = bf - b0
            val ch = FloatArray(3)
            for (c in 0..2) {
                val c00 = at(r0, g0, b0, c) * (1 - dr) + at(r1, g0, b0, c) * dr
                val c10 = at(r0, g1, b0, c) * (1 - dr) + at(r1, g1, b0, c) * dr
                val c01 = at(r0, g0, b1, c) * (1 - dr) + at(r1, g0, b1, c) * dr
                val c11 = at(r0, g1, b1, c) * (1 - dr) + at(r1, g1, b1, c) * dr
                val c0 = c00 * (1 - dg) + c10 * dg
                val c1 = c01 * (1 - dg) + c11 * dg
                ch[c] = c0 * (1 - db) + c1 * db
            }
            out[i] = pack(ch[0], ch[1], ch[2])
        }
        return out
    }

    private fun skin(t: Float): FloatArray {
        // De tom claro a escuro (aproximação de tons de pele).
        val light = floatArrayOf(0.96f, 0.80f, 0.69f)
        val dark = floatArrayOf(0.36f, 0.22f, 0.15f)
        return floatArrayOf(
            light[0] + (dark[0] - light[0]) * t,
            light[1] + (dark[1] - light[1]) * t,
            light[2] + (dark[2] - light[2]) * t,
        )
    }

    private fun hueToRgb(h: Float, saturation: Float, value: Float): FloatArray {
        val hh = (h.coerceIn(0f, 1f) * 5.999f)
        val i = hh.toInt()
        val f = hh - i
        val p = value * (1 - saturation)
        val q = value * (1 - saturation * f)
        val t = value * (1 - saturation * (1 - f))
        return when (i) {
            0 -> floatArrayOf(value, t, p)
            1 -> floatArrayOf(q, value, p)
            2 -> floatArrayOf(p, value, t)
            3 -> floatArrayOf(p, q, value)
            4 -> floatArrayOf(t, p, value)
            else -> floatArrayOf(value, p, q)
        }
    }

    private fun pack(r: Float, g: Float, b: Float): Int {
        fun c(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
    }
}
