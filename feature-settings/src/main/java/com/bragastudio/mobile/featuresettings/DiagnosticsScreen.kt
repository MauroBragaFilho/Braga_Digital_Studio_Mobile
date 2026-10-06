package com.bragastudio.mobile.featuresettings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bragastudio.mobile.common.components.BdsmCard
import com.bragastudio.mobile.common.components.BdsmScreen
import com.bragastudio.mobile.common.components.EmptyState
import com.bragastudio.mobile.common.components.StatusChip
import com.bragastudio.mobile.common.components.StatusKind
import com.bragastudio.mobile.common.components.bdsmTextFieldColors
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import com.bragastudio.mobile.corecapture.domain.CameraInfoModel
import com.bragastudio.mobile.coremedia.bsp.BspConnectionState

@Composable
fun DiagnosticsScreen(
    onNavigateUp: () -> Unit = {},
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val reportCopied = stringResource(R.string.diag_copied)
    val cameras by viewModel.availableCameras.collectAsStateWithLifecycle()
    val ndiSettings by viewModel.ndiSettings.collectAsStateWithLifecycle()
    val bspSettings by viewModel.bspSettings.collectAsStateWithLifecycle()
    val bspConnectionState by viewModel.bspConnectionState.collectAsStateWithLifecycle()
    val bspRttMs by viewModel.bspRttMs.collectAsStateWithLifecycle()
    val bspBitrateMbps by viewModel.bspBitrateMbps.collectAsStateWithLifecycle()
    val bspPacketLossPercent by viewModel.bspPacketLossPercent.collectAsStateWithLifecycle()

    BdsmScreen(
        title = stringResource(R.string.diag_title),
        onNavigateUp = onNavigateUp,
        scrollable = false,
        actions = {
            IconButton(onClick = {
                val report = generateReport(cameras)
                copyToClipboard(context, report)
                Toast.makeText(context, reportCopied, Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.diag_copy_cd))
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = BdsmTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.lg),
        ) {
            item {
                StreamingProtocolSwitchCard(
                    useBsp = bspSettings.isEnabled,
                    bspTargetHost = bspSettings.targetHost,
                    bspConnectionState = bspConnectionState,
                    bspRttMs = bspRttMs,
                    bspBitrateMbps = bspBitrateMbps,
                    bspPacketLossPercent = bspPacketLossPercent,
                    onTargetHostChange = { viewModel.setBspTargetHost(it) },
                    onSwitchToBsp = { viewModel.setActiveStreamingProtocol(useBsp = true) },
                    onSwitchToNdi = { viewModel.setActiveStreamingProtocol(useBsp = false) },
                )
            }
            if (cameras.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.NoPhotography,
                        title = stringResource(R.string.diag_empty_title),
                        message = stringResource(R.string.diag_empty_message),
                    )
                }
            }
            items(cameras, key = { it.id }, contentType = { "camera" }) { camera ->
                BdsmCard(modifier = Modifier.fillMaxWidth()) {
                    CameraCardContent(camera)
                }
            }
        }
    }
}

/**
 * Switch de dev pra alternar entre NDI e BSP de verdade: liga um flag no
 * SettingsRepository que o MediaGraph já observa, então isso já inicia/para a
 * transmissão real, não é só um mock visual.
 */
@Composable
private fun StreamingProtocolSwitchCard(
    useBsp: Boolean,
    bspTargetHost: String,
    bspConnectionState: BspConnectionState,
    bspRttMs: Long,
    bspBitrateMbps: Float,
    bspPacketLossPercent: Float,
    onTargetHostChange: (String) -> Boolean,
    onSwitchToBsp: () -> Unit,
    onSwitchToNdi: () -> Unit,
) {
    var hostInput by remember(bspTargetHost) { mutableStateOf(bspTargetHost) }
    // Host digitado inválido (não vazio): mostra erro e não grava.
    val hostInvalid = hostInput.isNotBlank() && !SettingsRules.isValidHost(hostInput)
    // BSP só pode ser ligado com um host válido já gravado.
    val canUseBsp = SettingsRules.isValidHost(bspTargetHost)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = BdsmTheme.shapes.container,
        color = BdsmTheme.colors.card,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
    ) {
        Column(
            modifier = Modifier.padding(BdsmTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
        ) {
            Text(
                stringResource(R.string.diag_proto_title),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.diag_proto_desc),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ProtocolChoiceButton(
                    label = "NDI",
                    selected = !useBsp,
                    modifier = Modifier.weight(1f),
                    onClick = onSwitchToNdi,
                )
                ProtocolChoiceButton(
                    label = "BSP",
                    selected = useBsp,
                    enabled = canUseBsp,
                    modifier = Modifier.weight(1f),
                    onClick = onSwitchToBsp,
                )
            }

            OutlinedTextField(
                value = hostInput,
                colors = bdsmTextFieldColors(),
                onValueChange = { hostInput = it },
                label = { Text(stringResource(R.string.diag_bsp_host_label)) },
                singleLine = true,
                isError = hostInvalid,
                supportingText = {
                    when {
                        hostInvalid -> Text(stringResource(R.string.diag_bsp_host_invalid))
                        !canUseBsp -> Text(stringResource(R.string.diag_bsp_host_missing))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(enabled = !hostInvalid, onClick = { onTargetHostChange(hostInput) }) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.diag_bsp_host_save_cd),
                            tint = if (hostInvalid) MaterialTheme.colorScheme.onSurfaceVariant else BdsmTheme.colors.success,
                        )
                    }
                },
            )

            if (useBsp) {
                val statusColor = when (bspConnectionState) {
                    BspConnectionState.CONNECTED -> BdsmTheme.colors.success

                    BspConnectionState.CONNECTING,
                    BspConnectionState.RECONNECTING,
                    -> BdsmTheme.colors.warning

                    BspConnectionState.ERROR -> MaterialTheme.colorScheme.error

                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                val statusLabel = stringResource(
                    when (bspConnectionState) {
                        BspConnectionState.DISCONNECTED -> R.string.diag_bsp_disconnected
                        BspConnectionState.CONNECTING -> R.string.diag_bsp_connecting
                        BspConnectionState.CONNECTED -> R.string.diag_bsp_connected
                        BspConnectionState.RECONNECTING -> R.string.diag_bsp_reconnecting
                        BspConnectionState.ERROR -> R.string.diag_bsp_error
                    },
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm),
                ) {
                    // Bolinha decorativa: o estado também está no texto ao lado.
                    Box(modifier = Modifier.size(10.dp).background(statusColor, CircleShape))
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    stringResource(R.string.diag_bsp_stats, bspRttMs, bspBitrateMbps, bspPacketLossPercent),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ProtocolChoiceButton(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val selectedDescription = stringResource(R.string.diag_proto_selected_cd, label)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { if (selected) contentDescription = selectedDescription },
        colors = if (selected) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        },
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f),
        )
        Text(
            text = value,
            style = BdsmTheme.type.metricSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.55f),
        )
    }
}

@Composable
private fun CameraCardContent(camera: CameraInfoModel) {
    val yes = stringResource(R.string.diag_yes)
    val no = stringResource(R.string.diag_no)
    fun yesNo(value: Boolean) = if (value) yes else no
    Column(
        modifier = Modifier.padding(BdsmTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm),
    ) {
        Text(
            text = stringResource(R.string.diag_camera_id, camera.id),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.sm)) {
            StatusChip(getFacingString(camera.facing), StatusKind.Info)
            StatusChip(getHardwareLevelString(camera.hardwareLevel), StatusKind.Neutral)
        }
        SpecRow(stringResource(R.string.diag_spec_name), camera.name)
        SpecRow(stringResource(R.string.diag_spec_lens_type), camera.lensType.name)
        SpecRow(stringResource(R.string.diag_spec_flash), yesNo(camera.hasFlash))
        SpecRow(stringResource(R.string.diag_spec_stabilization), yesNo(camera.stabilization))
        SpecRow(stringResource(R.string.diag_spec_ois), yesNo(camera.hasOis))
        SpecRow(stringResource(R.string.diag_spec_eis), yesNo(camera.hasEis))
        SpecRow(stringResource(R.string.diag_spec_torch), yesNo(camera.hasTorch))
        SpecRow(stringResource(R.string.diag_spec_hdr), yesNo(camera.supportsHdr))
        SpecRow(stringResource(R.string.diag_spec_hdr10), yesNo(camera.supportsHdr10))
        SpecRow(stringResource(R.string.diag_spec_max_fps), "${camera.maxFps}")
        SpecRow(stringResource(R.string.diag_spec_max_resolution), "${camera.maxResolution?.width ?: 0} x ${camera.maxResolution?.height ?: 0}")
        SpecRow(stringResource(R.string.diag_spec_high_speed), camera.highSpeedSizes.take(4).joinToString(" | ") { "${it.width}x${it.height}" })
        SpecRow(stringResource(R.string.diag_spec_focal), "${camera.focalLengths.joinToString(", ")} mm")
        SpecRow(stringResource(R.string.diag_spec_sensor), "${camera.sensorSize.width} x ${camera.sensorSize.height}")

        Text(
            text = stringResource(R.string.diag_capabilities, camera.capabilities.size),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = BdsmTheme.spacing.sm),
        )
        Text(
            text = camera.capabilities.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(R.string.diag_resolutions_top5),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = camera.resolutions.take(5).joinToString(" | ") { "${it.width}x${it.height}" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun getFacingString(facing: Int) = when (facing) {
    android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT -> "Front"
    android.hardware.camera2.CameraMetadata.LENS_FACING_BACK -> "Rear"
    android.hardware.camera2.CameraMetadata.LENS_FACING_EXTERNAL -> "External"
    else -> "Unknown"
}

private fun getHardwareLevelString(level: Int) = when (level) {
    android.hardware.camera2.CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
    android.hardware.camera2.CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
    android.hardware.camera2.CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
    android.hardware.camera2.CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
    android.hardware.camera2.CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
    else -> "UNKNOWN"
}

private fun generateReport(cameras: List<CameraInfoModel>): String {
    val sb = StringBuilder()
    for (info in cameras) {
        val facingStr = getFacingString(info.facing)
        val hardwareStr = getHardwareLevelString(info.hardwareLevel)
        val resLog = info.resolutions.joinToString("\n") { "${it.width}x${it.height}" }

        sb.appendLine("============================")
        sb.appendLine("Camera ID: ${info.id}")
        sb.appendLine("Facing:\n$facingStr")
        sb.appendLine("Hardware:\n$hardwareStr")
        sb.appendLine("Lens:\n${info.name}")
        sb.appendLine("Flash:\n${if (info.hasFlash) "Yes" else "No"}")
        sb.appendLine("OIS:\n${if (info.hasOis) "Yes" else "No"}")
        sb.appendLine("EIS:\n${if (info.hasEis) "Yes" else "No"}")
        sb.appendLine("Torch:\n${if (info.hasTorch) "Yes" else "No"}")
        sb.appendLine("HDR (Scene Mode):\n${if (info.supportsHdr) "Yes" else "No"}")
        sb.appendLine("HDR 10-bit (HLG10):\n${if (info.supportsHdr10) "Yes" else "No"}")
        sb.appendLine("Max FPS:\n${info.maxFps}")
        sb.appendLine("Max Resolution:\n${info.maxResolution?.width ?: 0}x${info.maxResolution?.height ?: 0}")
        sb.appendLine("High-Speed Sizes:\n${info.highSpeedSizes.joinToString(", ") { "${it.width}x${it.height}" }}")
        sb.appendLine("Focal:\n${info.focalLengths.joinToString(", ")}")
        sb.appendLine("Sensor:\n${info.sensorSize.width}x${info.sensorSize.height}")
        sb.appendLine("Resolutions:\n$resLog")
        sb.appendLine("============================\n")
    }
    return sb.toString()
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("BDSM Camera Diagnostics", text)
    clipboard.setPrimaryClip(clip)
}
