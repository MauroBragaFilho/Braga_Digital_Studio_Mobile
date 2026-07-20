package com.bragastudio.mobile.featurepreview.components.scopes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun HistogramScope(
    histogramR: IntArray?,
    histogramG: IntArray?,
    histogramB: IntArray?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(Color.Black.copy(alpha = 0.5f))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            val maxVal = (histogramR?.maxOrNull() ?: 1).toFloat()

            val drawChannel = { data: IntArray?, color: Color ->
                if (data != null && data.isNotEmpty()) {
                    val path = Path()
                    path.moveTo(0f, height)
                    for (i in data.indices) {
                        val x = (i.toFloat() / data.size) * width
                        val y = height - ((data[i].toFloat() / maxVal) * height)
                        path.lineTo(x, y)
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(width = 2f)
                    )
                }
            }

            drawChannel(histogramR, Color.White)
        }
    }
}
