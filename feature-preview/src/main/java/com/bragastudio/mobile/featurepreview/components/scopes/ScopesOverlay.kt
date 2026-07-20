package com.bragastudio.mobile.featurepreview.components.scopes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bragastudio.mobile.core.domain.VideoScopes

@Composable
fun ScopesOverlay(
    videoScopes: VideoScopes,
    onCycleScope: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.clickable { onCycleScope() }) {
        when (videoScopes.activeType) {
            1 -> HistogramScope(
                histogramR = videoScopes.histogramR,
                histogramG = videoScopes.histogramG,
                histogramB = videoScopes.histogramB,
                modifier = modifier
            )
            2 -> WaveformScope(
                waveformData = videoScopes.waveform,
                modifier = modifier
            )
            3 -> VectorscopeScope(
                vectorscopeData = videoScopes.vectorscope,
                modifier = modifier
            )
        }
    }
}
