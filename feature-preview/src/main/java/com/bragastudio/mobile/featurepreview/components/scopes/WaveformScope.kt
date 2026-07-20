package com.bragastudio.mobile.featurepreview.components.scopes

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality

@Composable
fun WaveformScope(
    waveformData: IntArray?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(Color.Black.copy(alpha = 0.5f))) {
        val bitmap = remember(waveformData) {
            if (waveformData == null) return@remember null
            val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(256 * 256)
            
            // Find max for normalization
            var maxDensity = 1
            for (i in waveformData.indices) {
                if (waveformData[i] > maxDensity) maxDensity = waveformData[i]
            }
            
            // Generate pixels
            // In Waveform, X is column, Y is level (0 at bottom, 255 at top)
            for (y in 0 until 256) {
                for (x in 0 until 256) {
                    val packed = waveformData[y * 256 + x]
                    val bmpY = 255 - y // invert Y so 0 is at bottom
                    val idx = bmpY * 256 + x
                    
                    if (packed != 0) {
                        val rCount = (packed ushr 24) and 0xFF
                        val gCount = (packed ushr 16) and 0xFF
                        val bCount = (packed ushr 8) and 0xFF
                        val lCount = packed and 0xFF
                        
                        val rInt = if (rCount > 0) 3 + (rCount * 10) else 0
                        val gInt = if (gCount > 0) 3 + (gCount * 10) else 0
                        val bInt = if (bCount > 0) 3 + (bCount * 10) else 0
                        val lInt = if (lCount > 0) 50 + (lCount * 10) else 0
                        
                        val outR = (rInt + lInt).coerceIn(0, 255)
                        val outG = (gInt + lInt).coerceIn(0, 255)
                        val outB = (bInt + lInt).coerceIn(0, 255)
                        
                        val maxIntensity = maxOf(outR, maxOf(outG, outB))
                        
                        if (maxIntensity > 0) {
                            val a = maxIntensity
                            val r = (outR * 255 / maxIntensity).coerceIn(0, 255)
                            val g = (outG * 255 / maxIntensity).coerceIn(0, 255)
                            val b = (outB * 255 / maxIntensity).coerceIn(0, 255)
                            pixels[idx] = android.graphics.Color.argb(a, r, g, b)
                        } else {
                            pixels[idx] = android.graphics.Color.TRANSPARENT
                        }
                    } else {
                        pixels[idx] = android.graphics.Color.TRANSPARENT
                    }
                }
            }
            bmp.setPixels(pixels, 0, 256, 0, 0, 256, 256)
            bmp.asImageBitmap()
        }

        if (bitmap != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawImage(
                    image = bitmap,
                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = FilterQuality.None
                )
            }
        }
    }
}
