package com.bragastudio.mobile.featurepreview.components.scopes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntSize

@Composable
fun WaveformScope(
    waveformData: IntArray?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(Color.Black.copy(alpha = 0.5f))) {
        // Cálculo dos pixels em Dispatchers.Default, com Bitmap reaproveitado (M29).
        val holder = rememberScopeBitmap(waveformData, ::waveformToPixels)

        Canvas(modifier = Modifier.fillMaxSize()) {
            holder.version // leitura de estado: invalida o draw quando o conteúdo muda
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
