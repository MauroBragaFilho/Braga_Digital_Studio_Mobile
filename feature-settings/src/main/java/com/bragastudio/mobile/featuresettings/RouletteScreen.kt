package com.bragastudio.mobile.featuresettings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.bragastudio.mobile.common.components.BdsmBigButton
import com.bragastudio.mobile.common.components.BdsmCard
import com.bragastudio.mobile.common.components.BdsmScreen
import com.bragastudio.mobile.common.components.rememberBdsmHaptics
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import kotlinx.coroutines.launch

data class RoulettePrize(val title: String, val subtitle: String = "", val color: Color = Color.Unspecified, val effect: String = "")

/** Prêmio sorteado: título/subtítulo e a frase de efeito (sem aspas de enfeite). */
private data class RouletteResult(val title: String, val effect: String, val mystery: Boolean)

/** Índice da fatia sob o ponteiro (topo) para um ângulo de rotação acumulado; 0 <= resultado < slices. */
internal fun rouletteSliceIndex(rotationDegrees: Float, slices: Int): Int {
    val finalAngle = (rotationDegrees % 360f + 360f) % 360f
    val pointerAngle = 270f
    val relativeAngle = (360f - finalAngle + pointerAngle) % 360f
    return (relativeAngle / (360f / slices)).toInt() % slices
}

@Composable
fun RouletteScreen(onNavigateUp: () -> Unit) {
    val haptics = rememberBdsmHaptics()
    val coroutineScope = rememberCoroutineScope()
    val rotation = remember { Animatable(0f) }
    var isSpinning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<RouletteResult?>(null) }
    var spins by rememberSaveable { mutableIntStateOf(0) }
    val textMeasurer = rememberTextMeasurer()

    // Fatias alternadas usam o tema (primary/tertiary) com texto "on" correspondente (contraste AA).
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(scheme.primary, scheme.tertiary)
    val onColors = listOf(scheme.onPrimary, scheme.onTertiary)
    val pointerColor = scheme.onBackground
    val sliceBorder = scheme.background

    val prizes = remember(colors) { roulettePrizes(colors) }
    val mysteryPrizes = remember { rouletteMysteryPrizes() }

    val mysteryLabel = stringResource(R.string.roulette_mystery)
    val spinningLabel = stringResource(R.string.roulette_spinning)
    val wheelDescription = stringResource(R.string.roulette_wheel_description, prizes.size)

    fun spin() {
        if (isSpinning) return
        isSpinning = true
        result = null
        haptics.confirm()
        coroutineScope.launch {
            val randomDuration = (4000..8000).random()
            val target = rotation.value + (360 * 10) + (Math.random() * 360f).toFloat()
            rotation.animateTo(target, tween(durationMillis = randomDuration, easing = FastOutSlowInEasing))
            isSpinning = false
            spins += 1
            val prize = prizes[rouletteSliceIndex(rotation.value, prizes.size)]
            result = if (prize.subtitle == "Misteriosa") {
                val m = mysteryPrizes.random()
                RouletteResult("${m.title} ${m.subtitle}".trim(), m.effect, mystery = true)
            } else {
                RouletteResult("${prize.title} ${prize.subtitle}".trim(), prize.effect, mystery = false)
            }
            haptics.confirm()
        }
    }

    BdsmScreen(
        title = stringResource(R.string.roulette_title),
        onNavigateUp = onNavigateUp,
        scrollable = false,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = BdsmTheme.spacing.screenMargin),
        ) {
            val landscape = maxWidth > maxHeight
            val rawWheel: Dp = if (landscape) {
                minOf(maxHeight - 32.dp, maxWidth * 0.5f, 420.dp)
            } else {
                minOf(maxWidth, maxHeight * 0.55f, 380.dp)
            }
            val wheelSize: Dp = rawWheel.coerceAtLeast(200.dp)

            val wheel: @Composable () -> Unit = {
                RouletteWheel(
                    prizes = prizes,
                    onColors = onColors,
                    pointerColor = pointerColor,
                    sliceBorder = sliceBorder,
                    rotation = rotation,
                    size = wheelSize,
                    description = wheelDescription,
                    onHubClick = ::spin,
                    textMeasurer = textMeasurer,
                )
            }
            val controls: @Composable (Modifier) -> Unit = { modifier ->
                Column(
                    modifier = modifier,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
                ) {
                    // Resultado em cartão (texto sobre o próprio cartão: contraste do tema, nada de scrim).
                    AnimatedVisibility(
                        visible = result != null || isSpinning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        enter = fadeIn() + slideInVertically { it / 4 },
                        exit = fadeOut() + slideOutVertically { it / 4 },
                    ) {
                        ResultCard(result = result, spinningLabel = spinningLabel, mysteryLabel = mysteryLabel)
                    }
                    BdsmBigButton(
                        text = stringResource(if (spins == 0) R.string.roulette_spin else R.string.roulette_spin_again),
                        onClick = ::spin,
                        icon = Icons.Filled.Casino,
                        enabled = !isSpinning,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (spins > 0) {
                        Text(
                            text = stringResource(R.string.roulette_spins_count, spins),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (landscape) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.xl),
                ) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { wheel() }
                    controls(Modifier.weight(1f))
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(BdsmTheme.spacing.xl))
                    wheel()
                    Spacer(Modifier.height(BdsmTheme.spacing.xl))
                    controls(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: RouletteResult?, spinningLabel: String, mysteryLabel: String) {
    BdsmCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BdsmTheme.spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm),
        ) {
            if (result == null) {
                Text(
                    text = spinningLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            } else {
                if (result.mystery) {
                    Text(
                        text = mysteryLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                if (result.effect.isNotBlank()) {
                    Text(
                        text = result.effect.trim('"'),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun RouletteWheel(
    prizes: List<RoulettePrize>,
    onColors: List<Color>,
    pointerColor: Color,
    sliceBorder: Color,
    rotation: Animatable<Float, *>,
    size: Dp,
    description: String,
    onHubClick: () -> Unit,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    val hubColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val hubIconColor = MaterialTheme.colorScheme.onSurface
    val fontSizeSp = (size.value / 30f).coerceIn(8f, 13f)
    Box(
        modifier = Modifier
            .semantics { contentDescription = description }
            .padding(top = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Seta indicadora (fica acima da roleta)
        Canvas(modifier = Modifier.size(28.dp).offset(y = (-14).dp).zIndex(2f)) {
            val path = Path().apply {
                moveTo(this@Canvas.size.width / 2f, this@Canvas.size.height)
                lineTo(0f, 0f)
                lineTo(this@Canvas.size.width, 0f)
                close()
            }
            drawPath(path, color = pointerColor)
        }

        Canvas(modifier = Modifier.size(size).clip(CircleShape)) {
            val sweepAngle = 360f / prizes.size
            // A rotação é lida só na fase de desenho: girar não recompõe a tela.
            rotate(rotation.value) {
                prizes.forEachIndexed { index, prize ->
                    val startAngle = index * sweepAngle
                    drawArc(color = prize.color, startAngle = startAngle, sweepAngle = sweepAngle, useCenter = true, size = this.size)
                    drawArc(
                        color = sliceBorder,
                        startAngle = startAngle,
                        sweepAngle = sweepAngle,
                        useCenter = true,
                        style = Stroke(width = 4f),
                        size = this.size,
                    )
                    rotate(startAngle + sweepAngle / 2) {
                        val fullText = if (prize.subtitle.isNotEmpty()) "${prize.title}\n${prize.subtitle}" else prize.title
                        val layout = textMeasurer.measure(
                            text = AnnotatedString(fullText),
                            style = TextStyle(
                                color = onColors[index % 2],
                                fontSize = fontSizeSp.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            ),
                        )
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(
                                x = center.x + this.size.width / 2 * 0.5f - layout.size.width / 2,
                                y = center.y - layout.size.height / 2,
                            ),
                        )
                    }
                }
            }
        }

        // Cubo central (também gira ao toque; alvo >= 48 dp)
        Box(
            modifier = Modifier
                .padding(top = (size - 64.dp) / 2)
                .size(64.dp)
                .clip(CircleShape)
                .background(hubColor),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.IconButton(onClick = onHubClick, modifier = Modifier.size(64.dp)) {
                androidx.compose.material3.Icon(Icons.Filled.Casino, contentDescription = null, tint = hubIconColor)
            }
        }
    }
}

private fun roulettePrizes(colors: List<Color>): List<RoulettePrize> = listOf(
    RoulettePrize("💵 R$ 1Mi", "", colors[0], "\"Infelizmente só na imaginação.\""),
    RoulettePrize("🚗 Carro", "Esportivo", colors[1], "\"Um Honda Civic Turbinado.\""),
    RoulettePrize("🏝 Ilha", "Particular", colors[0], "\"Entrega prevista para nunca.\""),
    RoulettePrize("🐔 Uma", "Galinha", colors[1], "\"Cuide bem dela.\""),
    RoulettePrize("🍕 Pizza", "", colors[0], "\"Você ganhou... vontade de comer pizza.\""),
    RoulettePrize("☕ Café", "", colors[1], "\"Melhor coisa do mundo.\""),
    RoulettePrize("🧠 +10", "Inteligência", colors[0], "\"Atualização concluída.\""),
    RoulettePrize("🎥 Cinema", "Portátil", colors[1], "\"Continue sonhando.\""),
    RoulettePrize("🛰 Satélite", "", colors[0], "\"Agora você transmite de qualquer lugar.\""),
    RoulettePrize("🎨 Tema", "Dourado", colors[1], "Desbloqueia um tema secreto."),
    RoulettePrize("🐈 Um Gato", "", colors[0], "\"Agora ele vai deitar no teclado.\""),
    RoulettePrize("🦖 Dino", "", colors[1], "\"Encontrado em perfeito estado.\""),
    RoulettePrize("🎁 Caixa", "Misteriosa", colors[0], "Sorteio Misterioso!"),
    RoulettePrize("❌ Nada", "", colors[1], "\"A casa sempre vence.\""),
    RoulettePrize("🌕 Lua", "", colors[0], "\"Você ganhou um pedaço da Lua.\""),
    RoulettePrize("⭐ Estrela", "", colors[1], "\"Agora você é dono de uma estrela.\""),
    RoulettePrize("💎 Diamante", "", colors[0], "\"Vale aproximadamente R$ 0.\""),
    RoulettePrize("🧦 Meia", "", colors[1], "\"Só metade mesmo.\""),
    RoulettePrize("📦 Caixa", "Vazia", colors[0], "\"O importante é participar.\""),
)

private fun rouletteMysteryPrizes(): List<RoulettePrize> = listOf(
    RoulettePrize("🧸 Ursinho", "de Pelúcia", effect = "\"Todo programador precisa de um.\""),
    RoulettePrize("🛸 Disco", "Voador", effect = "\"Os ETs aprovaram seu setup.\""),
    RoulettePrize("👑 Rei do", "Broadcast", effect = "\"Parabéns, você é o Rei/Rainha do BroadCast!\""),
    RoulettePrize("👽 Alienígena", "", effect = "\"Viemos em Paz.\""),
    RoulettePrize("🦄 Unicórnio", "", effect = "\"Também quero um.\""),
    RoulettePrize("🌌 O Universo", "Inteiro", effect = "\"Queria poder lhe dar um universo inteiro.\""),
)
