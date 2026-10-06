package com.bragastudio.mobile.featuresettings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bragastudio.mobile.common.components.BdsmTextButton
import com.bragastudio.mobile.common.components.ConfirmDialog
import com.bragastudio.mobile.common.components.SettingsDivider
import com.bragastudio.mobile.common.components.SettingsHelpText
import com.bragastudio.mobile.common.components.SettingsSection
import com.bragastudio.mobile.common.components.SettingsSwitchItem
import com.bragastudio.mobile.common.components.StatusLevel
import com.bragastudio.mobile.common.components.StatusRow
import com.bragastudio.mobile.common.components.rememberBdsmHaptics
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import com.bragastudio.mobile.network.auth.PairedClient
import java.text.DateFormat
import java.util.Date

/** Estado textual do servidor Link, em função do opt-in e de o servidor estar de fato no ar. */
enum class LinkServerStatus { OFF, STARTING, RUNNING }

fun linkServerStatus(enabled: Boolean, serverRunning: Boolean): LinkServerStatus = when {
    !enabled -> LinkServerStatus.OFF
    serverRunning -> LinkServerStatus.RUNNING
    else -> LinkServerStatus.STARTING
}

/**
 * Seção "Conectar ao OBS (BDSM Link)": liga/desliga o servidor, mostra se ele está no ar (ícone +
 * texto), o passo a passo com o IP deste celular (copiável) e os dispositivos pareados (revogar com
 * confirmação).
 */
@Composable
fun LinkSettingsSection(
    state: LinkUiState,
    onEnabledChange: (Boolean) -> Unit,
    onRevoke: (String) -> Unit,
    onRevokeAll: () -> Unit,
) {
    var clientToRevoke by remember { mutableStateOf<PairedClient?>(null) }
    var confirmRevokeAll by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val ip by produceState<String?>(initialValue = null, context) {
        LocalIp.observe(context).collect { value = it }
    }

    clientToRevoke?.let { client ->
        ConfirmDialog(
            title = stringResource(R.string.link_revoke_title),
            message = stringResource(R.string.link_revoke_message, client.name),
            confirmText = stringResource(R.string.link_revoke_confirm),
            onConfirm = {
                onRevoke(client.clientId)
                clientToRevoke = null
            },
            onDismiss = { clientToRevoke = null },
        )
    }
    if (confirmRevokeAll) {
        ConfirmDialog(
            title = stringResource(R.string.link_revoke_all_title),
            message = stringResource(R.string.link_revoke_all_message, state.paired.size),
            confirmText = stringResource(R.string.link_revoke_all),
            onConfirm = {
                onRevokeAll()
                confirmRevokeAll = false
            },
            onDismiss = { confirmRevokeAll = false },
        )
    }

    SettingsSection(stringResource(R.string.link_section_title), icon = Icons.Filled.Cast) {
        SettingsSwitchItem(
            icon = Icons.Filled.Cast,
            title = stringResource(R.string.link_switch_title),
            checked = state.enabled,
            subtitle = stringResource(R.string.link_switch_sub),
        ) { onEnabledChange(it) }

        SettingsDivider()
        val status = linkServerStatus(state.enabled, state.serverRunning)
        val (level, label) = when (status) {
            LinkServerStatus.OFF -> StatusLevel.Off to stringResource(R.string.link_status_off)
            LinkServerStatus.STARTING -> StatusLevel.Warning to stringResource(R.string.link_status_starting)
            LinkServerStatus.RUNNING -> StatusLevel.Ok to stringResource(R.string.link_status_running)
        }
        StatusRow(
            icon = Icons.Filled.Cast,
            label = stringResource(R.string.link_status_label),
            value = label,
            level = level,
            levelDescription = label,
        )

        if (state.enabled) {
            SettingsDivider()
            LinkSteps(ip = ip)
        }

        SettingsDivider()
        Text(
            text = stringResource(R.string.link_paired_title),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
        )
        if (state.paired.isEmpty()) {
            SettingsHelpText(stringResource(R.string.link_paired_none))
        } else {
            state.paired.forEach { client ->
                PairedClientRow(client = client, onRevoke = { clientToRevoke = client })
            }
            BdsmTextButton(
                onClick = { confirmRevokeAll = true },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.link_revoke_all), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Passo a passo para conectar o OBS, com o IP deste celular em destaque e botão de copiar. */
@Composable
private fun LinkSteps(ip: String?) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptics = rememberBdsmHaptics()
    val ipText = ip ?: stringResource(R.string.link_ip_none)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BdsmTheme.spacing.lg, vertical = BdsmTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.link_steps_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(stringResource(R.string.link_step_1), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.link_step_2), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = ipText,
                style = BdsmTheme.type.metricLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (ip != null) {
                val copied = stringResource(R.string.link_copied)
                val copyLabel = stringResource(R.string.link_copy_ip)
                BdsmTextButton(
                    onClick = {
                        haptics.confirm()
                        clipboard.setText(AnnotatedString(ip))
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(copyLabel)
                }
            }
        }
        Text(stringResource(R.string.link_step_3), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.link_step_4), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PairedClientRow(client: PairedClient, onRevoke: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(client.name.ifBlank { stringResource(R.string.link_device_unnamed) }, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.link_paired_at, formatPairedAt(client.pairedAtMs, stringResource(R.string.link_date_unknown))),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        IconButton(onClick = onRevoke) {
            Icon(
                Icons.Filled.LinkOff,
                contentDescription = stringResource(R.string.link_revoke_cd, client.name.ifBlank { stringResource(R.string.link_device_fallback) }),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun formatPairedAt(ms: Long, unknown: String): String = if (ms <= 0L) {
    unknown
} else {
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
}
