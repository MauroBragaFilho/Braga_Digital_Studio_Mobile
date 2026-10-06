package com.bragastudio.mobile.featurepreview.components.scopes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun HistogramScope(
    histogramR: IntArray?,
    histogramG: IntArray?,
    histogramB: IntArray?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(Color.Black.copy(alpha = 0.5f))) {
        // Pico calculado uma vez por atualização dos dados (e não a cada draw) e
        // Paths reaproveitados: sem alocação por quadro desenhado.
        val peak = remember(histogramR, histogramG, histogramB) {
            histogramPeak(histogramR, histogramG, histogramB).toFloat()
        }
        val pathR = remember { Path() }
        val pathG = remember { Path() }
        val pathB = remember { Path() }

        Canvas(modifier = Modifier.fillMaxSize()) {
            // Canais RGB reais sobrepostos com blend aditivo (como num histograma
            // de referência): onde os três se sobrepõem, a soma tende a branco.
            drawChannel(histogramR, peak, pathR, Color.Red)
            drawChannel(histogramG, peak, pathG, Color.Green)
            drawChannel(histogramB, peak, pathB, Color(0xFF4488FF))
        }
    }
}

private fun DrawScope.drawChannel(data: IntArray?, peak: Float, path: Path, color: Color) {
    if (data == null || data.isEmpty()) return
    val width = size.width
    val height = size.height
    path.rewind()
    path.moveTo(0f, height)
    for (i in data.indices) {
        val x = (i.toFloat() / data.size) * width
        val y = height - ((data[i].toFloat() / peak) * height)
        path.lineTo(x, y)
    }
    path.lineTo(width, height)
    path.close()
    drawPath(path = path, color = color.copy(alpha = 0.65f), style = Fill, blendMode = BlendMode.Plus)
    drawPath(path = path, color = color, style = Stroke(width = 1.5f))
}
