package com.bragastudio.mobile.featurepreview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lista rolável de LUTs (não é dial circular — nomes variam muito de tamanho
 * e a leitura em lista é mais rápida para escolher entre várias opções do que
 * girar um anel). "Nenhum (Desativado)" não aparece aqui: o toque no próprio
 * botão de LUT (fora deste painel) já liga/desliga o LUT ativo.
 */
@Composable
@Suppress("UNUSED_PARAMETER") // onDismiss: dismiss real é feito via Popup(onDismissRequest) no chamador; parametro mantido por compatibilidade
fun LutListPopover(
    luts: List<com.bragastudio.mobile.core.model.Lut>,
    activeLutId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // Opcional (default null): quando fornecido, mostra um item fixo no
    // rodapé para abrir a LutManagementScreen — a UI moderna recebia
    // onNavigateToLuts mas nunca desenhava nenhum gatilho pra ele.
    onNavigateToLuts: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .width(220.dp)
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ModernHudTheme.panelBg)
            .border(1.dp, ModernHudTheme.panelBorder, RoundedCornerShape(18.dp))
            .padding(vertical = 12.dp),
    ) {
        Text(
            "LUT",
            color = Color.Gray,
            fontSize = HudTheme.fontSizeMin,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            luts.forEach { lut ->
                val isSelected = lut.id == activeLutId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = HudTheme.minTouchTarget)
                        .clickable { onSelect(lut.id) }
                        .background(if (isSelected) ModernHudTheme.accent.copy(alpha = 0.14f) else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        lut.displayName,
                        color = if (isSelected) ModernHudTheme.accent else Color.White,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        Icon(Icons.Filled.Check, null, tint = ModernHudTheme.accent, modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (luts.isEmpty()) {
                Text(
                    "Nenhum LUT importado",
                    color = Color.Gray,
                    fontSize = HudTheme.fontSizeNormal,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        if (onNavigateToLuts != null) {
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(ModernHudTheme.panelBorder))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = HudTheme.minTouchTarget)
                    .clickable { onNavigateToLuts() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Filled.Tune, null, tint = ModernHudTheme.accent, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Gerenciar LUTs...", color = ModernHudTheme.accent, fontSize = HudTheme.fontSizeNormal, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Lista rolável genérica de opções em texto simples — mesmo visual/estrutura
 * da LutListPopover, mas sem depender do tipo `Lut`. Usada pelo Aspect Ratio
 * e reutilizável para qualquer outro controle futuro que faça mais sentido
 * como lista do que como dial circular.
 */
@Composable
@Suppress("UNUSED_PARAMETER") // onDismiss: dismiss real é feito via Popup(onDismissRequest) no chamador; parametro mantido por compatibilidade
fun SimpleListPopover(
    title: String,
    options: List<String>,
    activeOption: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(200.dp)
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ModernHudTheme.panelBg)
            .border(1.dp, ModernHudTheme.panelBorder, RoundedCornerShape(18.dp))
            .padding(vertical = 12.dp),
    ) {
        Text(
            title,
            color = Color.Gray,
            fontSize = HudTheme.fontSizeMin,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            options.forEach { option ->
                val isSelected = option == activeOption
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = HudTheme.minTouchTarget)
                        .clickable { onSelect(option) }
                        .background(if (isSelected) ModernHudTheme.accent.copy(alpha = 0.14f) else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        option,
                        color = if (isSelected) ModernHudTheme.accent else Color.White,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        Icon(Icons.Filled.Check, null, tint = ModernHudTheme.accent, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/**
 * Painel combinado do Focus Peaking, aberto ao segurar o botão de Peaking:
 * antes só a sensibilidade tinha um controle exposto; a cor já existia
 * persistida (MonitorSettings.focusPeakingColor) mas sem nenhuma UI.
 */
@Composable
@Suppress("UNUSED_PARAMETER") // onDismiss: dismiss real é feito via Popup(onDismissRequest) no chamador; parametro mantido por compatibilidade
fun FocusPeakingPanel(
    sensitivityValueLabel: String,
    sensitivityOptions: List<String>,
    onSelectSensitivity: (String) -> Unit,
    currentColor: String,
    onSelectColor: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorOptions = listOf(
        "Red" to Color(0xFFFF3B30),
        "Green" to Color(0xFF34C759),
        "Blue" to Color(0xFF0A84FF),
        "Yellow" to Color(0xFFFFD60A),
        "White" to Color.White,
    )

    Column(
        modifier = modifier
            .width(272.dp) // 5 swatches de 48 dp (alvo de toque) + padding
            .clip(RoundedCornerShape(18.dp))
            .background(ModernHudTheme.panelBg)
            .border(1.dp, ModernHudTheme.panelBorder, RoundedCornerShape(18.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("FOCUS PEAKING", color = Color.Gray, fontSize = HudTheme.fontSizeMin, fontWeight = FontWeight.Bold)

        // Botão de cor: cada swatch é clicável direto (é uma escolha entre
        // poucas opções fixas, não precisa de dial circular aqui).
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("COR", color = Color.Gray, fontSize = HudTheme.fontSizeMin, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                colorOptions.forEach { (name, swatch) ->
                    val isSelected = currentColor.equals(name, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .hudClickable(
                                onClick = { onSelectColor(name) },
                                role = Role.RadioButton,
                            )
                            .semantics {
                                contentDescription = "Cor $name"
                                selected = isSelected
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) ModernHudTheme.accent else Color.White.copy(alpha = 0.3f),
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))

        // Sensibilidade: mesmo miolo de dial circular usado em todo o resto do
        // app (CircularDialContent, compartilhado com CircularDialPopover).
        CircularDialContent(
            title = "SENSIBILIDADE",
            currentValueLabel = sensitivityValueLabel,
            options = sensitivityOptions,
            isManual = true,
            onSelect = onSelectSensitivity,
        )
    }
}

/**
 * Miolo compartilhado de todo dial circular do app: anel arrastável (gesto
 * radial), valor grande no centro, título embaixo. Usado tanto standalone
 * (dentro de CircularDialPopover, com moldura própria) quanto embutido em
 * painéis compostos (como FocusPeakingPanel, que soma cor + sensibilidade
 * numa mesma moldura).
 */
@Composable
fun CircularDialContent(
    title: String,
    currentValueLabel: String,
    options: List<String>,
    isManual: Boolean,
    onSelect: (String) -> Unit,
    // M31: chamado UMA vez ao soltar o dedo (onDragEnd) com o valor final — é aqui
    // que o chamador deve persistir. `onSelect` é chamado só quando o ÍNDICE muda
    // durante o arrasto (antes disparava a cada evento de toque).
    onSelectFinished: ((String) -> Unit)? = null,
) {
    val selectedIndex = remember(currentValueLabel, options) {
        options.indexOf(currentValueLabel).coerceAtLeast(0)
    }
    // BUG CORRIGIDO: a chave era só `options` (uma lista fixa que nunca muda
    // entre recomposições), então `dragIndex` ficava preso no valor inicial e
    // só era atualizado por arraste local — nunca resincronizava com o valor
    // real vindo de fora (ex.: sensibilidade mudada e persistida, mas o dial
    // continuava mostrando o valor antigo até fechar/reabrir o popover, que
    // recriava o composable do zero). Agora a chave é a mesma de
    // `selectedIndex` (currentValueLabel + options), então qualquer mudança
    // externa do valor atual resincroniza o dial imediatamente.
    var dragIndex by remember(currentValueLabel, options) { mutableIntStateOf(selectedIndex) }
    // rememberUpdatedState: o detector de gestos nunca reinicia por lambdas novas
    // e sempre chama a versão mais recente dos callbacks.
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnFinished by rememberUpdatedState(onSelectFinished)

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .semantics {
                    contentDescription = "$title: ${options.getOrElse(dragIndex) { currentValueLabel }}"
                }
                .pointerInput(options, isManual) {
                    if (!isManual || options.isEmpty()) return@pointerInput
                    detectDragGestures(
                        onDragEnd = {
                            options.getOrNull(dragIndex)?.let { currentOnFinished?.invoke(it) }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                            val pos = change.position
                            val angle = (
                                Math.toDegrees(
                                    kotlin.math.atan2((pos.y - center.y).toDouble(), (pos.x - center.x).toDouble()),
                                ) + 360.0
                                ) % 360.0
                            // O anel é desenhado de startAngle=150° a 150°+240°=390°(=30°),
                            // no mesmo sentido horário que atan2 já produz aqui (ver
                            // dialIndexForAngle, que é testada por unidade).
                            val idx = dialIndexForAngle(angle, options.size)
                            // M31: só notifica quando o índice MUDA.
                            if (idx != dragIndex) {
                                dragIndex = idx
                                currentOnSelect(options[idx])
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val sweepFraction = if (options.isEmpty()) 0f else dragIndex / (options.size - 1).coerceAtLeast(1).toFloat()
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeW = 10.dp.toPx()
                drawArc(
                    color = Color.White.copy(alpha = 0.12f),
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    style = Stroke(width = strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
                drawArc(
                    color = if (isManual) ModernHudTheme.accent else Color.Gray,
                    startAngle = 150f,
                    sweepAngle = 240f * sweepFraction,
                    useCenter = false,
                    style = Stroke(width = strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = options.getOrElse(dragIndex) { currentValueLabel },
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(title, color = Color.Gray, fontSize = HudTheme.fontSizeMin)
            }
        }
    }
}

/**
 * Popover com controle deslizante HORIZONTAL — estilo régua, como em câmeras
 * de cinema reais (Blackmagic/RED/ARRI) para ISO, obturador, WB e foco. Usado
 * especificamente pelos controles manuais de exposição (LensControlCluster);
 * o resto da interface (Zebra, Peaking, LUT, Aspect Ratio na leftbar) continua
 * usando o dial circular (CircularDialPopover), que combina melhor com toggles
 * simples de poucas opções.
 *
 * Arrastar totalmente para a esquerda sempre cai na primeira opção da lista —
 * por convenção, todo `options` passado aqui deve começar com "AUTO", então
 * "soltar tudo à esquerda" = voltar ao automático, como pedido.
 */
@Composable
@Suppress("UNUSED_PARAMETER") // onDismiss: dismiss real é feito via Popup(onDismissRequest) no chamador; parametro mantido por compatibilidade
fun HorizontalDialPopover(
    title: String,
    currentValueLabel: String,
    options: List<String>,
    isManual: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // M31: chamado uma vez ao soltar o dedo (persistência); onSelect só quando o índice muda.
    onSelectFinished: ((String) -> Unit)? = null,
) {
    val selectedIndex = remember(currentValueLabel, options) {
        options.indexOf(currentValueLabel).coerceAtLeast(0)
    }
    // Mesma correção aplicada ao dial circular: chave inclui currentValueLabel,
    // não só options, para nunca ficar com um valor "preso" de uma sessão
    // anterior.
    var dragIndex by remember(currentValueLabel, options) { mutableIntStateOf(selectedIndex) }
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnFinished by rememberUpdatedState(onSelectFinished)
    val isAuto = options.getOrNull(dragIndex) == "AUTO"

    Column(
        modifier = modifier
            .width(280.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ModernHudTheme.panelBg)
            .border(1.dp, ModernHudTheme.panelBorder, RoundedCornerShape(18.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color.Gray, fontSize = HudTheme.fontSizeMin, fontWeight = FontWeight.Bold)
            Text(
                when {
                    !isManual -> "INDISPONÍVEL"
                    isAuto -> "AUTOMÁTICO"
                    else -> "MANUAL"
                },
                color = if (isManual && !isAuto) ModernHudTheme.accent else Color.Gray,
                fontSize = HudTheme.fontSizeMin,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(
            text = options.getOrElse(dragIndex) { currentValueLabel },
            color = if (isAuto) Color.Gray else Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )

        // Régua horizontal: trilha + marcações de cada opção + indicador
        // preenchido do início (esquerda) até a posição atual.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .pointerInput(options, isManual) {
                    if (!isManual || options.size < 2) return@pointerInput
                    detectDragGestures(
                        onDragEnd = {
                            options.getOrNull(dragIndex)?.let { currentOnFinished?.invoke(it) }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            val idx = sliderIndexForFraction(fraction, options.size)
                            // M31: só notifica quando o índice MUDA.
                            if (idx != dragIndex) {
                                dragIndex = idx
                                currentOnSelect(options[idx])
                            }
                        },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val fillFraction = if (options.size > 1) dragIndex / (options.size - 1).toFloat() else 0f

            // Trilha de fundo
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.15f)),
            )
            // Preenchimento do início até a posição atual
            Box(
                modifier = Modifier
                    .fillMaxWidth(fillFraction)
                    .height(4.dp)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isAuto) Color.Gray else ModernHudTheme.accent),
            )
            // Marcações de cada opção
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                options.forEachIndexed { idx, _ ->
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(if (idx == 0) 14.dp else 8.dp) // marca do AUTO um pouco maior, para se destacar
                            .background(Color.White.copy(alpha = if (idx <= dragIndex) 0.5f else 0.2f)),
                    )
                }
            }
            // Manípulo (thumb)
            Box(
                modifier = Modifier
                    .fillMaxWidth(fillFraction)
                    .align(Alignment.CenterStart),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(3.dp, if (isAuto) Color.Gray else ModernHudTheme.accent, CircleShape),
                )
            }
        }

        Text(
            "Arraste — solte totalmente à esquerda para Automático",
            color = Color.Gray,
            fontSize = HudTheme.fontSizeMin,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * Popover com dial circular (anel arrastável) para ajustar um parâmetro de
 * exposição/foco por gesto radial — usado para todos os ajustes manuais na
 * nova interface (ISO, obturador, íris, WB, foco, zebra, LUT, aspect ratio).
 * A moldura (título + badge Manual/Indisponível + dica de uso) fica aqui;
 * o miolo do dial em si é o CircularDialContent compartilhado acima.
 */
@Composable
@Suppress("UNUSED_PARAMETER") // onDismiss: dismiss real é feito via Popup(onDismissRequest) no chamador; parametro mantido por compatibilidade
fun CircularDialPopover(
    title: String,
    currentValueLabel: String,
    options: List<String>,
    isManual: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectFinished: ((String) -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .width(190.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ModernHudTheme.panelBg)
            .border(1.dp, ModernHudTheme.panelBorder, RoundedCornerShape(18.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color.Gray, fontSize = HudTheme.fontSizeMin, fontWeight = FontWeight.Bold)
            Text(
                if (isManual) "MANUAL" else "INDISPONÍVEL",
                color = if (isManual) ModernHudTheme.accent else Color.Gray,
                fontSize = HudTheme.fontSizeMin,
                fontWeight = FontWeight.Bold,
            )
        }

        CircularDialContent(
            title = title,
            currentValueLabel = currentValueLabel,
            options = options,
            isManual = isManual,
            onSelect = onSelect,
            onSelectFinished = onSelectFinished,
        )

        Text(
            "Arraste ao redor do anel para ajustar",
            color = Color.Gray,
            fontSize = HudTheme.fontSizeMin,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
