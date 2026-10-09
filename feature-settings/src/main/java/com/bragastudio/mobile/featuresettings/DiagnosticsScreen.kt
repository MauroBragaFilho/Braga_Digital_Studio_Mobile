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

@Composable
fun DiagnosticsScreen(
    onNavigateUp: () -> Unit = {},
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val reportCopied = stringResource(R.string.diag_copied)
    val cameras by viewModel.availableCameras.collectAsStateWithLifecycle()

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
