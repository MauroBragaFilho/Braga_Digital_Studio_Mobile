package com.bragastudio.mobile.featuresettings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bragastudio.mobile.common.components.BdsmBottomSheet
import com.bragastudio.mobile.common.components.BdsmCard
import com.bragastudio.mobile.common.components.BdsmPrimaryButton
import com.bragastudio.mobile.common.components.BdsmSecondaryButton
import com.bragastudio.mobile.common.components.BdsmTextButton
import com.bragastudio.mobile.common.components.DetailLine
import com.bragastudio.mobile.common.components.StatusChip
import com.bragastudio.mobile.common.components.StatusKind
import com.bragastudio.mobile.common.components.rememberBdsmHaptics
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import com.bragastudio.mobile.coremedia.ndi.NdiSortMode
import com.bragastudio.mobile.coremedia.ndi.NdiSourceType
import com.bragastudio.mobile.coremedia.ndi.ProximityBand

private enum class NetworkView { RADAR, LIST }

/**
 * Radar/lista de fontes NDI embutido na tela NDI (sem navegar para outra tela). A descoberta liga
 * quando a tela fica visível e desliga ao sair (e só então mantém o MulticastLock). Alternância
 * Radar | Lista só com ícones (contentDescription para o TalkBack; a lista é a versão acessível).
 * Tocar numa fonte abre um cartão curto só com o NOME (nunca o IP).
 */
@Composable
fun NdiNetworkSection(
    onOpenPreview: (sourceName: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NdiNetworkViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(NetworkView.RADAR) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    // A animação do radar só roda com a tela visível (STARTED).
    var visible by remember { mutableStateOf(false) }

    LifecycleStartEffect(Unit) {
        visible = true
        viewModel.onScreenStart()
        onStopOrDispose {
            visible = false
            viewModel.onScreenStop()
        }
    }

    val content = state as? NdiRadarUiState.Content
    val items = content?.items.orEmpty()
    val selected = items.firstOrNull { it.id == selectedId }
    val details = items.firstOrNull { it.id == detailsId }

    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            when (view) {
                NetworkView.RADAR -> BoxWithConstraints(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 52.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    NdiRadar(
                        items = items,
                        mode = sortMode,
                        selectedId = selectedId,
                        onSelect = { selectedId = it },
                        animate = visible,
                        modifier = Modifier.size(minOf(maxWidth, maxHeight)),
                    )
                }

                NetworkView.LIST -> LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 52.dp),
                    verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm),
                ) {
                    items(items, key = { it.id }, contentType = { "source" }) { item ->
                        SourceRow(item = item, onClick = { selectedId = item.id })
                    }
                }
            }
            NetworkStatusLine(state = state, mode = sortMode, onRetry = viewModel::retry)
        }

        ViewControls(
            view = view,
            onView = { view = it },
            sortMode = sortMode,
            onToggleSort = {
                viewModel.setSortMode(if (sortMode == NdiSortMode.PROXIMITY) NdiSortMode.TYPE else NdiSortMode.PROXIMITY)
            },
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }

    selected?.let { item ->
        SourceActionSheet(
            item = item,
            onViewDevice = {
                selectedId = null
                detailsId = item.id
            },
            onPreview = {
                selectedId = null
                onOpenPreview(item.id)
            },
            onDismiss = { selectedId = null },
        )
    }
    details?.let { item -> DeviceDetailsSheet(item = item, onDismiss = { detailsId = null }) }
}

/** Botões só com ícone (alvo 48 dp): Radar | Lista e ordenar por proximidade/tipo. */
@Composable
private fun ViewControls(
    view: NetworkView,
    onView: (NetworkView) -> Unit,
    sortMode: NdiSortMode,
    onToggleSort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sortDesc = stringResource(if (sortMode == NdiSortMode.PROXIMITY) R.string.ndi_net_sort_proximity else R.string.ndi_net_sort_type)
    Row(
        modifier = modifier
            .clip(BdsmTheme.shapes.item)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconToggle(Icons.Filled.Radar, stringResource(R.string.ndi_net_view_radar), view == NetworkView.RADAR) { onView(NetworkView.RADAR) }
        IconToggle(Icons.AutoMirrored.Filled.ViewList, stringResource(R.string.ndi_net_view_list), view == NetworkView.LIST) { onView(NetworkView.LIST) }
        IconToggle(Icons.AutoMirrored.Filled.Sort, sortDesc, sortMode == NdiSortMode.TYPE, onToggleSort)
    }
}

@Composable
private fun IconToggle(icon: ImageVector, description: String, selected: Boolean, onClick: () -> Unit) {
    val haptics = rememberBdsmHaptics()
    IconButton(
        onClick = {
            haptics.tick()
            onClick()
        },
        modifier = Modifier
            .size(48.dp)
            .clip(BdsmTheme.shapes.chip)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Uma linha discreta sob o radar: procurando / nenhuma fonte / erro / aviso de posição aproximada. */
@Composable
private fun NetworkStatusLine(state: NdiRadarUiState, mode: NdiSortMode, onRetry: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = BdsmTheme.spacing.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            NdiRadarUiState.Searching -> Text(stringResource(R.string.ndi_net_searching), style = MaterialTheme.typography.labelMedium, color = muted)

            is NdiRadarUiState.Empty -> {
                Text(stringResource(R.string.ndi_net_empty_title), style = MaterialTheme.typography.labelMedium, color = muted)
                if (!state.hasNetwork) {
                    Text(stringResource(R.string.ndi_net_no_network), style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.Center)
                }
            }

            NdiRadarUiState.Error -> {
                Text(stringResource(R.string.ndi_net_error_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                BdsmTextButton(onClick = onRetry, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                    Text(stringResource(R.string.ndi3_retry))
                }
            }

            is NdiRadarUiState.Content -> {
                val rings = if (mode == NdiSortMode.PROXIMITY) {
                    listOf(R.string.ndi_net_prox_near, R.string.ndi_net_prox_medium, R.string.ndi_net_prox_far)
                } else {
                    listOf(R.string.ndi_net_type_bdsm, R.string.ndi_net_type_obs, R.string.ndi_net_type_other)
                }
                Text(
                    text = rings.map { stringResource(it) }.joinToString("  ·  ") + "  —  " + stringResource(R.string.ndi_net_approx),
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    textAlign = TextAlign.Center,
                )
                if (state.hiddenCount > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.ndi_net_hidden, state.hiddenCount, state.hiddenCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun typeLabel(type: NdiSourceType): String = stringResource(
    when (type) {
        NdiSourceType.BDSM -> R.string.ndi_net_type_bdsm
        NdiSourceType.OBS -> R.string.ndi_net_type_obs
        NdiSourceType.VMIX -> R.string.ndi_net_type_vmix
        NdiSourceType.OTHER -> R.string.ndi_net_type_other
    },
)

@Composable
private fun stateLabel(state: NdiDeviceState): String = stringResource(
    when (state) {
        NdiDeviceState.SELF -> R.string.ndi_net_state_self
        NdiDeviceState.AVAILABLE -> R.string.ndi_net_state_available
        NdiDeviceState.NO_RESPONSE -> R.string.ndi_net_state_no_response
    },
)

@Composable
private fun proximityLabel(item: NdiSourceUi): String = stringResource(
    when {
        item.state == NdiDeviceState.SELF -> R.string.ndi_net_prox_self
        item.state == NdiDeviceState.NO_RESPONSE -> R.string.ndi_net_prox_none
        item.isMeasuring -> R.string.ndi_net_prox_measuring
        item.band == ProximityBand.NEAR -> R.string.ndi_net_prox_near
        item.band == ProximityBand.MEDIUM -> R.string.ndi_net_prox_medium
        item.band == ProximityBand.FAR -> R.string.ndi_net_prox_far
        else -> R.string.ndi_net_prox_none
    },
)

/** Linha da lista (versão acessível do radar): nome, tipo, proximidade e estado em uma só leitura. */
@Composable
private fun SourceRow(item: NdiSourceUi, onClick: () -> Unit) {
    val type = typeLabel(item.type)
    val state = stateLabel(item.state)
    val proximity = proximityLabel(item)
    val description = stringResource(R.string.ndi_net_item_desc, item.displayName, type, proximity, state)
    BdsmCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = description }
                .padding(horizontal = BdsmTheme.spacing.md, vertical = BdsmTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$type · $proximity",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusChip(
                text = state,
                kind = when (item.state) {
                    NdiDeviceState.SELF -> StatusKind.Info
                    NdiDeviceState.AVAILABLE -> StatusKind.Success
                    NdiDeviceState.NO_RESPONSE -> StatusKind.Warning
                },
            )
        }
    }
}

/** Cartão curto: SÓ o nome do dispositivo e dois botões. Nunca mostra o IP. */
@Composable
private fun SourceActionSheet(
    item: NdiSourceUi,
    onViewDevice: () -> Unit,
    onPreview: () -> Unit,
    onDismiss: () -> Unit,
) {
    BdsmBottomSheet(onDismiss = onDismiss, title = item.displayName) {
        BdsmSecondaryButton(
            text = stringResource(R.string.ndi_net_view_device),
            icon = Icons.Filled.Visibility,
            onClick = onViewDevice,
            modifier = Modifier.fillMaxWidth(),
        )
        BdsmPrimaryButton(
            text = stringResource(R.string.ndi_net_open_preview),
            onClick = onPreview,
            enabled = item.canPreview,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!item.canPreview) {
            Text(
                text = stringResource(R.string.ndi_net_self_no_preview),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(BdsmTheme.spacing.sm))
    }
}

/** Detalhes do dispositivo: tipo, estado e proximidade aproximada (sem IP). */
@Composable
private fun DeviceDetailsSheet(item: NdiSourceUi, onDismiss: () -> Unit) {
    BdsmBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.ndi_net_device_title)) {
        DetailLine(label = stringResource(R.string.ndi_net_line_name), value = item.displayName)
        DetailLine(label = stringResource(R.string.ndi_net_line_type), value = typeLabel(item.type))
        DetailLine(label = stringResource(R.string.ndi_net_line_state), value = stateLabel(item.state))
        DetailLine(label = stringResource(R.string.ndi_net_line_proximity), value = proximityLabel(item))
        Text(
            text = stringResource(R.string.ndi_net_approx_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(BdsmTheme.spacing.sm))
    }
}
