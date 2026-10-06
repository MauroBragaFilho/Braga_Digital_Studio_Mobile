package com.bragastudio.mobile.featurepreview.components.scopes

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/**
 * Um único Bitmap 256x256 + buffer de pixels reaproveitados a cada tick (M29):
 * antes cada atualização (~10 Hz) alocava Bitmap + IntArray(65536) na composição.
 * [version] é lido no draw do Canvas para invalidar quando o conteúdo muda.
 */
internal class ScopeBitmapHolder {
    val bitmap: Bitmap = Bitmap.createBitmap(SCOPE_SIZE, SCOPE_SIZE, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(SCOPE_PIXELS)
    val image: ImageBitmap = bitmap.asImageBitmap()
    var version by mutableStateOf(0)
    var hasContent by mutableStateOf(false)

    fun publish() {
        bitmap.setPixels(pixels, 0, SCOPE_SIZE, 0, 0, SCOPE_SIZE, SCOPE_SIZE)
        hasContent = true
        version++
    }
}

/**
 * Observa [data] e converte para pixels em Dispatchers.Default, em série (collect
 * sobre um flow conflacionado: nunca dois cálculos escrevendo no mesmo buffer e
 * sempre processando o dado mais recente). Só a cópia final para o Bitmap roda na Main.
 */
@Composable
internal fun rememberScopeBitmap(
    data: IntArray?,
    fill: (IntArray, IntArray) -> Unit,
): ScopeBitmapHolder {
    val holder = remember { ScopeBitmapHolder() }
    val latest by rememberUpdatedState(data)
    DisposableEffectRecycle(holder)
    LaunchedEffect(holder) {
        snapshotFlow { latest }.conflate().collect { d ->
            if (d == null) {
                holder.hasContent = false
                holder.version++
            } else {
                withContext(Dispatchers.Default) { fill(d, holder.pixels) }
                holder.publish()
            }
        }
    }
    return holder
}

@Composable
private fun DisposableEffectRecycle(holder: ScopeBitmapHolder) {
    androidx.compose.runtime.DisposableEffect(holder) {
        onDispose { holder.bitmap.recycle() }
    }
}
