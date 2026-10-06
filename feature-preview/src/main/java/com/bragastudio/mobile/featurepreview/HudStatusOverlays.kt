package com.bragastudio.mobile.featurepreview

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Timecode de gravação (HH:MM:SS:FF). M29: recebe um PROVEDOR `() -> Long` e o lê
 * aqui, na folha — só este Text recompõe a cada tick (~33 Hz); a topbar e o HUD
 * ficam intactos. Não reduzir a frequência do timer de origem: o campo de frames
 * do timecode depende dela.
 */
@Composable
fun TimecodeText(
    timeProvider: () -> Long,
    fps: Int,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = HudTheme.fontSizeNormal,
    color: Color = Color.White,
) {
    Text(
        text = formatTimecode(timeProvider(), fps),
        color = color,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, // números tabulares: o timecode não "dança"
        maxLines = 1,
        softWrap = false,
        modifier = modifier,
    )
}

/**
 * Indicador de zoom com atalhos 1x/2x e mini-mapa de navegação.
 * O mini-mapa mostra a região do frame completo que está sendo exibida quando
 * o zoom pixel-a-pixel (>1x) está ativo, ajudando o operador a saber onde está
 * "olhando" dentro do sensor — como em monitores de referência profissionais.
 */
@Composable
@Suppress("UNUSED_PARAMETER") // onSetZoom: mantido na assinatura para simetria com os demais controles com dial; ainda não conectado
fun ZoomControl(
    zoomFactor: Float,
    panX: Float,
    panY: Float,
    onSetZoom: (Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Mini-mapa: só aparece quando há zoom aplicado (pixel a pixel)
        if (zoomFactor > 1.01f) {
            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 36.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(4.dp)),
            ) {
                // Retângulo representando a janela visível dentro do frame total
                val viewportWidthFraction = (1f / zoomFactor).coerceIn(0.05f, 1f)
                val viewportHeightFraction = viewportWidthFraction

                // panX/panY normalizados (-0.5..0.5 aprox) -> posição do canto do viewport
                val leftFraction = ((0.5f + panX) - viewportWidthFraction / 2f).coerceIn(0f, 1f - viewportWidthFraction)
                val topFraction = ((0.5f + panY) - viewportHeightFraction / 2f).coerceIn(0f, 1f - viewportHeightFraction)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = (64.dp * leftFraction),
                            top = (36.dp * topFraction),
                        )
                        .size(width = 64.dp * viewportWidthFraction, height = 36.dp * viewportHeightFraction)
                        .background(Color.White.copy(alpha = 0.25f))
                        .border(1.dp, HudTheme.buttonActiveColor, RoundedCornerShape(1.dp)),
                )
            }
        }

        // Chip com o valor atual de zoom digital (pixel a pixel). Os atalhos
        // fixos "1x"/"2x" que existiam aqui foram escondidos a pedido — essa
        // troca de nível já é feita pelo seletor de lente física (rail da
        // câmera) ou por pinça na tela; manter os dois ao mesmo tempo perto
        // da lente duplicava a função e ocupava espaço.
        if (zoomFactor > 1.01f) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = String.format(java.util.Locale.US, "%.1fx", zoomFactor),
                    color = HudTheme.buttonActiveColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/**
 * Borda de tally pulsante. Vermelho = PROGRAM/REC; verde = PREVIEW (ver [tallyIndicator]).
 * [TallyIndicator.NONE] não desenha nada.
 */
@Composable
internal fun TallyBorder(modifier: Modifier = Modifier, indicator: TallyIndicator = TallyIndicator.RED) {
    if (indicator == TallyIndicator.NONE) return
    val tallyColor = if (indicator == TallyIndicator.GREEN) TallyGreen else HudTheme.recordActiveColor
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "tally")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(durationMillis = 700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "tallyPulse",
    )
    Canvas(modifier = modifier) {
        val strokeWidth = 6.dp.toPx()
        drawRect(
            color = tallyColor.copy(alpha = pulseAlpha),
            topLeft = androidx.compose.ui.geometry.Offset(strokeWidth / 2f, strokeWidth / 2f),
            size = androidx.compose.ui.geometry.Size(size.width - strokeWidth, size.height - strokeWidth),
            style = Stroke(width = strokeWidth),
        )
    }
}

private val TallyGreen = Color(0xFF00C853)

@Composable
fun GridAndAspectOverlay(
    currentGrid: String,
    currentAspectRatio: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        if (currentGrid != "OFF" || currentAspectRatio != "OFF") {
            Canvas(modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)) {
                val lineThickness = 1.dp.toPx()
                val w = size.width
                val h = size.height

                // GRIDS
                if (currentGrid != "OFF") {
                    when (currentGrid) {
                        "3x3" -> {
                            drawLine(color = Color.White.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(w / 3f, 0f), end = androidx.compose.ui.geometry.Offset(w / 3f, h), strokeWidth = lineThickness)
                            drawLine(color = Color.White.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(2f * w / 3f, 0f), end = androidx.compose.ui.geometry.Offset(2f * w / 3f, h), strokeWidth = lineThickness)

                            drawLine(color = Color.White.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(0f, h / 3f), end = androidx.compose.ui.geometry.Offset(w, h / 3f), strokeWidth = lineThickness)
                            drawLine(color = Color.White.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(0f, 2f * h / 3f), end = androidx.compose.ui.geometry.Offset(w, 2f * h / 3f), strokeWidth = lineThickness)
                        }

                        "4x4" -> {
                            for (i in 1..3) {
                                drawLine(color = Color.White.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(w * i / 4f, 0f), end = androidx.compose.ui.geometry.Offset(w * i / 4f, h), strokeWidth = lineThickness)
                                drawLine(color = Color.White.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(0f, h * i / 4f), end = androidx.compose.ui.geometry.Offset(w, h * i / 4f), strokeWidth = lineThickness)
                            }
                        }

                        "Centro" -> {
                            drawLine(color = Color.White.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(w / 2f - 20.dp.toPx(), h / 2f), end = androidx.compose.ui.geometry.Offset(w / 2f + 20.dp.toPx(), h / 2f), strokeWidth = lineThickness)
                            drawLine(color = Color.White.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(w / 2f, h / 2f - 20.dp.toPx()), end = androidx.compose.ui.geometry.Offset(w / 2f, h / 2f + 20.dp.toPx()), strokeWidth = lineThickness)
                        }
                    }
                }

                // ASPECT RATIO MARKERS
                if (currentAspectRatio != "OFF") {
                    val ratioValue = when (currentAspectRatio) {
                        "2.35:1" -> 2.35f
                        "4:3" -> 4f / 3f
                        "1:1" -> 1f
                        else -> 16f / 9f
                    }

                    if (ratioValue > (16f / 9f)) {
                        val cinemaHeight = w / ratioValue
                        val yOffset = (h - cinemaHeight) / 2f

                        drawLine(color = Color.Red.copy(alpha = 0.8f), start = androidx.compose.ui.geometry.Offset(0f, yOffset), end = androidx.compose.ui.geometry.Offset(w, yOffset), strokeWidth = lineThickness * 2)
                        drawLine(color = Color.Red.copy(alpha = 0.8f), start = androidx.compose.ui.geometry.Offset(0f, h - yOffset), end = androidx.compose.ui.geometry.Offset(w, h - yOffset), strokeWidth = lineThickness * 2)
                    } else {
                        val squareWidth = h * ratioValue
                        val xOffset = (w - squareWidth) / 2f

                        drawLine(color = Color.Yellow.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(xOffset, 0f), end = androidx.compose.ui.geometry.Offset(xOffset, h), strokeWidth = lineThickness * 2)
                        drawLine(color = Color.Yellow.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(w - xOffset, 0f), end = androidx.compose.ui.geometry.Offset(w - xOffset, h), strokeWidth = lineThickness * 2)
                    }
                }
            }
        }
    }
}

/** Lê zoom/pan (alta frequência no pinch) só aqui, isolando a recomposição do restante do HUD. */
@Composable
internal fun ZoomControlHost(
    zoomFactorProvider: () -> Float,
    panXProvider: () -> Float,
    panYProvider: () -> Float,
) {
    ZoomControl(zoomFactor = zoomFactorProvider(), panX = panXProvider(), panY = panYProvider(), onSetZoom = { _, _, _ -> })
}
