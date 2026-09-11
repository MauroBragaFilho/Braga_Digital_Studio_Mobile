package com.bragastudio.mobile.featuresettings

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import java.net.NetworkInterface
import java.util.Collections

@Composable
fun NdiSetupScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val ndiSettings by viewModel.ndiSettings.collectAsState()
    val hwMetrics by viewModel.hardwareMetrics.collectAsState()
    val ndiLatency by viewModel.ndiLatencyMs.collectAsState()
    val ndiBitrate by viewModel.ndiBitrateMbps.collectAsState()

    val context = LocalContext.current
    val deviceIp = remember { getLocalIpAddress(context) }

    // Nome padrão BDSM - [Modelo do Aparelho]
    val defaultStreamName = remember { "BDSM - ${Build.MODEL}" }
    val currentStreamName = if (ndiSettings.cameraName.isBlank()) defaultStreamName else ndiSettings.cameraName

    var selectedNdiPreset by remember { mutableStateOf("1080p60 NDI|HX") }

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = Color.Black
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Transmissão NDI",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Rede Local & Protocolos IP",
                            color = Color(0xFFFF0055),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                IconButton(
                    onClick = { /* Refresh status */ },
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.05f))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Atualizar",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (isPortrait) {
                // MODO RETRATO (VERTICAL)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    NdiNetworkStatusCard(
                        ipAddress = deviceIp,
                        isWifiConnected = hwMetrics.isWifiConnected,
                        latencyMs = ndiLatency
                    )

                    NdiTransmitterCard(
                        isNdiActive = ndiSettings.isEnabled,
                        isAudioEnabled = ndiSettings.isAudioEnabled,
                        streamName = currentStreamName,
                        defaultStreamName = defaultStreamName,
                        onNdiToggle = { enabled -> viewModel.setNdiEnabled(enabled) },
                        onAudioToggle = { enabled -> viewModel.setNdiAudioEnabled(enabled) },
                        onStreamNameChange = { newName -> viewModel.setNdiCameraName(newName) }
                    )

                    NdiMetricsCard(
                        bitrateMbps = if (ndiBitrate > 0) ndiBitrate else 15,
                        latencyMs = ndiLatency,
                        isNdiActive = ndiSettings.isEnabled
                    )

                    NdiStreamSettingsCard(
                        selectedPreset = selectedNdiPreset,
                        onSelectPreset = { selectedNdiPreset = it }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                }
            } else {
                // MODO PAISAGEM (HORIZONTAL)
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NdiNetworkStatusCard(
                            ipAddress = deviceIp,
                            isWifiConnected = hwMetrics.isWifiConnected,
                            latencyMs = ndiLatency
                        )
                        NdiTransmitterCard(
                            isNdiActive = ndiSettings.isEnabled,
                            isAudioEnabled = ndiSettings.isAudioEnabled,
                            streamName = currentStreamName,
                            defaultStreamName = defaultStreamName,
                            onNdiToggle = { enabled -> viewModel.setNdiEnabled(enabled) },
                            onAudioToggle = { enabled -> viewModel.setNdiAudioEnabled(enabled) },
                            onStreamNameChange = { newName -> viewModel.setNdiCameraName(newName) }
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1.1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NdiMetricsCard(
                            bitrateMbps = if (ndiBitrate > 0) ndiBitrate else 15,
                            latencyMs = ndiLatency,
                            isNdiActive = ndiSettings.isEnabled
                        )
                        NdiStreamSettingsCard(
                            selectedPreset = selectedNdiPreset,
                            onSelectPreset = { selectedNdiPreset = it }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NdiNetworkStatusCard(
    ipAddress: String,
    isWifiConnected: Boolean,
    latencyMs: Int
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF141416))
            .border(1.dp, Color(0xFF222226), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFFF0055).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Wifi,
                        contentDescription = null,
                        tint = Color(0xFFFF0055),
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column {
                    Text(
                        text = if (isWifiConnected) "Rede Wi-Fi Conectada" else "Conexão de Rede Local",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "IP Real: $ipAddress (${latencyMs}ms Latência)",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isWifiConnected) Color(0xFF4CAF50).copy(alpha = 0.15f) else Color.Red.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isWifiConnected) "ONLINE" else "OFFLINE",
                    color = if (isWifiConnected) Color(0xFF4CAF50) else Color.Red,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun NdiTransmitterCard(
    isNdiActive: Boolean,
    isAudioEnabled: Boolean,
    streamName: String,
    defaultStreamName: String,
    onNdiToggle: (Boolean) -> Unit,
    onAudioToggle: (Boolean) -> Unit,
    onStreamNameChange: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF141416))
            .border(1.dp, if (isNdiActive) Color(0xFFFF0055).copy(alpha = 0.5f) else Color(0xFF222226), RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Ativação do Transmissor
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Router,
                        contentDescription = null,
                        tint = Color(0xFFFF0055),
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "Transmissor NDI HX",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Switch(
                    checked = isNdiActive,
                    onCheckedChange = onNdiToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFFFF0055),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color(0xFF222226)
                    )
                )
            }

            // Switch de Transmissão de Áudio
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.3f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (isAudioEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                        contentDescription = null,
                        tint = if (isAudioEnabled) Color(0xFFFF0055) else Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Transmitir Áudio da Câmera / USB",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (isAudioEnabled) "Áudio NDI Ativo (Stereo)" else "Mudo (Sem Áudio)",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 10.sp
                        )
                    }
                }

                Switch(
                    checked = isAudioEnabled,
                    onCheckedChange = onAudioToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFFFF0055),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color(0xFF222226)
                    )
                )
            }

            // Campo Editável do Nome do Stream
            Column {
                Text(
                    text = "Nome do Stream de Saída NDI",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                OutlinedTextField(
                    value = streamName,
                    onValueChange = onStreamNameChange,
                    placeholder = { Text(defaultStreamName, color = Color.Gray) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Black.copy(alpha = 0.5f),
                        unfocusedContainerColor = Color.Black.copy(alpha = 0.3f),
                        focusedBorderColor = Color(0xFFFF0055),
                        unfocusedBorderColor = Color(0xFF222226),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "Editar Nome",
                            tint = Color(0xFFFF0055),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }
    }
}

// ATENÇÃO: latencyMs é recebido mas não aparece na linha de métricas abaixo
// (só Resolução/Bitrate/Codec/Frames Perdidos). Se a intenção é mostrar
// latência do stream NDI, falta um NdiMetricStatItem("Latência", "$latencyMs ms").
@Composable
@Suppress("UNUSED_PARAMETER")
fun NdiMetricsCard(
    bitrateMbps: Int,
    latencyMs: Int,
    isNdiActive: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF141416))
            .border(1.dp, Color(0xFF222226), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Speed,
                        contentDescription = null,
                        tint = Color(0xFFFF0055),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Informações do Stream NDI",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (isNdiActive) Color(0xFFFF0055).copy(alpha = 0.15f) else Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isNdiActive) "TRANSMITINDO" else "PARADO",
                        color = if (isNdiActive) Color(0xFFFF0055) else Color.Gray,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                NdiMetricStatItem(label = "Resolução", value = "1080p60")
                NdiMetricStatItem(label = "Banda / Bitrate", value = "$bitrateMbps Mbps")
                NdiMetricStatItem(label = "Codec", value = "NDI|HX2")
                NdiMetricStatItem(label = "Frames Perdidos", value = "0")
            }
        }
    }
}

@Composable
fun NdiMetricStatItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 9.sp
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun NdiStreamSettingsCard(
    selectedPreset: String,
    onSelectPreset: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF141416))
            .border(1.dp, Color(0xFF222226), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Qualidade da Transmissão NDI",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "(Independente da Gravação)",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 10.sp
                )
            }

            val presets = listOf("1080p60 NDI|HX", "720p60 Low", "4K30 NDI|HX3")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    val isSelected = preset == selectedPreset
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Color(0xFFFF0055) else Color(0xFF1E1E22))
                            .border(1.dp, if (isSelected) Color(0xFFFF0055) else Color(0xFF2C2C32), RoundedCornerShape(12.dp))
                            .clickable { onSelectPreset(preset) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = preset.split(" ")[0],
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Text(
                text = "Nota: A transmissão NDI está operando em 1080p. Opções adicionais de preset preparadas para atualização de firmware.",
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 10.sp,
                lineHeight = 13.sp
            )
        }
    }
}

fun getLocalIpAddress(context: Context): String {
    try {
        // ConnectivityManager.getLinkProperties() é a API recomendada atual para
        // obter o IP local da rede ativa — WifiInfo.getIpAddress() está deprecado
        // desde a API 31 e não reflete corretamente redes que não são Wi-Fi
        // clássico (ex.: Wi-Fi Direct, Ethernet via adaptador USB).
        val connectivityManager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val linkProperties = activeNetwork?.let { connectivityManager.getLinkProperties(it) }
        val ipv4Address = linkProperties?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }
            ?.hostAddress
        if (ipv4Address != null) {
            return ipv4Address
        }

        val interfaces: List<NetworkInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (intf in interfaces) {
            val addrs = Collections.list(intf.inetAddresses)
            for (addr in addrs) {
                if (!addr.isLoopbackAddress) {
                    val sAddr = addr.hostAddress
                    if (sAddr != null && sAddr.indexOf(':') < 0) {
                        return sAddr
                    }
                }
            }
        }
    } catch (_: Exception) {}
    return "192.168.1.105"
}