package com.bragastudio.mobile.featuresettings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bragastudio.mobile.corecapture.domain.CameraInfoModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onNavigateUp: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val cameras by viewModel.availableCameras.collectAsState()

    Scaffold(
        containerColor = Color(0xFF0A0A0A),
        topBar = {
            TopAppBar(
                title = { Text("Diagnóstico de Câmeras", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val report = generateReport(cameras)
                        copyToClipboard(context, report)
                        Toast.makeText(context, "Relatório copiado!", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copiar", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(cameras) { camera ->
                CameraCard(camera)
            }
        }
    }
}

@Composable
private fun CameraCard(camera: CameraInfoModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = "Câmera ID: ${camera.id}", color = Color(0xFF2979FF), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(text = "Nome: ${camera.name}", color = Color.White)
            Text(text = "Tipo Lente: ${camera.lensType.name}", color = Color.White)
            Text(text = "Facing: ${getFacingString(camera.facing)}", color = Color.White)
            Text(text = "Hardware Level: ${getHardwareLevelString(camera.hardwareLevel)}", color = Color.White)
            Text(text = "Flash: ${if(camera.hasFlash) "Sim" else "Não"}", color = Color.White)
            Text(text = "Estabilização: ${if(camera.stabilization) "Sim" else "Não"}", color = Color.White)
            Text(text = "Distância Focal: ${camera.focalLengths.joinToString(", ")} mm", color = Color.White)
            Text(text = "Sensor: ${camera.sensorSize.width} x ${camera.sensorSize.height}", color = Color.White)
            
            Text(text = "Capacidades (${camera.capabilities.size}):", color = Color.LightGray, fontSize = 14.sp)
            Text(text = camera.capabilities.joinToString(", "), color = Color.Gray, fontSize = 12.sp)

            Text(text = "Resoluções (Top 5):", color = Color.LightGray, fontSize = 14.sp)
            val resStr = camera.resolutions.take(5).joinToString(" | ") { "${it.width}x${it.height}" }
            Text(text = resStr, color = Color.Gray, fontSize = 12.sp)
        }
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
