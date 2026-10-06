package com.bragastudio.mobile.featuresettings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bragastudio.mobile.common.components.BdsmTextButton
import com.bragastudio.mobile.coremedia.ndi.NdiPreviewPhase

private val OverlayScrim = Color(0x73000000)
private val OverlayText = Color.White

/**
 * Preview NDI em tela cheia, imersivo: só a imagem recebida (letterbox, fundo preto). Os únicos
 * elementos sobrepostos são o nome do dispositivo (canto inferior, nunca o IP) e o botão de voltar
 * à tela inicial. Estados sem imagem (carregando, sem sinal, erro) aparecem discretos no centro.
 * O receptor abre ao ficar visível e fecha ao sair, escurecer a tela ou ir ao segundo plano.
 */
@Composable
fun NdiPreviewScreen(
    onNavigateBack: () -> Unit,
    onNavigateHome: () -> Unit,
    viewModel: NdiPreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val view = LocalView.current

    ImmersiveWindow()
    BackHandler(onBack = onNavigateBack)

    // Mantém a tela acesa só enquanto a prévia está na frente.
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    // 1 receptor por vez: abre ao ficar visível, fecha ao sair/escurecer/ir ao segundo plano.
    LifecycleStartEffect(viewModel) {
        viewModel.onScreenStart()
        onStopOrDispose { viewModel.onScreenStop() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val surfaceView = rememberUpdatedState(viewModel)
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = surfaceView.value.setSurface(holder.surface)
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) = surfaceView.value.setSurface(null)
                    })
                }
            },
            // Letterbox: a SurfaceView ocupa a maior área com a proporção do vídeo, centralizada.
            modifier = Modifier.align(Alignment.Center).aspectRatio(state.aspectRatio),
        )

        if (state.phase != NdiPreviewPhase.LIVE) {
            StatusOverlay(
                phase = state.phase,
                onRetry = viewModel::retry,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Voltar à tela inicial: pequeno, translúcido, alvo de 48 dp, no canto superior.
        IconButton(
            onClick = onNavigateHome,
            modifier = Modifier
                .align(Alignment.TopStart)
                .safeDrawingPadding()
                .padding(8.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(OverlayScrim),
        ) {
            Icon(
                imageVector = Icons.Filled.Home,
                contentDescription = stringResource(R.string.ndi_pv_back_home),
                tint = OverlayText,
            )
        }

        // Nome do dispositivo (nunca o IP): canto inferior, em área segura, com fundo translúcido.
        val labelDescription = stringResource(R.string.ndi_pv_label_desc, viewModel.displayName)
        Text(
            text = viewModel.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = OverlayText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .safeDrawingPadding()
                .padding(12.dp)
                .clip(MaterialTheme.shapes.small)
                .background(OverlayScrim)
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .semantics { contentDescription = labelDescription },
        )
    }
}

/** Estado sem imagem: discreto e central; "tentar de novo" só quando não há sinal ou deu erro. */
@Composable
private fun StatusOverlay(phase: NdiPreviewPhase, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val message = stringResource(
        when (phase) {
            NdiPreviewPhase.NO_SIGNAL -> R.string.ndi_pv_no_signal
            NdiPreviewPhase.ERROR -> R.string.ndi_pv_error
            else -> R.string.ndi_pv_connecting
        },
    )
    Column(
        modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (phase == NdiPreviewPhase.CONNECTING || phase == NdiPreviewPhase.IDLE) {
            CircularProgressIndicator(color = OverlayText.copy(alpha = 0.7f), strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = OverlayText.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
        )
        if (phase == NdiPreviewPhase.NO_SIGNAL || phase == NdiPreviewPhase.ERROR) {
            BdsmTextButton(onClick = onRetry) {
                Text(text = stringResource(R.string.ndi_pv_retry), color = OverlayText)
            }
        }
    }
}

/** Esconde as barras do sistema (o app já é imersivo; reaplica caso o usuário as tenha puxado). */
@Composable
private fun ImmersiveWindow() {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(view) {
        val window = context.findActivity()?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, view)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose { }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
