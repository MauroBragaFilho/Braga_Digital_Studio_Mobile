package com.bragastudio.mobile.featuresettings

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import com.bragastudio.mobile.coremedia.ndi.NdiSortMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val ROTATION_PERIOD_MS = 180_000
private const val ROTATION_STEP_MS = 120L
private val RING_FRACTIONS = floatArrayOf(0.36f, 0.66f, 0.94f)
private const val TWO_PI = (2 * PI).toFloat()

/**
 * Radar/globo das fontes NDI, desenhado em Canvas. Gira devagar (uma volta em 3 min; parado se as
 * animações do sistema estiverem desligadas). Cada fonte é só uma bolinha ou uma seta estilo nave:
 * seta = este aparelho, bolinha cheia = disponível, bolinha vazada = sem resposta. Sem ícones,
 * miniaturas, nomes ou IPs no desenho; tocar numa fonte chama [onSelect] com o id (nome NDI).
 * A versão acessível é a Lista (o radar tem uma descrição única e orienta a usá-la).
 */
@Composable
fun NdiRadar(
    items: List<NdiSourceUi>,
    mode: NdiSortMode,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val rotation = rememberRadarRotation(animate)
    val points = remember(items, mode) { NdiNetworkLogic.layout(items, mode) }
    val byId = remember(items) { items.associateBy { it.id } }

    val colors = BdsmTheme.colors
    val ringColor = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant
    val globeInner = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh
    val globeOuter = androidx.compose.material3.MaterialTheme.colorScheme.surface
    val gap = androidx.compose.material3.MaterialTheme.colorScheme.background
    val availableColor = colors.success
    val noResponseColor = colors.warning
    val selfColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val selectedColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface

    val description = pluralStringResource(R.plurals.ndi_net_radar_desc, items.size, items.size)
    val currentPoints = rememberUpdatedState(points)
    val currentById = rememberUpdatedState(byId)

    Canvas(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val radius = minOf(size.width, size.height) / 2f - 4.dp.toPx()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val hit = 28.dp.toPx()
                    val best = currentPoints.value
                        .map { it to pointPosition(center, radius, it, rotation.value) }
                        .filter { (_, pos) -> hypot(pos.x - tap.x, pos.y - tap.y) <= hit }
                        .minByOrNull { (_, pos) -> hypot(pos.x - tap.x, pos.y - tap.y) }
                    best?.first?.let { onSelect(it.id) }
                }
            },
    ) {
        val radius = size.minDimension / 2f - 4.dp.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)
        val rot = rotation.value

        // Globo: fundo em gradiente + anéis + meridianos girando.
        drawCircle(
            brush = Brush.radialGradient(listOf(globeInner, globeOuter), center = center, radius = radius),
            radius = radius,
            center = center,
        )
        RING_FRACTIONS.forEachIndexed { index, f ->
            drawCircle(
                color = ringColor,
                radius = radius * f,
                center = center,
                style = Stroke(width = if (index == RING_FRACTIONS.lastIndex) 2.dp.toPx() else 1.dp.toPx()),
            )
        }
        for (k in 0 until 3) {
            val rx = radius * abs(cos(rot * 4f + k * PI.toFloat() / 3f))
            drawOval(
                color = ringColor.copy(alpha = 0.45f),
                topLeft = Offset(center.x - rx, center.y - radius),
                size = Size(rx * 2f, radius * 2f),
                style = Stroke(width = 1.dp.toPx()),
            )
        }

        points.forEach { point ->
            val item = currentById.value[point.id] ?: return@forEach
            val pos = pointPosition(center, radius, point, rot)
            if (point.id == selectedId) {
                drawCircle(color = selectedColor.copy(alpha = 0.18f), radius = 20.dp.toPx(), center = pos)
                drawCircle(color = selectedColor, radius = 20.dp.toPx(), center = pos, style = Stroke(width = 1.5.dp.toPx()))
            }
            when (item.state) {
                NdiDeviceState.SELF -> drawShip(pos, point.angle + rot, selfColor, gap)

                NdiDeviceState.AVAILABLE -> {
                    drawCircle(color = gap, radius = 10.dp.toPx(), center = pos)
                    drawCircle(color = availableColor, radius = 8.dp.toPx(), center = pos, style = Fill)
                }

                NdiDeviceState.NO_RESPONSE -> {
                    drawCircle(color = gap, radius = 10.dp.toPx(), center = pos)
                    drawCircle(color = noResponseColor, radius = 7.dp.toPx(), center = pos, style = Stroke(width = 2.5.dp.toPx()))
                }
            }
        }
    }
}

private fun pointPosition(center: Offset, radius: Float, point: RadarPoint, rotation: Float): Offset {
    val r = radius * RING_FRACTIONS[point.ring.coerceIn(0, RING_FRACTIONS.lastIndex)]
    val a = point.angle + rotation
    return Offset(center.x + r * cos(a), center.y + r * sin(a))
}

/** Seta estilo nave apontando para fora do centro. */
private fun DrawScope.drawShip(pos: Offset, heading: Float, color: Color, outline: Color) {
    val size = 12.dp.toPx()
    fun corner(angle: Float, dist: Float) = Offset(pos.x + dist * cos(angle), pos.y + dist * sin(angle))
    val path = Path().apply {
        val tip = corner(heading, size)
        val left = corner(heading + 2.5f, size * 0.85f)
        val notch = corner(heading + PI.toFloat(), size * 0.35f)
        val right = corner(heading - 2.5f, size * 0.85f)
        moveTo(tip.x, tip.y)
        lineTo(left.x, left.y)
        lineTo(notch.x, notch.y)
        lineTo(right.x, right.y)
        close()
    }
    drawPath(path, color = outline, style = Stroke(width = 5.dp.toPx()))
    drawPath(path, color = color, style = Fill)
}

/** Rotação lenta; parada quando o usuário desligou as animações do sistema. */
@Composable
private fun rememberRadarRotation(animate: Boolean): State<Float> {
    val context = LocalContext.current
    val animationsOff = remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) {
            false
        }
    }
    val angle = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    // A rotação é lentíssima (3 min por volta): atualizar a ~8 quadros/s basta e custa uma fração
    // de uma animação a 60 fps. Para quando a tela não está visível (animate = false).
    androidx.compose.runtime.LaunchedEffect(animate, animationsOff) {
        if (!animate || animationsOff) return@LaunchedEffect
        var last = System.nanoTime()
        while (true) {
            kotlinx.coroutines.delay(ROTATION_STEP_MS)
            val now = System.nanoTime()
            val dt = (now - last) / 1_000_000f
            last = now
            angle.floatValue = (angle.floatValue + TWO_PI * dt / ROTATION_PERIOD_MS) % TWO_PI
        }
    }
    return angle
}
