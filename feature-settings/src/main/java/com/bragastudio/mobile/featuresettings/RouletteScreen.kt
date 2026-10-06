package com.bragastudio.mobile.featuresettings
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

data class RoulettePrize(val title: String, val subtitle: String = "", val color: Color = Color.Unspecified, val effect: String = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouletteScreen(onNavigateUp: () -> Unit) {
    var isSpinning by remember { mutableStateOf(false) }
    val rotation = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    var resultText by remember { mutableStateOf("") }
    var showResult by remember { mutableStateOf(false) }

    val textMeasurer = rememberTextMeasurer()

    // Fatias alternadas usam o tema (primary/tertiary) com texto "on" correspondente (contraste AA).
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(scheme.primary, scheme.tertiary)
    val onColors = listOf(scheme.onPrimary, scheme.onTertiary)
    val pointerColor = scheme.onBackground
    val sliceBorder = scheme.background

    val prizes = listOf(
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

    val mysteryPrizes = listOf(
        RoulettePrize("🧸 Ursinho", "de Pelúcia", effect = "\"Todo programador precisa de um.\""),
        RoulettePrize("🛸 Disco", "Voador", effect = "\"Os ETs aprovaram seu setup.\""),
        RoulettePrize("👑 Rei do", "Broadcast", effect = "\"Parabéns, você é o Rei/Rainha do BroadCast!\""),
        RoulettePrize("👽 Alienígena", "", effect = "\"Viemos em Paz.\""),
        RoulettePrize("🦄 Unicórnio", "", effect = "\"Também quero um.\""),
        RoulettePrize("🌌 O Universo", "Inteiro", effect = "\"Queria poder lhe dar um universo inteiro.\""),
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.roulette_title), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(com.bragastudio.mobile.common.R.string.bdsm_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(contentAlignment = Alignment.TopCenter) {
                // Seta indicadora (fica acima da roleta)
                Canvas(modifier = Modifier.size(30.dp).offset(y = (-15).dp).zIndex(2f)) {
                    val path = Path().apply {
                        moveTo(size.width / 2f, size.height)
                        lineTo(0f, 0f)
                        lineTo(size.width, 0f)
                        close()
                    }
                    drawPath(path, color = pointerColor)
                }

                // Roleta
                Canvas(
                    modifier = Modifier
                        .size(300.dp)
                        .clip(CircleShape),
                ) {
                    val sweepAngle = 360f / prizes.size

                    rotate(rotation.value) {
                        prizes.forEachIndexed { index, prize ->
                            val startAngle = index * sweepAngle

                            // Fatia da roleta
                            drawArc(
                                color = prize.color,
                                startAngle = startAngle,
                                sweepAngle = sweepAngle,
                                useCenter = true,
                                size = size,
                            )

                            // Borda da fatia
                            drawArc(
                                color = sliceBorder,
                                startAngle = startAngle,
                                sweepAngle = sweepAngle,
                                useCenter = true,
                                style = Stroke(width = 4f),
                                size = size,
                            )

                            // Texto
                            rotate(startAngle + sweepAngle / 2) {
                                val fullText = if (prize.subtitle.isNotEmpty()) "${prize.title}\n${prize.subtitle}" else prize.title
                                val textLayoutResult = textMeasurer.measure(
                                    text = AnnotatedString(fullText),
                                    style = TextStyle(color = onColors[index % 2], fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                                )
                                // Desenha no eixo X positivo (da direita do centro para a borda)
                                val textOffset = Offset(
                                    x = center.x + size.width / 2 * 0.5f - textLayoutResult.size.width / 2,
                                    y = center.y - textLayoutResult.size.height / 2,
                                )
                                drawText(
                                    textLayoutResult = textLayoutResult,
                                    topLeft = textOffset,
                                )
                            }
                        }
                    }
                }

                // Botão de clique no centro (Círculo interno preto)
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .align(Alignment.Center)
                        .clickable {
                            if (!isSpinning) {
                                isSpinning = true
                                resultText = "Sorteando..."
                                showResult = false
                                coroutineScope.launch {
                                    val randomDuration = (4000..8000).random()
                                    val targetRotation = rotation.value + (360 * 10) + (Math.random() * 360f).toFloat()
                                    rotation.animateTo(
                                        targetValue = targetRotation,
                                        animationSpec = tween(durationMillis = randomDuration, easing = FastOutSlowInEasing),
                                    )
                                    isSpinning = false

                                    val finalAngle = (rotation.value % 360 + 360) % 360
                                    val pointerAngle = 270f
                                    val relativeAngle = (360f - finalAngle + pointerAngle) % 360f
                                    val sliceIndex = (relativeAngle / (360f / prizes.size)).toInt() % prizes.size

                                    val prize = prizes[sliceIndex]
                                    if (prize.subtitle == "Misteriosa") {
                                        val mystery = mysteryPrizes.random()
                                        resultText = "🎁 Caixa Misteriosa!\n\n${mystery.title} ${mystery.subtitle}\n\n${mystery.effect}"
                                    } else {
                                        resultText = "${prize.title} ${prize.subtitle}\n\n${prize.effect}"
                                    }
                                    showResult = true
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.roulette_spin), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            }

            Spacer(modifier = Modifier.height(30.dp))

            if (!showResult) {
                Text(
                    text = if (isSpinning) "Sorteando..." else "",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }

        if (showResult) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.85f))
                    .clickable { showResult = false }
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = resultText,
                    // Texto sobre o scrim escuro: branco fixo para contraste nos dois temas.
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 36.sp,
                )
            }
        }
    }
}
