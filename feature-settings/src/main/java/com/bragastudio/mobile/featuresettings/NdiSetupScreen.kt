package com.bragastudio.mobile.featuresettings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bragastudio.mobile.common.components.BdsmCard
import com.bragastudio.mobile.common.components.BdsmScreen
import com.bragastudio.mobile.common.components.BdsmSecondaryButton
import com.bragastudio.mobile.common.components.BdsmTextButton
import com.bragastudio.mobile.common.components.SegmentedChoice
import com.bragastudio.mobile.common.components.SettingsDivider
import com.bragastudio.mobile.common.components.SettingsItem
import com.bragastudio.mobile.common.components.SettingsSection
import com.bragastudio.mobile.common.components.SettingsSwitchItem
import com.bragastudio.mobile.common.components.StatusLevel
import com.bragastudio.mobile.common.components.StatusRow
import com.bragastudio.mobile.common.components.bdsmClickable
import com.bragastudio.mobile.common.components.bdsmTextFieldColors
import com.bragastudio.mobile.common.components.icon
import com.bragastudio.mobile.common.components.rememberBdsmHaptics
import com.bragastudio.mobile.common.components.tint
import com.bragastudio.mobile.common.ui.theme.BdsmTheme

/**
 * Configurações avançadas do NDI (UX v3): a tela principal de NDI é só ligar/desligar; aqui ficam
 * o passo a passo, o som, o nome na rede, o tamanho da imagem e os detalhes técnicos.
 */
@Composable
fun NdiAdvancedScreen(
    viewModel: NdiSetupViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
) {
    val config by viewModel.config.collectAsStateWithLifecycle()
    val metrics by viewModel.metrics.collectAsStateWithLifecycle()
    val network by viewModel.network.collectAsStateWithLifecycle()

    val hasNetwork = network.ipAddress != null

    // Nome digitado (estado local): só é gravado em onDone/perda de foco (M46). Alimenta o exemplo ao vivo.
    var nameText by remember(config.streamName) { mutableStateOf(config.streamName) }
    val liveName = SettingsRules.effectiveStreamName(SettingsRules.sanitizeStreamName(nameText), viewModel.defaultStreamName)

    // Coluna única (rolável, largura limitada pelo BdsmScreen) em retrato e paisagem.
    BdsmScreen(
        title = stringResource(R.string.ndi_advanced_title),
        onNavigateUp = onNavigateBack,
        actions = {
            IconButton(onClick = { viewModel.refreshNetwork() }) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.ndi_refresh),
                )
            }
        },
    ) {
        // Um passo por vez: só o próximo passo aparece; "Ver todos" abre a lista completa.
        NdiStepsCard(
            sourceLabel = NdiStatus.sourceLabel(liveName),
            hasNetwork = hasNetwork,
            enabled = config.enabled,
            connected = metrics.connectionCount > 0,
        )

        BdsmCard(modifier = Modifier.fillMaxWidth()) {
            SettingsSwitchItem(
                icon = if (config.audioEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                title = stringResource(R.string.ndi_audio_title),
                subtitle = stringResource(if (config.audioEnabled) R.string.ndi_audio_on else R.string.ndi_audio_off),
                checked = config.audioEnabled,
                onCheckedChange = viewModel::setNdiAudioEnabled,
            )
        }

        NdiNameCard(
            nameText = nameText,
            onNameChange = { nameText = it.take(SettingsRules.MAX_STREAM_NAME_LENGTH) },
            liveName = liveName,
            defaultStreamName = viewModel.defaultStreamName,
            onCommit = {
                viewModel.commitStreamName(nameText)
                // Mostra o valor efetivo (vazio vira o nome padrão), mesmo que o gravado não mude.
                nameText = SettingsRules.effectiveStreamName(SettingsRules.sanitizeStreamName(nameText), viewModel.defaultStreamName)
            },
        )

        NdiQualityCard(selectedPreset = config.preset, onSelectPreset = viewModel::setPreset)

        NdiDetailsCard(
            isNdiActive = config.enabled,
            bitrateMbps = metrics.bitrateMbps,
            latencyMs = metrics.pipelineLatencyMs,
            frameDropPct = metrics.frameDropPct,
            lastSentResolution = metrics.lastSentResolution,
            rawInputMbps = metrics.rawInputMbps,
            bitrateIsEstimate = metrics.bitrateIsEstimate,
        )
    }
}

// ---------------------------------------------------------------------------
// 2. Passo a passo (cada passo ganha um check quando já foi cumprido)
// ---------------------------------------------------------------------------

@Composable
private fun NdiStepsCard(
    sourceLabel: String,
    hasNetwork: Boolean,
    enabled: Boolean,
    connected: Boolean,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptics = rememberBdsmHaptics()
    val copiedMsg = stringResource(R.string.ndi_copied)
    var showAll by rememberSaveable { mutableStateOf(false) }
    val done = booleanArrayOf(hasNetwork, enabled, connected, connected)
    // Passo atual = primeiro ainda não cumprido (4 = tudo pronto).
    val current = done.indexOfFirst { !it }.let { if (it < 0) 4 else it }
    val texts = listOf(
        stringResource(R.string.ndi_step_1),
        stringResource(R.string.ndi_step_2),
        stringResource(R.string.ndi_step_3),
        stringResource(R.string.ndi_step_4),
    )
    SettingsSection(title = stringResource(if (current >= 4) R.string.ndi_steps_done_title else R.string.ndi_steps_next_title), icon = Icons.Filled.Wifi) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BdsmTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
        ) {
            if (showAll) {
                texts.forEachIndexed { i, t -> StepLine(i + 1, t, done = done[i]) }
            } else if (current < 4) {
                StepLine(current + 1, texts[current], done = false)
            }
            // Nome exato da lista do receptor: aparece a partir do passo em que ele é necessário.
            if (showAll || current >= 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = sourceLabel,
                        style = BdsmTheme.type.metricLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    BdsmTextButton(
                        onClick = {
                            haptics.confirm()
                            clipboard.setText(AnnotatedString(sourceLabel))
                            Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ndi_copy))
                    }
                }
            }
            BdsmTextButton(onClick = { showAll = !showAll }) {
                Text(stringResource(if (showAll) R.string.ndi_steps_hide else R.string.ndi_steps_show_all))
            }
        }
    }
}

@Composable
private fun StepLine(number: Int, text: String, done: Boolean) {
    val level = if (done) StatusLevel.Ok else StatusLevel.Off
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
    ) {
        Text(
            text = "$number.",
            style = BdsmTheme.type.metric,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(22.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (done) {
            Icon(
                imageVector = level.icon(),
                contentDescription = stringResource(R.string.ndi_step_done),
                tint = level.tint(),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 3. Nome (com exemplo ao vivo)
// ---------------------------------------------------------------------------

@Composable
private fun NdiNameCard(
    nameText: String,
    onNameChange: (String) -> Unit,
    liveName: String,
    defaultStreamName: String,
    onCommit: () -> Unit,
) {
    var hadFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    SettingsSection(title = stringResource(R.string.ndi_name_title)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BdsmTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm),
        ) {
            OutlinedTextField(
                value = nameText,
                colors = bdsmTextFieldColors(),
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.ndi_name_label)) },
                placeholder = { Text(defaultStreamName) },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { state ->
                        // Perdeu o foco depois de tê-lo: grava (sem gravar a cada tecla).
                        if (hadFocus && !state.isFocused) onCommit()
                        hadFocus = state.isFocused
                    },
                shape = BdsmTheme.shapes.item,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    onCommit()
                    focusManager.clearFocus()
                }),
            )
            Text(
                text = stringResource(R.string.ndi_name_preview, NdiStatus.sourceLabel(liveName)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.ndi_name_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 4. Qualidade da imagem enviada
// ---------------------------------------------------------------------------

@Composable
private fun NdiQualityCard(
    selectedPreset: StreamPreset,
    onSelectPreset: (StreamPreset) -> Unit,
) {
    SettingsSection(title = stringResource(R.string.ndi_quality_title)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(BdsmTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
        ) {
            SegmentedChoice(
                options = StreamPreset.entries.toList(),
                selected = selectedPreset,
                label = { it.label },
                onSelect = onSelectPreset,
            )
            Text(
                text = stringResource(R.string.ndi_quality_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 5. Detalhes técnicos (recolhidos)
// ---------------------------------------------------------------------------

@Composable
private fun NdiDetailsCard(
    isNdiActive: Boolean,
    // 0 = sem dado (NDI parado): a UI mostra "--" em vez de um valor inventado.
    bitrateMbps: Int,
    latencyMs: Int,
    frameDropPct: Float,
    lastSentResolution: Pair<Int, Int>?,
    rawInputMbps: Int,
    bitrateIsEstimate: Boolean,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    BdsmCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .bdsmClickable(onClick = { open = !open }, role = Role.Button)
                .padding(horizontal = BdsmTheme.spacing.lg, vertical = BdsmTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
        ) {
            Icon(
                imageVector = Icons.Filled.Speed,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.ndi_details_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.ndi_details_sub),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            SettingsDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(BdsmTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
            ) {
                MetricLine(
                    stringResource(R.string.ndi_metric_resolution),
                    lastSentResolution?.let { (w, h) -> "${w}x$h" } ?: "--",
                )
                MetricLine(
                    stringResource(if (bitrateIsEstimate) R.string.ndi_metric_bandwidth_estimate else R.string.ndi_metric_bandwidth),
                    formatBitrateMbps(bitrateMbps.takeIf { isNdiActive }).let {
                        if (bitrateIsEstimate && it != "--") "≈ $it" else it
                    },
                )
                MetricLine(stringResource(R.string.ndi_metric_latency), formatLatencyMs(latencyMs.takeIf { isNdiActive }))
                MetricLine(stringResource(R.string.ndi_metric_raw), formatBitrateMbps(rawInputMbps.takeIf { isNdiActive }))
                MetricLine(
                    stringResource(R.string.ndi_metric_drops),
                    if (isNdiActive) "${"%.1f".format(java.util.Locale.US, frameDropPct)}%" else "--",
                )
                MetricLine(stringResource(R.string.ndi_metric_format), "NDI (RGBA)")
            }
        }
    }
}

@Composable
private fun MetricLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = BdsmTheme.type.metric,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
