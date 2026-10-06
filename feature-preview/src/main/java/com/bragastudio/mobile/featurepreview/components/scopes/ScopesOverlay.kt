package com.bragastudio.mobile.featurepreview.components.scopes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bragastudio.mobile.core.domain.VideoScopes

@Composable
fun ScopesOverlay(
    videoScopes: VideoScopes,
    onCycleScope: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.clickable { onCycleScope() }) {
        // Os filhos preenchem o Box; repassar o [modifier] externo duplicava
        // tamanho/padding aplicados pelo chamador.
        when (videoScopes.activeType) {
            1 -> HistogramScope(
                histogramR = videoScopes.histogramR,
                histogramG = videoScopes.histogramG,
                histogramB = videoScopes.histogramB,
                modifier = Modifier.fillMaxSize(),
            )

            2 -> WaveformScope(
                waveformData = videoScopes.waveform,
                modifier = Modifier.fillMaxSize(),
            )

            3 -> VectorscopeScope(
                vectorscopeData = videoScopes.vectorscope,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
