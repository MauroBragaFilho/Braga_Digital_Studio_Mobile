package com.bragastudio.mobile.featurepreview.components.scopes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntSize

@Composable
fun VectorscopeScope(
    vectorscopeData: IntArray?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(Color.Black.copy(alpha = 0.5f))) {
        // Cálculo dos pixels em Dispatchers.Default, com Bitmap reaproveitado (M29).
        val holder = rememberScopeBitmap(vectorscopeData, ::vectorscopeToPixels)

        Canvas(modifier = Modifier.fillMaxSize()) {
            holder.version // leitura de estado: invalida o draw quando o conteúdo muda
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = minOf(size.width, size.height) / 2f
            val reticle = Color.White.copy(alpha = 0.3f)

            // Retículo
            drawCircle(color = reticle, radius = radius, center = center, style = Stroke(width = 1f))
            drawLine(reticle, Offset(center.x, 0f), Offset(center.x, size.height), strokeWidth = 1f)
            drawLine(reticle, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 1f)

            if (holder.hasContent) {
                drawImage(
                    image = holder.image,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.None,
                )
            }
        }
    }
}
