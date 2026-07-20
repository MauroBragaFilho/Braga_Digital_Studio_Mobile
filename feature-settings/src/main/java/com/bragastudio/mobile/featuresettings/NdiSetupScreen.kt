package com.bragastudio.mobile.featuresettings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bragastudio.mobile.common.components.*
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NdiSetupScreen(
    onNavigateUp: () -> Unit,
    onStartStopTransmission: (Boolean) -> Unit,
    onTestConnection: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val ndiSettings by viewModel.ndiSettings.collectAsState()
    val videoSettings by viewModel.videoSettings.collectAsState()
    val metrics by viewModel.hardwareMetrics.collectAsState()
    val ndiLatencyMs by viewModel.ndiLatencyMs.collectAsState()
    val ndiFrameDropPct by viewModel.ndiFrameDropPct.collectAsState()
    val ndiBitrateMbps by viewModel.ndiBitrateMbps.collectAsState()
    val isTransmitting = ndiSettings.isEnabled
    var transmitterName by remember { mutableStateOf(ndiSettings.cameraName) }
    
    // Update local state when flow updates
    LaunchedEffect(ndiSettings.cameraName) {
        transmitterName = ndiSettings.cameraName
    }
    var selectedQuality by remember { mutableStateOf("Balanceada") }
    var selectedFps by remember { mutableStateOf("30") }
    var bitrate by remember { mutableStateOf("15 Mbps") }
    var latency by remember { mutableStateOf("23 ms") }
    var framesSent by remember { mutableStateOf("12000") }
    var packetsLost by remember { mutableStateOf("0") }
    var connectionStatus by remember { mutableStateOf("Conectado") }
    var showNameDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showFpsDialog by remember { mutableStateOf(false) }

    if (showNameDialog) {
        var tempName by remember { mutableStateOf(transmitterName) }
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Nome da Câmera", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = tempName,
                    onValueChange = { tempName = it },
                    label = { Text("Nome", color = Color.Gray) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF2979FF),
                        unfocusedBorderColor = Color.Gray,
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = { 
                    val finalName = if (tempName.isBlank()) "BDSM - ${android.os.Build.MODEL}" else tempName
                    transmitterName = finalName
                    viewModel.setNdiCameraName(finalName)
                    showNameDialog = false 
                }) {
                    Text("Salvar", color = Color(0xFF2979FF))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) {
                    Text("Cancelar", color = Color.Gray)
                }
            }
        )
    }

    if (showQualityDialog) {
        OptionsDialog(
            title = "Qualidade",
            options = listOf("Econômica", "Balanceada", "Alta", "Máxima"),
            currentSelection = selectedQuality,
            onSelect = { selectedQuality = it; showQualityDialog = false },
            onDismiss = { showQualityDialog = false }
        )
    }

    if (showFpsDialog) {
        OptionsDialog(
            title = "Taxa de Quadros (FPS)",
            options = listOf("24", "25", "30", "50", "60"),
            currentSelection = selectedFps,
            onSelect = { selectedFps = it; showFpsDialog = false },
            onDismiss = { showFpsDialog = false }
        )
    }

    Scaffold(
        containerColor = Color(0xFF0A0A0A),
        topBar = {
            TopAppBar(
                title = { Text("Configuração NDI", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Thermostat, contentDescription = "Temp", tint = Color(0xFFB0B0B0), modifier = Modifier.size(16.dp))
                            Text(String.format("%.1f°C", metrics.temperatureCelsius), color = Color(0xFFB0B0B0), fontSize = 12.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.SdStorage, contentDescription = "Armazenamento", tint = Color(0xFFB0B0B0), modifier = Modifier.size(16.dp))
                            Text(String.format("%.1f GB", metrics.storageFreeGB), color = Color(0xFFB0B0B0), fontSize = 12.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.BatteryFull, contentDescription = "Bateria", tint = Color(0xFFB0B0B0), modifier = Modifier.size(16.dp))
                            Text("${metrics.batteryPercentage}%", color = Color(0xFFB0B0B0), fontSize = 12.sp)
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // COLUNA ESQUERDA (Cards de Status e NDI)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Card NDI Banner
                Card(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Background gradient to simulate lens (since we don't have the image file)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.radialGradient(colors = listOf(Color.Red.copy(alpha=0.3f), Color.Transparent), radius = 600f))
                        )
                        
                        Column(
                            modifier = Modifier.padding(24.dp).fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("NDI®", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Black)
                                Text("Network Device Interface", color = Color.Gray, fontSize = 12.sp)
                            }
                            
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(
                                    modifier = Modifier
                                        .background(if (isTransmitting) Color(0xFF1B5E20) else Color(0xFF424242), RoundedCornerShape(16.dp))
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                        .clickable { 
                                            val newState = !isTransmitting
                                            viewModel.setNdiEnabled(newState)
                                            onStartStopTransmission(newState)
                                        }
                                ) {
                                    Text(if (isTransmitting) "Ativo" else "Inativo", color = if (isTransmitting) Color(0xFF4CAF50) else Color.White, fontWeight = FontWeight.Bold)
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(modifier = Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (isTransmitting) Color(0xFF00E676) else Color.Gray))
                                    Text(if (isTransmitting) "Transmitindo" else "Pronto para transmitir", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
                
                // Card Status da Transmissão
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Status da Transmissão", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (metrics.isWifiConnected) Icons.Filled.Wifi else Icons.Filled.WifiOff, "Rede", tint = if (metrics.isWifiConnected) Color(0xFF00E676) else Color.Red, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Rede", color = Color.Gray, fontSize = 10.sp)
                                }
                                Text(if (metrics.isWifiConnected) "Conectado" else "Desconectado", color = if (metrics.isWifiConnected) Color(0xFF00E676) else Color.Red, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Latência", color = Color.Gray, fontSize = 10.sp)
                                Text(if (isTransmitting) "$ndiLatencyMs ms" else "--", color = if (isTransmitting) Color(0xFF00E676) else Color.Gray, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Frame Drop", color = Color.Gray, fontSize = 10.sp)
                                Text(if (isTransmitting) "$ndiFrameDropPct%" else "--", color = if (isTransmitting) Color(0xFF00E676) else Color.Gray, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Bitrate", color = Color.Gray, fontSize = 10.sp)
                                Text(if (isTransmitting) "$ndiBitrateMbps Mbps" else "--", color = if (isTransmitting) Color.White else Color.Gray, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // COLUNA DIREITA (Configurações)
            Column(
                modifier = Modifier.weight(1.2f)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Configurações", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        SettingsRow(label = "Nome do dispositivo", value = transmitterName, icon = Icons.Filled.Edit) {
                            showNameDialog = true
                        }
                        SettingsRow(label = "Resolução", value = "1080p", icon = Icons.Filled.ExpandMore) {}
                        SettingsRow(label = "Áudio", value = "Ligado", icon = Icons.Filled.ExpandMore) {}
                        SettingsRow(label = "Protocolo", value = "NDI HX3", icon = Icons.Filled.ExpandMore) {}
                        SettingsRow(label = "Qualidade", value = selectedQuality, icon = Icons.Filled.ExpandMore) { showQualityDialog = true }
                        SettingsRow(label = "Taxa de quadros (FPS)", value = "30 fps", icon = Icons.Filled.ExpandMore) { showFpsDialog = true }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsRow(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = Color.Gray, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.width(8.dp))
                Icon(icon, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2E2E2E)))
    }
}