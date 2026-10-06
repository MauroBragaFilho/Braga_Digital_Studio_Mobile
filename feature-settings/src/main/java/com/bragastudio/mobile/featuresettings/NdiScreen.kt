package com.bragastudio.mobile.featuresettings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bragastudio.mobile.common.components.BdsmBigButton
import com.bragastudio.mobile.common.components.BdsmBottomSheet
import com.bragastudio.mobile.common.components.BdsmScreen
import com.bragastudio.mobile.common.components.BdsmSecondaryButton
import com.bragastudio.mobile.common.components.BdsmTextButton
import com.bragastudio.mobile.common.components.BigButtonTone
import com.bragastudio.mobile.common.components.DetailLine
import com.bragastudio.mobile.common.components.rememberBdsmHaptics
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import com.bragastudio.mobile.common.ui.theme.bdsmIsShortLandscape
import com.bragastudio.mobile.corecapture.status.CameraStatus

/**
 * NDI v3: extremamente simples. Um círculo de estado, o nome do stream (quando ativo) e UM botão
 * grande no fundo (alcance do polegar). Detalhes e opções avançadas ficam uma camada abaixo.
 */
@Composable
fun NdiScreen(
    onOpenAdvanced: () -> Unit,
    onOpenPreview: (sourceName: String) -> Unit,
    viewModel: NdiSetupViewModel = hiltViewModel(),
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val network by viewModel.network.collectAsStateWithLifecycle()
    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val camera by viewModel.cameraStatus.collectAsStateWithLifecycle()
    var detailsOpen by rememberSaveable { mutableStateOf(false) }

    val hasNetwork = network.ipAddress != null
    val sourceLabel = NdiStatus.sourceLabel(config.streamName)

    BdsmScreen(
        title = stringResource(R.string.module_ndi),
        onNavigateUp = {},
        showBack = false,
        scrollable = false,
    ) {
        val stateBlock: @Composable () -> Unit = {
            NdiStateIndicator(phase = phase, sourceLabel = sourceLabel, hasNetwork = hasNetwork)
        }
        val actionsBlock: @Composable () -> Unit = {
            BdsmTextButton(
                onClick = { detailsOpen = true },
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = BdsmTheme.spacing.touchTarget),
            ) {
                Text(
                    text = stringResource(R.string.ndi3_details),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            NdiPrimaryActions(
                phase = phase,
                onStart = { viewModel.setNdiEnabled(true) },
                onStop = { viewModel.setNdiEnabled(false) },
                onRetry = viewModel::retry,
            )
        }
        if (bdsmIsShortLandscape()) {
            // Paisagem de celular: estado e ações à esquerda, radar/lista ocupando a altura toda à direita
            // (em coluna única o radar ficava sem altura).
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.xl),
            ) {
                Column(
                    modifier = Modifier.weight(0.4f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    stateBlock()
                    Column { actionsBlock() }
                }
                NdiNetworkSection(
                    onOpenPreview = onOpenPreview,
                    modifier = Modifier.weight(0.6f).fillMaxHeight(),
                )
            }
        } else {
            // Topo: estado da transmissão compacto. Centro: radar/lista da rede. Fundo: ações.
            stateBlock()
            NdiNetworkSection(
                onOpenPreview = onOpenPreview,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            actionsBlock()
        }
    }

    if (detailsOpen) {
        NdiDetailsSheet(
            sourceLabel = sourceLabel,
            ipAddress = network.ipAddress,
            phase = phase,
            connections = metrics.connectionCount,
            hasNetwork = hasNetwork,
            quality = config.preset.label,
            camera = camera,
            onAdvanced = {
                detailsOpen = false
                onOpenAdvanced()
            },
            onDismiss = { detailsOpen = false },
        )
    }
}

/** Estado compacto no topo: círculo pequeno + rótulo + nome do stream (nunca só cor: forma, texto e ícone mudam). */
@Composable
private fun NdiStateIndicator(phase: NdiScreenPhase, sourceLabel: String, hasNetwork: Boolean) {
    val label = stringResource(
        when (phase) {
            NdiScreenPhase.OFF -> R.string.ndi3_state_off
            NdiScreenPhase.STARTING -> R.string.ndi3_state_starting
            NdiScreenPhase.ACTIVE -> R.string.ndi3_state_on
            NdiScreenPhase.ERROR -> R.string.ndi3_state_error
        },
    )
    val ring = when (phase) {
        NdiScreenPhase.OFF -> MaterialTheme.colorScheme.outline
        NdiScreenPhase.STARTING -> BdsmTheme.colors.warning
        NdiScreenPhase.ACTIVE -> BdsmTheme.colors.success
        NdiScreenPhase.ERROR -> MaterialTheme.colorScheme.error
    }
    Column(verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(ring.copy(alpha = 0.12f))
                    .border(width = 3.dp, color = ring, shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                when (phase) {
                    NdiScreenPhase.OFF -> Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = null, tint = ring, modifier = Modifier.size(26.dp))
                    NdiScreenPhase.STARTING -> CircularProgressIndicator(color = ring, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                    NdiScreenPhase.ACTIVE -> Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = ring, modifier = Modifier.size(26.dp))
                    NdiScreenPhase.ERROR -> Icon(Icons.Filled.Error, contentDescription = null, tint = ring, modifier = Modifier.size(26.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (phase == NdiScreenPhase.ERROR) stringResource(R.string.ndi3_hint_error) else sourceLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!hasNetwork) NoNetworkNote()
    }
}

@Composable
private fun NoNetworkNote() {
    val context = LocalContext.current
    val failed = stringResource(R.string.ndi_open_wifi_failed)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm),
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = BdsmTheme.colors.warning, modifier = Modifier.size(20.dp))
        Text(
            text = stringResource(R.string.ndi3_no_network),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        BdsmTextButton(
            onClick = {
                try {
                    context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.defaultMinSize(minHeight = BdsmTheme.spacing.touchTarget),
        ) { Text(stringResource(R.string.ndi3_open_wifi)) }
    }
}

/** Botão grande de ação (um por estado; no erro, "Tentar novamente" + Cancelar). */
@Composable
private fun NdiPrimaryActions(
    phase: NdiScreenPhase,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(bottom = BdsmTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm),
    ) {
        when (phase) {
            NdiScreenPhase.OFF -> BdsmBigButton(
                text = stringResource(R.string.ndi3_start),
                icon = Icons.Filled.PlayArrow,
                onClick = onStart,
            )

            NdiScreenPhase.STARTING -> BdsmBigButton(
                text = stringResource(R.string.ndi3_cancel),
                tone = BigButtonTone.Neutral,
                onClick = onStop,
            )

            NdiScreenPhase.ACTIVE -> BdsmBigButton(
                text = stringResource(R.string.ndi3_stop),
                icon = Icons.Filled.Stop,
                tone = BigButtonTone.Danger,
                onClick = onStop,
            )

            NdiScreenPhase.ERROR -> {
                BdsmBigButton(
                    text = stringResource(R.string.ndi3_retry),
                    icon = Icons.Filled.Refresh,
                    onClick = onRetry,
                )
                BdsmBigButton(
                    text = stringResource(R.string.ndi3_cancel),
                    tone = BigButtonTone.Neutral,
                    onClick = onStop,
                )
            }
        }
    }
}

/** Segunda camada: o que a pessoa técnica precisa (nome, IP, estado, rede, qualidade) + avançado. */
@Composable
private fun NdiDetailsSheet(
    sourceLabel: String,
    ipAddress: String?,
    phase: NdiScreenPhase,
    connections: Int,
    hasNetwork: Boolean,
    quality: String,
    camera: CameraStatus,
    onAdvanced: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptics = rememberBdsmHaptics()
    val copied = stringResource(R.string.ndi_copied)
    val stateText = when (phase) {
        NdiScreenPhase.OFF -> stringResource(R.string.ndi3_state_off_short)

        NdiScreenPhase.STARTING -> stringResource(R.string.ndi3_state_starting)

        NdiScreenPhase.ERROR -> stringResource(R.string.ndi3_state_error)

        NdiScreenPhase.ACTIVE -> when {
            connections > 1 -> stringResource(R.string.ndi_phase_live_many, connections)
            connections == 1 -> stringResource(R.string.ndi_phase_live_one)
            else -> stringResource(R.string.ndi_phase_waiting)
        }
    }
    BdsmBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.ndi3_details_title)) {
        DetailLine(label = stringResource(R.string.ndi3_line_name), value = sourceLabel, mono = true)
        DetailLine(label = stringResource(R.string.ndi3_line_ip), value = ipAddress ?: "—", mono = true)
        DetailLine(label = stringResource(R.string.ndi3_line_state), value = stateText)
        DetailLine(
            label = stringResource(R.string.ndi3_line_network),
            value = stringResource(if (hasNetwork) R.string.ndi3_network_ok else R.string.ndi3_network_none),
        )
        DetailLine(label = stringResource(R.string.ndi3_line_quality), value = quality)
        DetailLine(
            label = stringResource(R.string.ndi3_line_camera),
            value = when (camera) {
                is CameraStatus.Ready -> camera.badge
                CameraStatus.Loading -> "…"
                CameraStatus.NoPermission -> stringResource(R.string.ndi3_camera_no_permission)
                CameraStatus.Unavailable -> stringResource(R.string.ndi3_camera_none)
            },
        )
        Spacer(Modifier.size(BdsmTheme.spacing.xs))
        BdsmSecondaryButton(
            text = stringResource(R.string.ndi_copy) + " " + stringResource(R.string.ndi3_line_name).lowercase(),
            icon = Icons.Filled.ContentCopy,
            onClick = {
                haptics.confirm()
                clipboard.setText(AnnotatedString(sourceLabel))
                Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        BdsmSecondaryButton(
            text = stringResource(R.string.ndi3_advanced),
            icon = Icons.Filled.Tune,
            onClick = onAdvanced,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
