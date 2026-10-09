package com.bragastudio.mobile.featuresettings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bragastudio.mobile.common.components.BdsmBigButton
import com.bragastudio.mobile.common.components.BdsmBottomSheet
import com.bragastudio.mobile.common.components.BdsmGroupedList
import com.bragastudio.mobile.common.components.BdsmScreen
import com.bragastudio.mobile.common.components.BdsmTextButton
import com.bragastudio.mobile.common.components.BigButtonTone
import com.bragastudio.mobile.common.components.DetailLine
import com.bragastudio.mobile.common.components.SettingsSwitchItem
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import com.bragastudio.mobile.common.ui.theme.bdsmIsShortLandscape
import com.bragastudio.mobile.coremedia.domain.BspReceiver

/**
 * BSP (Braga Stream Protocol) v2, fonte: o mesmo desenho simples da tela NDI. Um círculo de estado, o
 * nome anunciado na rede, os receptores conectados (SÓ nomes) e UM botão grande no fundo. Detalhes
 * (RTT, taxa, quadros, perda) ficam uma camada abaixo. O vídeo só sai com o Monitor aberto: a nota é
 * fixa na tela.
 */
@Composable
fun BspScreen(
    onNavigateUp: () -> Unit,
    viewModel: BspViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var detailsOpen by rememberSaveable { mutableStateOf(false) }

    BdsmScreen(
        title = stringResource(R.string.module_bsp),
        onNavigateUp = onNavigateUp,
        scrollable = false,
    ) {
        val stateBlock: @Composable () -> Unit = { BspStateIndicator(state) }
        val bodyBlock: @Composable (Modifier) -> Unit = { modifier -> BspBody(state, modifier) }
        val actionsBlock: @Composable () -> Unit = {
            BdsmTextButton(
                onClick = { detailsOpen = true },
                modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = BdsmTheme.spacing.touchTarget),
            ) {
                Text(
                    text = stringResource(R.string.bsp_details),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            BspPrimaryActions(
                phase = state.phase,
                onStart = { viewModel.setEnabled(true) },
                onStop = { viewModel.setEnabled(false) },
                onRetry = viewModel::retry,
            )
        }
        if (bdsmIsShortLandscape()) {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.xl),
            ) {
                Column(
                    modifier = Modifier.weight(0.45f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    stateBlock()
                    Column { actionsBlock() }
                }
                bodyBlock(Modifier.weight(0.55f).fillMaxHeight())
            }
        } else {
            stateBlock()
            bodyBlock(Modifier.weight(1f).fillMaxWidth())
            actionsBlock()
        }
    }

    if (detailsOpen) {
        BspDetailsSheet(
            state = state,
            onAllowPlainChange = viewModel::setAllowPlainMedia,
            onDismiss = { detailsOpen = false },
        )
    }
}

/** Estado compacto no topo: círculo + rótulo + nome anunciado (nunca só cor: forma, texto e ícone mudam). */
@Composable
private fun BspStateIndicator(state: BspUiState) {
    val phase = state.phase
    val label = stringResource(
        when (phase) {
            BspScreenPhase.OFF -> R.string.bsp_state_off
            BspScreenPhase.STARTING -> R.string.bsp_state_starting
            BspScreenPhase.WAITING_RECEIVER -> R.string.bsp_state_waiting
            BspScreenPhase.WAITING_MONITOR -> R.string.bsp_state_waiting_monitor
            BspScreenPhase.STREAMING -> R.string.bsp_state_streaming
            BspScreenPhase.ERROR -> R.string.bsp_state_error
        },
    )
    val ring = when (phase) {
        BspScreenPhase.OFF -> MaterialTheme.colorScheme.outline
        BspScreenPhase.STARTING, BspScreenPhase.WAITING_RECEIVER, BspScreenPhase.WAITING_MONITOR -> BdsmTheme.colors.warning
        BspScreenPhase.STREAMING -> BdsmTheme.colors.success
        BspScreenPhase.ERROR -> MaterialTheme.colorScheme.error
    }
    val subtitle = when (phase) {
        BspScreenPhase.OFF -> stringResource(R.string.bsp_hint_off)
        BspScreenPhase.ERROR -> state.error ?: stringResource(R.string.bsp_hint_error)
        else -> state.announcedName
    }
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
                BspScreenPhase.OFF -> Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = null, tint = ring, modifier = Modifier.size(26.dp))
                BspScreenPhase.STARTING -> CircularProgressIndicator(color = ring, strokeWidth = 3.dp, modifier = Modifier.size(24.dp))
                BspScreenPhase.WAITING_RECEIVER, BspScreenPhase.WAITING_MONITOR -> Icon(Icons.Outlined.RadioButtonUnchecked, contentDescription = null, tint = ring, modifier = Modifier.size(26.dp))
                BspScreenPhase.STREAMING -> Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = ring, modifier = Modifier.size(26.dp))
                BspScreenPhase.ERROR -> Icon(Icons.Filled.Error, contentDescription = null, tint = ring, modifier = Modifier.size(26.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Centro da tela: a nota do Monitor (sempre visível) e a lista de receptores conectados. */
@Composable
private fun BspBody(state: BspUiState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.lg),
    ) {
        Spacer(Modifier.size(BdsmTheme.spacing.xs))
        MonitorNote()
        if (state.phase != BspScreenPhase.OFF && state.phase != BspScreenPhase.ERROR && state.phase != BspScreenPhase.STARTING) {
            ReceiversList(state.receivers)
        }
    }
}

@Composable
private fun MonitorNote() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BdsmTheme.shapes.item)
            .background(BdsmTheme.colors.card)
            .padding(BdsmTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
    ) {
        Icon(Icons.Filled.Videocam, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Text(
            text = stringResource(R.string.bsp_monitor_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ReceiversList(receivers: List<BspReceiver>) {
    BdsmGroupedList(label = stringResource(R.string.bsp_receivers_title)) {
        if (receivers.isEmpty()) {
            Text(
                text = stringResource(R.string.bsp_receivers_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(BdsmTheme.spacing.lg),
            )
        } else {
            receivers.forEach { receiver ->
                DetailLine(
                    label = receiver.name,
                    value = if (receiver.streaming) BspScreenLogic.formatRtt(receiver.rttMs) else stringResource(R.string.bsp_receiver_paused),
                    modifier = Modifier.padding(horizontal = BdsmTheme.spacing.lg),
                )
            }
        }
    }
}

/** Botão grande de ação (um por estado; no erro, "Tentar novamente" + Cancelar). */
@Composable
private fun BspPrimaryActions(
    phase: BspScreenPhase,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(bottom = BdsmTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm),
    ) {
        when (phase) {
            BspScreenPhase.OFF -> BdsmBigButton(
                text = stringResource(R.string.bsp_start),
                icon = Icons.Filled.PlayArrow,
                onClick = onStart,
            )

            BspScreenPhase.STARTING -> BdsmBigButton(
                text = stringResource(R.string.bsp_cancel),
                tone = BigButtonTone.Neutral,
                onClick = onStop,
            )

            BspScreenPhase.WAITING_RECEIVER,
            BspScreenPhase.WAITING_MONITOR,
            BspScreenPhase.STREAMING,
            -> BdsmBigButton(
                text = stringResource(R.string.bsp_stop),
                icon = Icons.Filled.Stop,
                tone = BigButtonTone.Danger,
                onClick = onStop,
            )

            BspScreenPhase.ERROR -> {
                BdsmBigButton(
                    text = stringResource(R.string.bsp_retry),
                    icon = Icons.Filled.Refresh,
                    onClick = onRetry,
                )
                BdsmBigButton(
                    text = stringResource(R.string.bsp_cancel),
                    tone = BigButtonTone.Neutral,
                    onClick = onStop,
                )
            }
        }
    }
}

/** Segunda camada: o que a pessoa técnica precisa (nome, estado, receptores, RTT, taxa, quadros, perda). */
@Composable
private fun BspDetailsSheet(
    state: BspUiState,
    onAllowPlainChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val stats = state.stats
    val stateText = stringResource(
        when (state.phase) {
            BspScreenPhase.OFF -> R.string.bsp_state_off
            BspScreenPhase.STARTING -> R.string.bsp_state_starting
            BspScreenPhase.WAITING_RECEIVER -> R.string.bsp_state_waiting
            BspScreenPhase.WAITING_MONITOR -> R.string.bsp_state_waiting_monitor
            BspScreenPhase.STREAMING -> R.string.bsp_state_streaming
            BspScreenPhase.ERROR -> R.string.bsp_state_error
        },
    )
    val receiverNames = state.receivers.joinToString { it.name }.ifEmpty { "—" }
    BdsmBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.bsp_details_title)) {
        DetailLine(label = stringResource(R.string.bsp_line_name), value = state.announcedName, mono = true)
        DetailLine(label = stringResource(R.string.bsp_line_state), value = stateText)
        DetailLine(label = stringResource(R.string.bsp_line_receivers), value = receiverNames)
        DetailLine(label = stringResource(R.string.bsp_line_rtt), value = BspScreenLogic.formatRtt(stats.rttMs))
        DetailLine(label = stringResource(R.string.bsp_line_bitrate), value = BspScreenLogic.formatBitrate(stats.bitrateMbps))
        DetailLine(label = stringResource(R.string.bsp_line_fps), value = BspScreenLogic.formatFps(stats.fps))
        DetailLine(label = stringResource(R.string.bsp_line_loss), value = BspScreenLogic.formatLoss(stats.lossPercent))
        DetailLine(
            label = stringResource(R.string.bsp_line_security),
            value = stringResource(if (state.allowPlainMedia) R.string.bsp_security_optional else R.string.bsp_security_aead),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm),
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(
                text = stringResource(R.string.bsp_pairing_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BdsmGroupedList {
            SettingsSwitchItem(
                icon = Icons.Filled.Warning,
                title = stringResource(R.string.bsp_plain_title),
                subtitle = stringResource(R.string.bsp_plain_sub),
                checked = state.allowPlainMedia,
                iconTint = BdsmTheme.colors.warning,
                onCheckedChange = onAllowPlainChange,
            )
        }
    }
}
