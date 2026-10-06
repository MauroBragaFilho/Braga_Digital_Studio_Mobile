package com.bragastudio.mobile.featurepreview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// ============================================================================
// MEDIDOR DE ÁUDIO (VU) — M29 + M32
// ============================================================================
// Zonas de cor no estilo "LED ladder" (Sony/profissional), agora calibradas em
// dBFS reais (ver dbToFraction em HudFormatters.kt):
//   verde até -12 dBFS, amarelo até -3 dBFS, vermelho de -3 a 0 dBFS (clip).
private val VuGreen = Color(0xFF00E676)
private val VuYellow = Color(0xFFFFD600)
private val VuRed = Color(0xFFFF1744)

/** Intervalo de amostragem do medidor (a leitura dos providers acontece fora da composição). */
private const val VU_SAMPLE_INTERVAL_MS = 40L

/** Pico a partir do qual uma amostra conta como clip (0 dBFS). */
const val VU_CLIP_THRESHOLD = 0.999f

/** Amostras consecutivas em clip para acender o indicador. */
const val VU_CLIP_SAMPLES = 3

/** Tempo que o marcador de pico fica retido. */
const val VU_PEAK_HOLD_MS = 1000L

/** Tempo que o indicador de clip permanece aceso após a última amostra em clip. */
const val VU_CLIP_HOLD_MS = 2000L

/**
 * Modelo puro (sem Compose) de um canal do VU: nível da barra, marcador de pico
 * retido por ~1 s e clip por pico >= 0,999 em [clipSamples] amostras
 * consecutivas. O tempo entra por parâmetro para ser testável.
 *
 * "Amostra" aqui é uma leitura do provider a cada [VU_SAMPLE_INTERVAL_MS]: o
 * StateFlow de origem não reemite valores iguais, então contar emissões nunca
 * detectaria um clip sustentado.
 */
class VuMeterModel(
    private val peakHoldMs: Long = VU_PEAK_HOLD_MS,
    private val clipHoldMs: Long = VU_CLIP_HOLD_MS,
    private val clipSamples: Int = VU_CLIP_SAMPLES,
) {
    var level: Float = 0f
        private set
    var peakHold: Float = 0f
        private set
    var isClipping: Boolean = false
        private set

    private var clipRun = 0
    private var peakHeldAtMs = 0L
    private var clipUntilMs = 0L

    /**
     * @param levelSample nível da barra (fração 0..1; hoje RMS)
     * @param peakSample pico do canal (fração 0..1); quando não há medição de pico,
     *   passe o mesmo valor de [levelSample].
     */
    fun update(levelSample: Float, peakSample: Float, nowMs: Long) {
        level = normalizeAudioLevel(levelSample)
        val peak = max(normalizeAudioLevel(peakSample), level)

        // Peak-hold: sobe na hora; depois de peakHoldMs sem superar, cai para o pico atual.
        if (peak >= peakHold || nowMs - peakHeldAtMs >= peakHoldMs) {
            peakHold = peak
            peakHeldAtMs = nowMs
        }

        // Clip: pico >= 0,999 por clipSamples amostras seguidas; fica aceso clipHoldMs.
        if (peak >= VU_CLIP_THRESHOLD) clipRun++ else clipRun = 0
        if (clipRun >= clipSamples) {
            isClipping = true
            clipUntilMs = nowMs + clipHoldMs
        } else if (isClipping && nowMs >= clipUntilMs) {
            isClipping = false
        }
    }
}

/** Estado observável de um canal; lido só na fase de desenho (Canvas), sem recompor. */
@Stable
internal class VuChannelUi {
    var level by mutableFloatStateOf(0f)
    var peak by mutableFloatStateOf(0f)
    var clipping by mutableStateOf(false)

    fun sync(model: VuMeterModel) {
        level = model.level
        peak = model.peakHold
        clipping = model.isClipping
    }
}

/**
 * Medidor estéreo. Recebe PROVIDERS (`() -> Float`) e os lê numa coroutine
 * (25 Hz), nunca na composição: o HUD pai não recompõe a cada amostra de áudio.
 *
 * @param peakLeft/peakRight picos por canal (quando a captura os expuser); se
 *   nulos, o nível atual é tratado como pico.
 */
@Composable
fun AudioMetersOverlay(
    audioLevelLeft: () -> Float,
    audioLevelRight: () -> Float,
    modifier: Modifier = Modifier,
    peakLeft: (() -> Float)? = null,
    peakRight: (() -> Float)? = null,
) {
    val left = remember { VuChannelUi() }
    val right = remember { VuChannelUi() }
    val levelLeftProvider by rememberUpdatedState(audioLevelLeft)
    val levelRightProvider by rememberUpdatedState(audioLevelRight)
    val peakLeftProvider by rememberUpdatedState(peakLeft)
    val peakRightProvider by rememberUpdatedState(peakRight)

    LaunchedEffect(Unit) {
        val leftModel = VuMeterModel()
        val rightModel = VuMeterModel()
        while (isActive) {
            val now = System.nanoTime() / 1_000_000L
            val l = levelLeftProvider()
            val r = levelRightProvider()
            leftModel.update(l, peakLeftProvider?.invoke() ?: l, now)
            rightModel.update(r, peakRightProvider?.invoke() ?: r, now)
            left.sync(leftModel)
            right.sync(rightModel)
            delay(VU_SAMPLE_INTERVAL_MS)
        }
    }

    Column(
        modifier = modifier
            .width(190.dp)
            .semantics { contentDescription = "Medidor de áudio estéreo, escala de menos 60 a 0 dBFS" },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        AudioMeterChannel(ui = left, label = "L")
        AudioMeterChannel(ui = right, label = "R")
    }
}

/**
 * Um canal do VU, discreto: barra FINA (4 dp) com zonas verde/amarelo/vermelho até
 * o nível atual, marcador de pico retido, marcas em -12/-3 dBFS e ponto de clip no
 * fim — tudo num Canvas, lido só na fase de desenho (não recompõe).
 */
@Composable
internal fun AudioMeterChannel(ui: VuChannelUi, label: String) {
    val isClipping = ui.clipping // muda raramente: recomposição só do rótulo

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HudTheme.spacingSmall),
    ) {
        Text(
            text = label,
            color = if (isClipping) VuRed else HudTheme.textColorSecondary,
            fontSize = HudTheme.fontSizeMin,
            lineHeight = HudTheme.fontSizeMin,
            fontWeight = if (isClipping) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(12.dp),
        )

        Canvas(modifier = Modifier.weight(1f).height(8.dp)) {
            val barH = 4.dp.toPx()
            val top = 0f
            val clipDot = 6.dp.toPx()
            val barW = size.width - clipDot - 4.dp.toPx()
            val clip = ui.clipping
            val level = if (clip) 1f else ui.level.coerceIn(0f, 1f)
            val radius = CornerRadius(barH / 2f)

            // Trilho
            drawRoundRect(Color.White.copy(alpha = 0.14f), Offset(0f, top), Size(barW, barH), radius)
            // Zonas preenchidas até o nível
            val zones = floatArrayOf(0f, VU_GREEN_END_FRACTION, VU_YELLOW_END_FRACTION, 1f)
            val colors = arrayOf(VuGreen, VuYellow, VuRed)
            for (z in 0 until 3) {
                val from = zones[z]
                val to = minOf(zones[z + 1], level)
                if (to > from) {
                    drawRoundRect(
                        colors[z],
                        Offset(from * barW, top),
                        Size((to - from) * barW, barH),
                        if (z == 0) radius else CornerRadius(0f),
                    )
                }
            }
            // Pico retido
            val peak = ui.peak.coerceIn(0f, 1f)
            if (peak > 0f) {
                val x = (peak * barW).coerceIn(1.dp.toPx(), barW - 1.dp.toPx())
                drawLine(Color.White, Offset(x, top - 1.dp.toPx()), Offset(x, top + barH + 1.dp.toPx()), 2.dp.toPx())
            }
            // Marcas -12 e -3 dBFS
            for (f in floatArrayOf(VU_GREEN_END_FRACTION, VU_YELLOW_END_FRACTION)) {
                val x = f * barW
                drawLine(Color.White.copy(alpha = 0.5f), Offset(x, top + barH), Offset(x, size.height), 1.dp.toPx())
            }
            // Clip
            drawCircle(
                color = if (clip) VuRed else Color.White.copy(alpha = 0.18f),
                radius = clipDot / 2f,
                center = Offset(size.width - clipDot / 2f, top + barH / 2f),
            )
        }
    }
}
