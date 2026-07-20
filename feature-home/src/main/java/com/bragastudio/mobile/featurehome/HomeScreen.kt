package com.bragastudio.mobile.featurehome

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    onNavigateToPreview: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLuts: () -> Unit,
    onNavigateToRecordings: () -> Unit,
    onNavigateToNdiSetup: () -> Unit
) {
    val metrics by viewModel.hardwareMetrics.collectAsState()
    
    Scaffold(
        containerColor = Color.Black
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Branding
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("BRAGA", color = Color(0xFFFF1744), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text("DIGITAL", color = Color(0xFFFF1744), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("STUDIO MOBILE", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                }
                
                // Status e Settings
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        StatusItemHorizontal(Icons.Filled.Thermostat, "${metrics.temperatureCelsius}°C", "TEMP.")
                        StatusItemHorizontal(Icons.Filled.SdStorage, "${String.format("%.1f", metrics.storageFreeGB)} GB", "ARMAZ.")
                        StatusItemHorizontal(Icons.Filled.BatteryFull, "${metrics.batteryPercentage}%", "BATERIA")
                        
                        StatusItemHorizontal(
                            icon = if (metrics.isWifiConnected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                            value = if (metrics.isWifiConnected) "WiFi" else "OFF",
                            label = if (metrics.isWifiConnected) "Conectado" else "Desconectado"
                        )
                    }
                    
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Configurações", tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Main Content
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                MonitorCard(
                    onClick = onNavigateToPreview,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                
                Column(
                    modifier = Modifier.width(240.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Passar o nome do item para o QuickActionCard
                    QuickActionCard(
                        title = "NDI (REDE)",
                        subtitle = "Transmita via NDI\nem alta qualidade.",
                        itemName = "Configuração NDI", // <-- Nome do item
                        icon = Icons.Filled.Router,
                        backgroundColor = Color(0xFF00E5FF),
                        onClick = onNavigateToNdiSetup,
                        modifier = Modifier.weight(1f)
                    )
                    QuickActionCard(
                        title = "GRAVAÇÕES",
                        subtitle = "Acesse suas\ngravações.",
                        itemName = "Gravações", // <-- Nome do item
                        icon = Icons.Filled.Movie,
                        backgroundColor = Color(0xFF7C4DFF),
                        onClick = onNavigateToRecordings,
                        modifier = Modifier.weight(1f)
                    )
                    QuickActionCard(
                        title = "LUTs",
                        subtitle = "Gerencie \nsuas LUTs.",
                        itemName = "LUTs", // <-- Nome do item
                        icon = Icons.Filled.ColorLens,
                        backgroundColor = Color(0xFFFF9800),
                        onClick = onNavigateToLuts,
                        modifier = Modifier.weight(1f)
                    )
                    QuickActionCard(
                        title = "CONFIGURAÇÕES",
                        subtitle = "Ajuste o app da\nsua forma.",
                        itemName = "Configurações", // <-- Nome do item
                        icon = Icons.Filled.Tune,
                        backgroundColor = Color(0xFF2979FF),
                        onClick = onNavigateToSettings,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun MonitorCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1A1A1A))
            .clickable { onClick() }
            .padding(32.dp)
    ) {
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.Videocam, contentDescription = null, tint = Color(0xFFFF1744), modifier = Modifier.size(64.dp))
                Spacer(modifier = Modifier.height(24.dp))
                Text("Monitor", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Monitore com qualidade profissional\ncom ferramentas avançadas.", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp, lineHeight = 24.sp)
                Spacer(modifier = Modifier.height(32.dp))
                
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFFFF1744))
                        .clickable { onClick() }
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Abrir Monitor", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
            
            Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null, tint = Color(0xFF333333), modifier = Modifier.size(300.dp))
            }
        }
    }
}

@Composable
fun QuickActionCard(
    title: String,
    subtitle: String,
    itemName: String, // <-- Novo parâmetro para o nome do item
    icon: ImageVector,
    backgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A1A1A))
            .clickable { onClick() }
            .padding(20.dp)
    ) {
        // Layout alterado para acomodar o nome do item à direita
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween, // Espaço entre conteúdo e nome/item
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Coluna com ícone, título e subtítulo (lado esquerdo)
            Column(
                modifier = Modifier.weight(1f), // Ocupa espaço disponível à esquerda
                verticalArrangement = Arrangement.Center
            ) {
                Icon(icon, contentDescription = null, tint = backgroundColor, modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, lineHeight = 16.sp)
            }

            Spacer(modifier = Modifier.width(16.dp)) // Espaçamento entre o conteúdo e o nome do item

            // Coluna com o nome do item (lado direito)
            Column(
                horizontalAlignment = Alignment.End // Alinha o texto à direita
            ) {
                Text(
                    text = itemName, // <-- Texto com o nome do item
                    color = Color.White.copy(alpha = 0.8f), // Cor ligeiramente mais clara
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, // Estilo médio para destaque suave
                    // Pode adicionar maxLines e overflow se necessário
                )
            }

            // Ícone da seta (opcional, pode ser mantido ou removido)
            // Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun StatusItemHorizontal(icon: ImageVector, value: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        }
    }
}