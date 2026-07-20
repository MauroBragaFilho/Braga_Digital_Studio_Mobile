package com.bragastudio.mobile.featurepreview.components.scopes

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke

@Composable
fun VectorscopeScope(
    vectorscopeData: IntArray?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(Color.Black.copy(alpha = 0.5f))) {
        val bitmap = remember(vectorscopeData) {
            if (vectorscopeData == null) return@remember null
            val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(256 * 256)
            
            var maxDensity = 1
            for (i in vectorscopeData.indices) {
                if (vectorscopeData[i] > maxDensity) maxDensity = vectorscopeData[i]
            }
            
            // X is U, Y is V. Origin at center.
            for (v in 0 until 256) {
                for (u in 0 until 256) {
                    val density = vectorscopeData[v * 256 + u]
                    val bmpY = 255 - v // invert Y so 255 is top
                    val idx = bmpY * 256 + u
                    if (density > 0) {
                        // Base intensity of 150 so it's always visible, up to 255
                        val intensity = (150 + (density.toFloat() / maxDensity * 255 * 10)).toInt().coerceIn(0, 255)
                        // Y = 255 for MAX brightness of the RGB colors generated
                        val y = 255f
                        val cb = u - 128f
                        val cr = v - 128f
                        
                        var r = (y + 1.402f * cr).toInt()
                        var g = (y - 0.344136f * cb - 0.714136f * cr).toInt()
                        var b = (y + 1.772f * cb).toInt()
                        
                        r = r.coerceIn(0, 255)
                        g = g.coerceIn(0, 255)
                        b = b.coerceIn(0, 255)
                        
                        pixels[idx] = android.graphics.Color.argb(intensity, r, g, b)
                    } else {
                        pixels[idx] = android.graphics.Color.TRANSPARENT
                    }
                }
            }
            bmp.setPixels(pixels, 0, 256, 0, 0, 256, 256)
            bmp.asImageBitmap()
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = minOf(size.width, size.height) / 2f
            
            // Draw reticle
            drawCircle(
                color = Color.White.copy(alpha = 0.3f),
                radius = radius,
                center = center,
                style = Stroke(width = 1f)
            )
            drawLine(
                color = Color.White.copy(alpha = 0.3f),
                start = Offset(center.x, 0f),
                end = Offset(center.x, size.height),
                strokeWidth = 1f
            )
            drawLine(
                color = Color.White.copy(alpha = 0.3f),
                start = Offset(0f, center.y),
                end = Offset(size.width, center.y),
                strokeWidth = 1f
            )

            if (bitmap != null) {
                drawImage(
                    image = bitmap,
                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.None
                )
            }
        }
    }
}
