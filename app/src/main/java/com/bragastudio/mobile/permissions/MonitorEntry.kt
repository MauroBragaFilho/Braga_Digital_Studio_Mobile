package com.bragastudio.mobile.permissions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.bragastudio.mobile.R
import com.bragastudio.mobile.common.components.BdsmTextButton
import com.bragastudio.mobile.common.components.rememberBdsmHaptics
import com.bragastudio.mobile.common.ui.theme.BdsmTheme

/**
 * Pedido contextual de permissões ao abrir o Monitor: explica antes de pedir (câmera obrigatória,
 * microfone opcional e oferecido uma vez). Devolve a ação "abrir o Monitor" que a Home chama; se
 * tudo já estiver concedido, [onEnter] roda direto, sem diálogo.
 */
@Composable
fun rememberMonitorEntry(permissions: PermissionsState, onEnter: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val haptics = rememberBdsmHaptics()
    var flowActive by remember { mutableStateOf(false) }
    val currentOnEnter by rememberUpdatedState(onEnter)
    val step = PermissionLogic.monitorEntryStep(
        camera = permissions.status(AppPermission.Camera),
        microphone = permissions.status(AppPermission.Microphone),
        micOfferDismissed = permissions.micOfferDismissed,
    )

    if (flowActive) {
        when (step) {
            MonitorEntryStep.Ready -> LaunchedEffect(Unit) {
                flowActive = false
                currentOnEnter()
            }

            MonitorEntryStep.NeedCamera -> PermissionDialog(
                icon = Icons.Filled.Videocam,
                title = stringResource(R.string.entry_camera_title),
                body = stringResource(R.string.entry_camera_body),
                confirmLabel = stringResource(R.string.perm_action_allow),
                dismissLabel = stringResource(R.string.entry_not_now),
                onConfirm = {
                    haptics.confirm()
                    permissions.request(AppPermission.Camera)
                },
                onDismiss = { flowActive = false },
            )

            MonitorEntryStep.CameraBlocked -> PermissionDialog(
                icon = Icons.Filled.Videocam,
                title = stringResource(R.string.entry_camera_blocked_title),
                body = stringResource(R.string.entry_camera_blocked_body),
                confirmLabel = stringResource(R.string.perm_action_open_settings),
                dismissLabel = stringResource(R.string.entry_not_now),
                onConfirm = { openAppSettings(context) },
                onDismiss = { flowActive = false },
            )

            MonitorEntryStep.OfferMicrophone -> PermissionDialog(
                icon = Icons.Filled.Mic,
                title = stringResource(R.string.entry_mic_title),
                body = stringResource(R.string.entry_mic_body),
                confirmLabel = stringResource(R.string.perm_action_allow),
                dismissLabel = stringResource(R.string.entry_mic_skip),
                onConfirm = {
                    haptics.confirm()
                    // Só segue depois da resposta do sistema: o diálogo some quando o passo vira Ready.
                    permissions.request(AppPermission.Microphone) { permissions.dismissMicOffer() }
                },
                onDismiss = { permissions.dismissMicOffer() },
            )
        }
    }

    return {
        if (step == MonitorEntryStep.Ready) currentOnEnter() else flowActive = true
    }
}

@Composable
private fun PermissionDialog(
    icon: ImageVector,
    title: String,
    body: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = BdsmTheme.shapes.container,
        icon = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = { BdsmTextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { BdsmTextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}
