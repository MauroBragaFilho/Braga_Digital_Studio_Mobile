package com.bragastudio.mobile.featurehome

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ColorLens
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bragastudio.mobile.core.domain.HardwareMetrics

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
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

    Scaffold(
        containerColor = Color(0xFF000000)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            if (isPortrait) {
                // ============================================================
                // INTERFACE MODO RETRATO (VERTICAL)
                // ============================================================
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // 1. Header Retrato
                        PortraitHeader(onNavigateToSettings = onNavigateToSettings)

                        // 2. Métricas Retrato
                        StatusMetricsBar(metrics = metrics)

                        // 3. Card Monitor Retrato
                        PortraitMonitorCard(onClick = onNavigateToPreview)

                        // Carrossel Dots
                        CarouselDotsIndicator()
                    }

                    // 4. Grid 2x2 de Módulos (Retrato)
                    PortraitModulesGrid(
                        onNavigateToNdiSetup = onNavigateToNdiSetup,
                        onNavigateToRecordings = onNavigateToRecordings,
                        onNavigateToLuts = onNavigateToLuts,
                        onNavigateToSettings = onNavigateToSettings
                    )
                }
            } else {
                // ============================================================
                // INTERFACE MODO PAISAGEM (HORIZONTAL)
                // ============================================================
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // 1. Header Paisagem com Métricas e Engrenagem no Topo
                    LandscapeHeader(
                        metrics = metrics,
                        onNavigateToSettings = onNavigateToSettings
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 2. Conteúdo Principal Paisagem (Monitor na Esquerda + Coluna na Direita)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Esquerda: Monitor Card em Paisagem (60%)
                        LandscapeMonitorCard(
                            onClick = onNavigateToPreview,
                            modifier = Modifier
                                .weight(0.58f)
                                .fillMaxHeight()
                        )

                        // Direita: Coluna com os 4 Cards Horizontais (40%)
                        LandscapeModulesColumn(
                            onNavigateToNdiSetup = onNavigateToNdiSetup,
                            onNavigateToRecordings = onNavigateToRecordings,
                            onNavigateToLuts = onNavigateToLuts,
                            onNavigateToSettings = onNavigateToSettings,
                            modifier = Modifier
                                .weight(0.42f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// 1. COMPONENTES DO MODO RETRATO (VERTICAL)
// ============================================================================

@Composable
fun PortraitHeader(onNavigateToSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    text = "BRAGA",
                    color = Color(0xFFE50914),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    lineHeight = 11.sp
                )
                Text(
                    text = "DIGITAL",
                    color = Color(0xFFE50914),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    lineHeight = 11.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "STUDIO MOBILE",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            )
        }

        IconButton(
            onClick = onNavigateToSettings,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.05f))
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Configurações",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
fun PortraitMonitorCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF121214))
            .border(1.dp, Color(0xFF222226), RoundedCornerShape(24.dp))
            .clickable { onClick() }
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
                    .padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Icon(
                        imageVector = Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = Color(0xFFFF0055),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Monitor",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Monitore com qualidade profissional com ferramentas avançadas.",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFFFF0055))
                        .clickable { onClick() }
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Abrir Monitor",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFFF0055).copy(alpha = 0.35f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                Image(
                    painter = painterResource(id = R.drawable.camera_lens),
                    contentDescription = "Lente da Câmera",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(end = 4.dp)
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp)
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhotoCamera,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PortraitModulesGrid(
    onNavigateToNdiSetup: () -> Unit,
    onNavigateToRecordings: () -> Unit,
    onNavigateToLuts: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ModuleSquareCard(
                title = "Configuração NDI",
                subtitle = "Conecte e gerencie dispositivos NDI.",
                icon = Icons.Filled.Router,
                iconTint = Color(0xFF00E5FF),
                onClick = onNavigateToNdiSetup,
                modifier = Modifier.weight(1f)
            )
            ModuleSquareCard(
                title = "Gravações",
                subtitle = "Acesse suas gravações e projetos.",
                icon = Icons.Filled.Movie,
                iconTint = Color(0xFF7C4DFF),
                onClick = onNavigateToRecordings,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ModuleSquareCard(
                title = "LUTs",
                subtitle = "Gerencie e aplique suas LUTs.",
                icon = Icons.Filled.ColorLens,
                iconTint = Color(0xFFFF9100),
                onClick = onNavigateToLuts,
                modifier = Modifier.weight(1f)
            )
            ModuleSquareCard(
                title = "Configurações",
                subtitle = "Ajustes do app e do sistema.",
                icon = Icons.Filled.Tune,
                iconTint = Color(0xFF2979FF),
                onClick = onNavigateToSettings,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ============================================================================
// 2. COMPONENTES DO MODO PAISAGEM (HORIZONTAL)
// ============================================================================

@Composable
fun LandscapeHeader(
    metrics: HardwareMetrics,
    onNavigateToSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Branding Esquerda
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(
                    text = "BRAGA",
                    color = Color(0xFFE50914),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    lineHeight = 10.sp
                )
                Text(
                    text = "DIGITAL",
                    color = Color(0xFFE50914),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    lineHeight = 10.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "STUDIO MOBILE",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "Controle. Crie. Transmita.",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Métricas Centro / Direita
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusMetricsBar(metrics = metrics)

            IconButton(
                onClick = onNavigateToSettings,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.05f))
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Configurações",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun LandscapeMonitorCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF0F0F11))
            .border(1.dp, Color(0xFF222226), RoundedCornerShape(20.dp))
            .clickable { onClick() }
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Informações
            Column(
                modifier = Modifier
                    .weight(1.1f)
                    .fillMaxHeight()
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Icon(
                        imageVector = Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = Color(0xFFFF0055),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Monitor Profissional",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Monitore sua imagem com precisão\ne ferramentas avançadas em tempo real.",
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Status Badge (● Pronto para capturar)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF0055))
                        )
                        Text(
                            text = "Pronto para capturar",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Botão "Abrir Monitor ->"
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xFFFF0055))
                        .clickable { onClick() }
                        .padding(horizontal = 22.dp, vertical = 11.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Abrir Monitor",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Lente DSLR Grafismo Lado Direito
            Box(
                modifier = Modifier
                    .weight(0.9f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFFF0055).copy(alpha = 0.35f),
                                    Color.Transparent
                                )
                            )
                        )
                )

                Image(
                    painter = painterResource(id = R.drawable.camera_lens),
                    contentDescription = "Lente da Câmera",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(end = 4.dp)
                )
            }
        }
    }
}

@Composable
fun LandscapeModulesColumn(
    onNavigateToNdiSetup: () -> Unit,
    onNavigateToRecordings: () -> Unit,
    onNavigateToLuts: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QuickHorizontalModuleCard(
            title = "Configuração NDI",
            subtitle = "Conecte e gerencie dispositivos NDI",
            icon = Icons.Filled.Router,
            accentColor = Color(0xFF00E5FF),
            onClick = onNavigateToNdiSetup,
            modifier = Modifier.weight(1f)
        )
        QuickHorizontalModuleCard(
            title = "Gravações",
            subtitle = "Acesse suas gravações e projetos",
            icon = Icons.Filled.Movie,
            accentColor = Color(0xFF7C4DFF),
            onClick = onNavigateToRecordings,
            modifier = Modifier.weight(1f)
        )
        QuickHorizontalModuleCard(
            title = "LUTs",
            subtitle = "Gerencie e aplique suas LUTs",
            icon = Icons.Filled.ColorLens,
            accentColor = Color(0xFFFF9100),
            onClick = onNavigateToLuts,
            modifier = Modifier.weight(1f)
        )
        QuickHorizontalModuleCard(
            title = "Configurações",
            subtitle = "Ajustes do app e do sistema",
            icon = Icons.Filled.Tune,
            accentColor = Color(0xFF2979FF),
            onClick = onNavigateToSettings,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun QuickHorizontalModuleCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF141416))
            .border(1.dp, Color(0xFF222226), RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ============================================================================
// UTILS COMPARTILHADOS
// ============================================================================

@Composable
fun StatusMetricsBar(metrics: HardwareMetrics) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetricStatusItem(
            icon = Icons.Filled.Thermostat,
            value = "${metrics.temperatureCelsius}°C",
            label = "TEMP."
        )
        MetricStatusItem(
            icon = Icons.Filled.SdStorage,
            value = "${String.format("%.1f", metrics.storageFreeGB)} GB",
            label = "ARMAZ."
        )
        MetricStatusItem(
            icon = Icons.Filled.BatteryFull,
            value = "${metrics.batteryPercentage}%",
            label = "BATERIA"
        )
        MetricStatusItem(
            icon = if (metrics.isWifiConnected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
            value = if (metrics.isWifiConnected) "WIFI" else "OFF",
            label = if (metrics.isWifiConnected) "Conectado" else "Desconectado"
        )
    }
}

@Composable
fun MetricStatusItem(
    icon: ImageVector,
    value: String,
    label: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = value,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 13.sp
            )
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 9.sp
            )
        }
    }
}

@Composable
fun CarouselDotsIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(16.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xFFFF0055))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.25f))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.25f))
        )
    }
}

@Composable
fun ModuleSquareCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(130.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF141416))
            .border(1.dp, Color(0xFF222226), RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(30.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}