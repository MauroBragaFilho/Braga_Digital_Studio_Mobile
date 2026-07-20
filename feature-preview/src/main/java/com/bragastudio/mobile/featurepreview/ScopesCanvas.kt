package com.bragastudio.mobile.featurepreview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

@Composable
fun HistogramScope(
    histogramR: IntArray?,
    histogramG: IntArray?,
    histogramB: IntArray?,
    modifier: Modifier = Modifier
) {
    if (histogramR == null || histogramG == null || histogramB == null ||
        histogramR.isEmpty() || histogramG.isEmpty() || histogramB.isEmpty()) return

    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val binWidth = width / 256f

            // Calculate max value across Luma channel to normalize the height
            var maxVal = 1
            for (i in 0 until 256) {
                if (histogramR[i] > maxVal) maxVal = histogramR[i]
            }

            // Draw Luma histogram
            drawChannel(histogramR, maxVal, height, binWidth, Color.White.copy(alpha = 0.8f))
        }
    }
}

private fun DrawScope.drawChannel(
    histogram: IntArray,
    maxVal: Int,
    height: Float,
    binWidth: Float,
    color: Color
) {
    for (i in 0 until 256) {
        val h = (histogram[i].toFloat() / maxVal) * height
        val x = i * binWidth

        drawLine(
            color = color,
            start = Offset(x, height),
            end = Offset(x, height - h),
            strokeWidth = binWidth,
            cap = StrokeCap.Round
        )
    }
}
