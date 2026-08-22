package com.bragastudio.mobile.featurepreview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloat
import com.bragastudio.mobile.core.domain.HardwareMetrics // ✅ Import necessário
import com.bragastudio.mobile.core.domain.VideoSettings
import com.bragastudio.mobile.corecapture.domain.CameraInfoModel

// ============================================================================
// TEMA E CONSTANTES
// ============================================================================
object HudTheme {
    val recordColor = Color.Red
    val recordActiveColor = Color(0xFFFF3D00)
    val ndiActiveColor = Color(0xFF00C853)
    val ndiInactiveColor = Color.White.copy(alpha = 0.1f)
    val buttonActiveColor = Color(0xFF2979FF)
    val buttonInactiveColor = Color.White.copy(alpha = 0.1f)
    val sidebarBackgroundColor = Color.Black.copy(alpha = 0.5f)
    
    val textColorPrimary = Color.White
    val textColorSecondary = Color.White.copy(alpha = 0.6f)
    val textColorMuted = Color.Gray
    
    val toolButtonSize = 34.dp
    val recordButtonSize = 72.dp
    val iconSizeSmall = 18.dp
    val iconSizeMedium = 24.dp
    
    val spacingSmall = 4.dp
    val spacingMedium = 8.dp
    val spacingLarge = 16.dp
    val spacingXLarge = 24.dp
    
    val fontSizeSmall = 8.sp
    val fontSizeMedium = 10.sp
    val fontSizeNormal = 12.sp
    val fontSizeLarge = 14.sp
}

// ============================================================================
// UTILITÁRIOS
// ============================================================================
fun formatTimecode(milliseconds: Long, fps: Int): String {
    val totalSeconds = milliseconds / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    val msRemainder = milliseconds % 1000
    val frames = if (fps > 0) (msRemainder * fps) / 1000 else 0
    return String.format("%02d:%02d:%02d:%02d", hours, minutes, secs, frames)
}

/**
 * Estima o tempo de gravação restante a partir do espaço livre e do bitrate de vídeo
 * atualmente configurado (assume-se ~10% adicional para o stream de áudio, prática comum
 * em calculadoras de gravação de câmeras profissionais).
 */
fun formatRecordingTimeRemaining(storageFreeGB: Float, bitrateMbps: Int): String {
    if (bitrateMbps <= 0) return "--:--"
    val effectiveMbps = bitrateMbps * 1.1f // margem para faixa de áudio
    val storageMegabits = storageFreeGB * 1024f * 8f
    val secondsRemaining = (storageMegabits / effectiveMbps).toLong()

    if (secondsRemaining <= 0) return "00:00"

    val hours = secondsRemaining / 3600
    val minutes = (secondsRemaining % 3600) / 60

    return if (hours > 0) {
        String.format("%dh%02dm", hours, minutes)
    } else {
        String.format("%02dm", minutes)
    }
}

fun normalizeAudioLevel(level: Float): Float {
    return level.coerceIn(0f, 1f)
}

fun getLensLabel(lensName: String): String {
    return when {
        lensName.contains("0.5", ignoreCase = true) || lensName.contains("ultra", ignoreCase = true) -> "0.5x"
        lensName.contains("3x", ignoreCase = true) || lensName.contains("3", ignoreCase = true) -> "3x"
        lensName.contains("2x", ignoreCase = true) || lensName.contains("2", ignoreCase = true) -> "2x"
        lensName.contains("tele", ignoreCase = true) -> "3x"
        else -> "1x"
    }
}

// ============================================================================
// COMPONENTE PRINCIPAL DO HUD
// ============================================================================
@Composable
fun CameraHUDOverlay(
    isHudVisible: Boolean,
    displayRotation: Int = android.view.Surface.ROTATION_0,
    isRecording: Boolean,
    isNdiEnabled: Boolean,
    fps: Int,
    videoSettings: VideoSettings,
    recordingTime: Long,
    metrics: HardwareMetrics,
    storageFreeGB: Float,
    batteryPercentage: Int,
    currentLens: CameraInfoModel?,
    availableLenses: List<CameraInfoModel>,
    onLensSelect: (String) -> Unit,
    audioLevelLeft: Float,
    audioLevelRight: Float,
    selectedAudioDeviceName: String,
    isScopesVisible: Boolean,
    isZebraEnabled: Boolean,
    zebraThreshold: Int = 100,
    onSetZebraThreshold: (Int) -> Unit = {},
    focusPeakingSensitivity: Float = 0.5f,
    onSetFocusPeakingSensitivity: (Float) -> Unit = {},
    isLutEnabled: Boolean,
    isFocusPeakingEnabled: Boolean,
    isFalseColorEnabled: Boolean,
    currentAspectRatio: String,
    currentGrid: String,
    allLuts: List<com.bragastudio.mobile.core.model.Lut> = emptyList(),
    activeLut: com.bragastudio.mobile.core.model.Lut? = null,
    onSelectLut: (String?) -> Unit = {},
    onSetAspectRatio: (String) -> Unit = {},
    onSetGrid: (String) -> Unit = {},
    onToggleCameraSource: () -> Unit,
    onToggleFocusPeaking: () -> Unit,
    onRecordClick: () -> Unit,
    onNdiToggle: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLuts: () -> Unit,
    onToggleScopes: () -> Unit,
    onToggleZebra: () -> Unit,
    onToggleLut: () -> Unit,
    onToggleFalseColor: () -> Unit,
    onToggleAspectRatio: () -> Unit, 
    onToggleGrid: () -> Unit,
    availableAudioDevices: List<android.media.AudioDeviceInfo> = emptyList(),
    onSetCameraSource: (String) -> Unit = {},
    onCycleResolution: () -> Unit = {},
    onSetResolution: (String) -> Unit = {},
    onCycleFps: () -> Unit = {},
    onSetFps: (Int) -> Unit = {},
    onCycleBitrate: () -> Unit = {},
    onSetBitrate: (Int) -> Unit = {},
    onCycleCodec: () -> Unit = {},
    onSetCodec: (String) -> Unit = {},
    onCycleAudioDevice: () -> Unit = {},
    onSelectAudioDevice: (android.media.AudioDeviceInfo) -> Unit = {}
) {
    // Posicionamento do REC por orientação, definido a partir de teste no device real
    // (não segue mais estritamente "lado do USB" — ajustado para ergonomia/alcance
    // do polegar e proximidade da lente da câmera em cada orientação):
    //   Retrato            (ROTATION_0)   -> centralizado embaixo
    //   Horizontal         (ROTATION_90)  -> lado direito, centralizado verticalmente (mantido)
    //   Retrato Invertido  (ROTATION_180) -> centralizado em cima, perto do polegar
    //   Horizontal Invertida (ROTATION_270) -> lado direito da tela (lado da câmera)
    val recAlignment = when (displayRotation) {
        android.view.Surface.ROTATION_0 -> Alignment.BottomCenter
        android.view.Surface.ROTATION_90 -> Alignment.CenterEnd
        android.view.Surface.ROTATION_180 -> Alignment.TopCenter
        android.view.Surface.ROTATION_270 -> Alignment.CenterEnd
        else -> Alignment.BottomCenter
    }

    val recPadding = when (displayRotation) {
        android.view.Surface.ROTATION_0 -> Modifier.padding(bottom = 54.dp)
        android.view.Surface.ROTATION_90 -> Modifier.padding(end = 20.dp)
        android.view.Surface.ROTATION_180 -> Modifier.padding(top = 54.dp)
        android.view.Surface.ROTATION_270 -> Modifier.padding(end = 20.dp)
        else -> Modifier.padding(bottom = 54.dp)
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // Tally Light: borda vermelha pulsante em volta de todo o quadro enquanto grava.
        // Fica visível mesmo com o HUD oculto (modo "clean feed"), como em monitores
        // de referência (Atomos/SmallHD), para nunca deixar dúvida sobre o estado do REC.
        if (isRecording) {
            TallyBorder(modifier = Modifier.fillMaxSize())
        }

        TopBarProfessional(
            isHudVisible = isHudVisible,
            isNdiEnabled = isNdiEnabled,
            metrics = metrics,
            storageFreeGB = storageFreeGB,
            batteryPercentage = batteryPercentage,
            selectedAudioDeviceName = selectedAudioDeviceName,
            fps = fps,
            videoSettings = videoSettings,
            onNdiToggle = onNdiToggle,
            onNavigateToSettings = onNavigateToSettings,
            modifier = Modifier.align(Alignment.TopCenter).background(if (isHudVisible) Color.Black.copy(alpha = 0.7f) else Color.Transparent).padding(top = HudTheme.spacingMedium, bottom = HudTheme.spacingMedium),
            availableAudioDevices = availableAudioDevices,
            onToggleCameraSource = onToggleCameraSource,
            onSetCameraSource = onSetCameraSource,
            onCycleResolution = onCycleResolution,
            onSetResolution = onSetResolution,
            onCycleFps = onCycleFps,
            onSetFps = onSetFps,
            onCycleBitrate = onCycleBitrate,
            onSetBitrate = onSetBitrate,
            onCycleCodec = onCycleCodec,
            onSetCodec = onSetCodec,
            onCycleAudioDevice = onCycleAudioDevice,
            onSelectAudioDevice = onSelectAudioDevice
        )
        
        if (isHudVisible) {
            LeftToolsSidebarProfessional(
                isScopesVisible = isScopesVisible,
                isZebraEnabled = isZebraEnabled,
                isLutEnabled = isLutEnabled,
                isFocusPeakingEnabled = isFocusPeakingEnabled,
                isFalseColorEnabled = isFalseColorEnabled,
                currentAspectRatio = currentAspectRatio,
                allLuts = allLuts,
                activeLut = activeLut,
                onSelectLut = onSelectLut,
                onSetAspectRatio = onSetAspectRatio,
                onToggleScopes = onToggleScopes,
                onToggleZebra = onToggleZebra,
                onToggleLut = onToggleLut,
                onNavigateToLuts = onNavigateToLuts,
                onToggleAspectRatio = onToggleAspectRatio,
                onToggleFocusPeaking = onToggleFocusPeaking,
                onToggleFalseColor = onToggleFalseColor,
                zebraThreshold = zebraThreshold,
                onSetZebraThreshold = onSetZebraThreshold,
                focusPeakingSensitivity = focusPeakingSensitivity,
                onSetFocusPeakingSensitivity = onSetFocusPeakingSensitivity,
                modifier = Modifier.align(Alignment.CenterStart).background(HudTheme.sidebarBackgroundColor).padding(vertical = HudTheme.spacingLarge, horizontal = HudTheme.spacingMedium)
            )
        }
        
        // Apenas o Botão REC posicionado no lado USB-C
        RightControlsProfessional(
            isHudVisible = isHudVisible,
            isRecording = isRecording,
            currentLens = currentLens,
            availableLenses = availableLenses,
            onRecordClick = onRecordClick,
            onLensSelect = onLensSelect, 
            modifier = Modifier.align(recAlignment).then(recPadding)
        )
        
        BottomInfoProfessional(
            isHudVisible = isHudVisible,
            currentGrid = currentGrid,
            onToggleGrid = onToggleGrid,
            onSetGrid = onSetGrid,
            recordingTime = recordingTime,
            isRecording = isRecording,
            audioLevelLeft = audioLevelLeft,
            audioLevelRight = audioLevelRight,
            fps = fps,
            modifier = Modifier.align(Alignment.BottomCenter).background(if (isHudVisible) Color.Black.copy(alpha = 0.5f) else Color.Transparent).padding(vertical = 4.dp)
        )
    }
}

// ============================================================================
// SUB-COMPONENTES
// ============================================================================

/**
 * Sliders de ajuste fino, exibidos junto do botão de ferramenta correspondente
 * quando Zebra ou Focus Peaking está ativo — assim o operador ajusta o limiar
 * sem precisar sair do monitor e ir até Configurações.
 */
@Composable
fun QuickAdjustSlider(
    label: String,
    value: Float,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HudTheme.spacingSmall),
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, color = HudTheme.textColorSecondary, fontSize = 10.sp, modifier = Modifier.width(46.dp))
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.width(120.dp).height(20.dp),
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = HudTheme.buttonActiveColor,
                activeTrackColor = HudTheme.buttonActiveColor,
                inactiveTrackColor = HudTheme.buttonInactiveColor
            )
        )
        Text(valueLabel, color = HudTheme.textColorPrimary, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(34.dp))
    }
}

/**
 * Indicador de zoom com atalhos 1x/2x e mini-mapa de navegação.
 * O mini-mapa mostra a região do frame completo que está sendo exibida quando
 * o zoom pixel-a-pixel (>1x) está ativo, ajudando o operador a saber onde está
 * "olhando" dentro do sensor — como em monitores de referência profissionais.
 */
@Composable
fun ZoomControl(
    zoomFactor: Float,
    panX: Float,
    panY: Float,
    onSetZoom: (Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Mini-mapa: só aparece quando há zoom aplicado (pixel a pixel)
        if (zoomFactor > 1.01f) {
            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 36.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
            ) {
                // Retângulo representando a janela visível dentro do frame total
                val viewportWidthFraction = (1f / zoomFactor).coerceIn(0.05f, 1f)
                val viewportHeightFraction = viewportWidthFraction

                // panX/panY normalizados (-0.5..0.5 aprox) -> posição do canto do viewport
                val leftFraction = ((0.5f + panX) - viewportWidthFraction / 2f).coerceIn(0f, 1f - viewportWidthFraction)
                val topFraction = ((0.5f + panY) - viewportHeightFraction / 2f).coerceIn(0f, 1f - viewportHeightFraction)

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = (64.dp * leftFraction),
                            top = (36.dp * topFraction)
                        )
                        .size(width = 64.dp * viewportWidthFraction, height = 36.dp * viewportHeightFraction)
                        .background(Color.White.copy(alpha = 0.25f))
                        .border(1.dp, HudTheme.buttonActiveColor, RoundedCornerShape(1.dp))
                )
            }
        }

        // Chip de zoom atual + atalhos 1x / 2x
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            listOf(1.0f, 2.0f).forEach { preset ->
                val isActive = kotlin.math.abs(zoomFactor - preset) < 0.05f
                Text(
                    text = "${preset.toInt()}x",
                    color = if (isActive) HudTheme.buttonActiveColor else HudTheme.textColorSecondary,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSetZoom(preset, 0f, 0f) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (zoomFactor > 1.01f && kotlin.math.abs(zoomFactor - 1f) > 0.05f && kotlin.math.abs(zoomFactor - 2f) > 0.05f) {
                Text(
                    text = String.format("%.1fx", zoomFactor),
                    color = HudTheme.buttonActiveColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
fun TallyBorder(modifier: Modifier = Modifier) {
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "tally")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(durationMillis = 700, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "tallyPulse"
    )
    Canvas(modifier = modifier) {
        val strokeWidth = 6.dp.toPx()
        drawRect(
            color = HudTheme.recordActiveColor.copy(alpha = pulseAlpha),
            topLeft = androidx.compose.ui.geometry.Offset(strokeWidth / 2f, strokeWidth / 2f),
            size = androidx.compose.ui.geometry.Size(size.width - strokeWidth, size.height - strokeWidth),
            style = Stroke(width = strokeWidth)
        )
    }
}

@Composable
fun GridAndAspectOverlay(
    currentGrid: String,
    currentAspectRatio: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        if (currentGrid != "OFF" || currentAspectRatio != "OFF") {
            Canvas(modifier = Modifier.fillMaxHeight().aspectRatio(16f/9f, matchHeightConstraintsFirst = true)) {
                val lineThickness = 1.dp.toPx()
                val w = size.width
                val h = size.height
                
                // GRIDS
                if (currentGrid != "OFF") {
                    when (currentGrid) {
                        "3x3" -> {
                            drawLine(color = Color.White.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(w / 3f, 0f), end = androidx.compose.ui.geometry.Offset(w / 3f, h), strokeWidth = lineThickness)
                            drawLine(color = Color.White.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(2f * w / 3f, 0f), end = androidx.compose.ui.geometry.Offset(2f * w / 3f, h), strokeWidth = lineThickness)
                            
                            drawLine(color = Color.White.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(0f, h / 3f), end = androidx.compose.ui.geometry.Offset(w, h / 3f), strokeWidth = lineThickness)
                            drawLine(color = Color.White.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(0f, 2f * h / 3f), end = androidx.compose.ui.geometry.Offset(w, 2f * h / 3f), strokeWidth = lineThickness)
                        }
                        "4x4" -> {
                            for(i in 1..3) {
                                drawLine(color = Color.White.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(w * i / 4f, 0f), end = androidx.compose.ui.geometry.Offset(w * i / 4f, h), strokeWidth = lineThickness)
                                drawLine(color = Color.White.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(0f, h * i / 4f), end = androidx.compose.ui.geometry.Offset(w, h * i / 4f), strokeWidth = lineThickness)
                            }
                        }
                        "Centro" -> {
                            drawLine(color = Color.White.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(w / 2f - 20.dp.toPx(), h / 2f), end = androidx.compose.ui.geometry.Offset(w / 2f + 20.dp.toPx(), h / 2f), strokeWidth = lineThickness)
                            drawLine(color = Color.White.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(w / 2f, h / 2f - 20.dp.toPx()), end = androidx.compose.ui.geometry.Offset(w / 2f, h / 2f + 20.dp.toPx()), strokeWidth = lineThickness)
                        }
                    }
                }

                // ASPECT RATIO MARKERS
                if (currentAspectRatio != "OFF") {
                    val ratioValue = when (currentAspectRatio) {
                        "2.35:1" -> 2.35f
                        "4:3" -> 4f/3f
                        "1:1" -> 1f
                        else -> 16f/9f
                    }

                    if (ratioValue > (16f/9f)) {
                        val cinemaHeight = w / ratioValue
                        val yOffset = (h - cinemaHeight) / 2f
                        
                        drawLine(color = Color.Red.copy(alpha = 0.8f), start = androidx.compose.ui.geometry.Offset(0f, yOffset), end = androidx.compose.ui.geometry.Offset(w, yOffset), strokeWidth = lineThickness * 2)
                        drawLine(color = Color.Red.copy(alpha = 0.8f), start = androidx.compose.ui.geometry.Offset(0f, h - yOffset), end = androidx.compose.ui.geometry.Offset(w, h - yOffset), strokeWidth = lineThickness * 2)
                    } else {
                        val squareWidth = h * ratioValue
                        val xOffset = (w - squareWidth) / 2f
                        
                        drawLine(color = Color.Yellow.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(xOffset, 0f), end = androidx.compose.ui.geometry.Offset(xOffset, h), strokeWidth = lineThickness * 2)
                        drawLine(color = Color.Yellow.copy(alpha = 0.5f), start = androidx.compose.ui.geometry.Offset(w - xOffset, 0f), end = androidx.compose.ui.geometry.Offset(w - xOffset, h), strokeWidth = lineThickness * 2)
                    }
                }
            }
        }
    }
}

@Composable
fun TopBarProfessional(
    isHudVisible: Boolean,
    isNdiEnabled: Boolean,
    metrics: HardwareMetrics,
    storageFreeGB: Float,
    batteryPercentage: Int,
    selectedAudioDeviceName: String,
    fps: Int,
    videoSettings: com.bragastudio.mobile.core.domain.VideoSettings,
    onNdiToggle: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    availableAudioDevices: List<android.media.AudioDeviceInfo> = emptyList(),
    onToggleCameraSource: () -> Unit = {},
    onSetCameraSource: (String) -> Unit = {},
    onCycleResolution: () -> Unit = {},
    onSetResolution: (String) -> Unit = {},
    onCycleFps: () -> Unit = {},
    onSetFps: (Int) -> Unit = {},
    onCycleBitrate: () -> Unit = {},
    onSetBitrate: (Int) -> Unit = {},
    onCycleCodec: () -> Unit = {},
    onSetCodec: (String) -> Unit = {},
    onCycleAudioDevice: () -> Unit = {},
    onSelectAudioDevice: (android.media.AudioDeviceInfo) -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = HudTheme.spacingLarge),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
            // Centro: Configurações (Scrollável para caber em telas menores)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(horizontal = 8.dp)
            ) {
                // NDI Status
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onNdiToggle() }
                ) {
                    Box(
                        modifier = Modifier.size(10.dp).clip(CircleShape).background(if (isNdiEnabled) HudTheme.ndiActiveColor else Color.Gray)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("NDI", color = if (isNdiEnabled) HudTheme.ndiActiveColor else Color.Gray, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }

                if (isHudVisible) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(modifier = Modifier.width(1.dp).height(24.dp).background(Color.Gray.copy(alpha = 0.5f)))
                    Spacer(modifier = Modifier.width(12.dp))

                    TopBarSettingItem(
                        label = "RES",
                        value = videoSettings.resolution,
                        options = listOf("1080p", "1440p", "4K"),
                        onClick = onCycleResolution,
                        onSelect = onSetResolution
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    TopBarSettingItem(
                        label = "FPS",
                        value = fps.toString(),
                        options = listOf("24", "30", "60"),
                        onClick = onCycleFps,
                        onSelect = { onSetFps(it.toIntOrNull() ?: 30) }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    TopBarSettingItem(
                        label = "BITRATE",
                        value = videoSettings.bitrateMbps.toString(),
                        options = listOf("25", "50", "100"),
                        onClick = onCycleBitrate,
                        onSelect = { onSetBitrate(it.toIntOrNull() ?: 50) }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    TopBarSettingItem(
                        label = "CODEC",
                        value = videoSettings.codec,
                        options = listOf("H.264", "H.265"),
                        onClick = onCycleCodec,
                        onSelect = onSetCodec
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    val deviceNames = availableAudioDevices.map { it.productName.toString() }
                    TopBarSettingItem(
                        label = "MIC",
                        value = selectedAudioDeviceName.take(10),
                        options = if (deviceNames.isEmpty()) listOf("Default") else deviceNames,
                        onClick = onCycleAudioDevice,
                        onSelect = { name -> 
                            val dev = availableAudioDevices.find { it.productName.toString() == name }
                            dev?.let { onSelectAudioDevice(it) }
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    
                    TopBarSettingItem(
                        label = "FONTE",
                        value = videoSettings.videoSource,
                        options = listOf("Camera", "USB"),
                        onClick = onToggleCameraSource,
                        onSelect = onSetCameraSource
                    )
                }
            }
            
            // Lado direito: Bateria, Armazenamento e Engrenagem
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Status de Hardware sempre visível
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (metrics.isWifiConnected) Icons.Filled.Wifi else Icons.Filled.WifiOff, "WIFI", tint = if (metrics.isWifiConnected) Color.White else Color.Red, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("WIFI", color = Color.Gray, fontSize = 10.sp)
                    }
                    Text(if (metrics.isWifiConnected) "ON" else "OFF", color = if (metrics.isWifiConnected) Color.White else Color.Red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.width(1.dp).height(24.dp).background(Color.Gray.copy(alpha = 0.5f)))
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("BATERIA", color = Color.Gray, fontSize = 10.sp)
                    Text("${batteryPercentage}%", color = if (batteryPercentage > 20) Color.White else Color.Red, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    val remainingLabel = formatRecordingTimeRemaining(storageFreeGB, videoSettings.bitrateMbps)
                    Text("GRAVAÇÃO RESTANTE", color = Color.Gray, fontSize = 10.sp)
                    Text(
                        remainingLabel,
                        color = if (storageFreeGB > 5f) Color.White else Color.Red,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(String.format("%.1fGB livres", storageFreeGB), color = Color.Gray, fontSize = 9.sp)
                }
                Spacer(modifier = Modifier.width(16.dp))
                
                // Engrenagem apenas se o HUD estiver visível
                if (isHudVisible) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = Color.White,
                        modifier = Modifier.size(HudTheme.iconSizeMedium).clickable { onNavigateToSettings() }
                    )
                }
            }
    }
}

@Composable
fun RightControlsProfessional(
    isHudVisible: Boolean,
    isRecording: Boolean,
    currentLens: CameraInfoModel?,
    availableLenses: List<CameraInfoModel>,
    onRecordClick: () -> Unit,
    onLensSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(4.dp, Color.Black.copy(alpha=0.3f), CircleShape)
                .clickable { onRecordClick() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (isRecording) 24.dp else 48.dp)
                    .clip(if (isRecording) RoundedCornerShape(8.dp) else CircleShape)
                    .background(Color(0xFFD32F2F))
            )
        }
        
        if (isHudVisible) {
            Spacer(modifier = Modifier.height(16.dp))
            // Zoom/Lens
            SingleLensToggleButton(
                availableLenses = availableLenses,
                currentLens = currentLens,
                onLensSelect = onLensSelect,
                modifier = Modifier.size(HudTheme.toolButtonSize).clip(RoundedCornerShape(12.dp))
            )
        }
    }
}

@Composable
fun BottomInfoProfessional(
    isHudVisible: Boolean,
    currentGrid: String,
    onToggleGrid: () -> Unit,
    onSetGrid: (String) -> Unit,
    recordingTime: Long,
    isRecording: Boolean,
    audioLevelLeft: Float,
    audioLevelRight: Float,
    fps: Int,
    modifier: Modifier = Modifier
) {
    var showGridMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = HudTheme.spacingMedium, vertical = 2.dp), 
        horizontalArrangement = Arrangement.SpaceBetween, 
        verticalAlignment = Alignment.CenterVertically
    ) {
        // GUIA -> GRID
        if (isHudVisible) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onToggleGrid() },
                        onLongPress = { showGridMenu = true }
                    )
                }.padding(4.dp)
            ) {
                Icon(Icons.Filled.GridOn, "Grid", tint = if (currentGrid != "OFF") HudTheme.buttonActiveColor else Color.White, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (currentGrid != "OFF") currentGrid else "GRID", color = if (currentGrid != "OFF") HudTheme.buttonActiveColor else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        } else {
            Spacer(modifier = Modifier.width(48.dp)) // Maintain some spacing so layout doesn't collapse weirdly
        }

        // TIMECODE + REC
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Timer, "Time", tint = Color.White, modifier = Modifier.size(20.dp))
                Text(
                    text = formatTimecode(recordingTime, fps), 
                    color = Color.White, 
                    fontSize = 16.sp, 
                    fontWeight = FontWeight.Bold
                )
            }
            
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isRecording) Color.Red else Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!isRecording) {
                        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Color.Gray))
                    }
                    Text("REC", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
        
        // VU e PERFIL
        Row(verticalAlignment = Alignment.CenterVertically) {
            AudioMetersOverlay(audioLevelLeft = audioLevelLeft, audioLevelRight = audioLevelRight)
            if (isHudVisible) {
                Spacer(modifier = Modifier.width(16.dp))
                // TODO: IMPLEMENTAÇÃO DO BOTÃO PERFIL
                // Este botão está oculto através de alpha(0f) mas mantido clicável
                // para permitir uso futuro sem alterar o layout atual da tela.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .alpha(0f)
                        .clickable { 
                            // Ação do Perfil
                        }
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("PERFIL", color = Color.Gray, fontSize = 10.sp)
                        Text("Padrão", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    
    if (showGridMenu) {
        val gridOptions = listOf("OFF", "3x3", "4x4", "Centro")
        TranslucentFloatingMenu(
            items = gridOptions,
            selectedItem = currentGrid,
            onItemSelected = { grid -> onSetGrid(grid) },
            itemLabel = { it },
            onDismissRequest = { showGridMenu = false },
            modifier = Modifier.width(200.dp)
        )
    }
}

@Composable
fun AudioMetersOverlay(
    audioLevelLeft: Float,
    audioLevelRight: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.8f))
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioMeterChannel(level = audioLevelLeft, label = "L")
            AudioMeterChannel(level = audioLevelRight, label = "R")
        }
    }
}

// Zonas de cor do medidor no estilo "LED ladder" (Sony/profissional):
// verde até ~-12dBFS, amarelo até ~-3dBFS, vermelho dali até 0dBFS/clip.
private const val VU_SEGMENT_COUNT = 16
private const val VU_GREEN_ZONE_END = 0.68f   // ~-12dBFS
private const val VU_YELLOW_ZONE_END = 0.90f  // ~-3dBFS
private const val VU_CLIP_THRESHOLD = 0.98f   // ~0dBFS

private fun vuSegmentColor(segmentPosition: Float): Color = when {
    segmentPosition < VU_GREEN_ZONE_END -> Color(0xFF00E676)
    segmentPosition < VU_YELLOW_ZONE_END -> Color(0xFFFFD600)
    else -> Color(0xFFFF1744)
}

/**
 * Medidor de áudio estilo "LED ladder" (Sony/profissional): segmentos discretos que
 * acendem verde -> amarelo -> vermelho conforme o nível, 100% responsivo em tempo
 * real (sem peak-hold/decaimento — reflete a amostra atual a cada recomposição).
 * Em clipping, a barra inteira acende vermelha para ficar impossível de ignorar.
 */
@Composable
fun AudioMeterChannel(level: Float, label: String) {
    val normalizedLevel = normalizeAudioLevel(level)
    val isClipping = normalizedLevel >= VU_CLIP_THRESHOLD
    val litSegments = if (isClipping) VU_SEGMENT_COUNT else (normalizedLevel * VU_SEGMENT_COUNT).toInt()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(HudTheme.spacingSmall)
    ) {
        Text(
            text = label,
            color = if (isClipping) Color(0xFFFF1744) else HudTheme.textColorSecondary,
            fontSize = 10.sp,
            fontWeight = if (isClipping) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(14.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
            modifier = Modifier.width(100.dp).height(10.dp)
        ) {
            for (i in 0 until VU_SEGMENT_COUNT) {
                val segmentPosition = (i + 1f) / VU_SEGMENT_COUNT
                val isLit = isClipping || i < litSegments
                val color = if (isClipping) Color(0xFFFF1744) else vuSegmentColor(segmentPosition)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            color = if (isLit) color else color.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(1.dp)
                        )
                )
            }
        }

        // Indicador de clipping (quadrado que acende em 0dBFS+)
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isClipping) Color(0xFFFF1744) else HudTheme.buttonInactiveColor)
        )
    }
}

@Composable
fun <T> TranslucentFloatingMenu(
    items: List<T>,
    selectedItem: T?,
    onItemSelected: (T) -> Unit,
    itemLabel: (T) -> String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.ui.window.Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismissRequest
    ) {
        Box(
            modifier = modifier
                .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                .padding(8.dp)
        ) {
            androidx.compose.foundation.lazy.LazyColumn {
                items(items.size) { index ->
                    val item = items[index]
                    val isSelected = item == selectedItem
                    Text(
                        text = itemLabel(item),
                        color = if (isSelected) HudTheme.recordColor else Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onItemSelected(item); onDismissRequest() }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 16.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun TopBarSettingItem(
    label: String,
    value: String,
    options: List<String>,
    onClick: () -> Unit,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { onClick() },
                onLongPress = { expanded = true }
            )
        }
    ) {
        Text(label, color = Color.Gray, fontSize = 10.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { onSelect(option); expanded = false }
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LeftToolsSidebarProfessional(
    isScopesVisible: Boolean,
    isZebraEnabled: Boolean,
    isLutEnabled: Boolean,
    isFocusPeakingEnabled: Boolean,
    isFalseColorEnabled: Boolean,
    currentAspectRatio: String,
    allLuts: List<com.bragastudio.mobile.core.model.Lut>,
    activeLut: com.bragastudio.mobile.core.model.Lut?,
    onSelectLut: (String?) -> Unit,
    onSetAspectRatio: (String) -> Unit,
    onToggleScopes: () -> Unit,
    onToggleZebra: () -> Unit,
    onToggleLut: () -> Unit,
    onNavigateToLuts: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onToggleFocusPeaking: () -> Unit,
    onToggleFalseColor: () -> Unit,
    modifier: Modifier = Modifier,
    // Ajuste fino (0-100 / 0f-1f) exibido ao lado do botão quando a ferramenta está ativa.
    zebraThreshold: Int = 100,
    onSetZebraThreshold: (Int) -> Unit = {},
    focusPeakingSensitivity: Float = 0.5f,
    onSetFocusPeakingSensitivity: (Float) -> Unit = {}
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        // Scopes
        IconToggleButton(Icons.Filled.BarChart, "Scopes", isScopesVisible, onToggleScopes)

        // Zebra + slider de limiar (só aparece com a ferramenta ativa)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconToggleButton(Icons.Filled.Texture, "Zebra", isZebraEnabled, onToggleZebra)
            if (isZebraEnabled) {
                QuickAdjustSlider(
                    label = "LIMIAR",
                    value = zebraThreshold / 100f,
                    valueLabel = "$zebraThreshold%",
                    onValueChange = { onSetZebraThreshold((it * 100).toInt()) }
                )
            }
        }

        // Focus Peaking + slider de sensibilidade
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconToggleButton(Icons.Filled.CenterFocusStrong, "Focus Peaking", isFocusPeakingEnabled, onToggleFocusPeaking)
            if (isFocusPeakingEnabled) {
                QuickAdjustSlider(
                    label = "SENS.",
                    value = focusPeakingSensitivity,
                    valueLabel = when {
                        focusPeakingSensitivity < 0.34f -> "BAIXA"
                        focusPeakingSensitivity < 0.67f -> "MED"
                        else -> "ALTA"
                    },
                    onValueChange = onSetFocusPeakingSensitivity
                )
            }
        }
        // False Color
        IconToggleButton(Icons.Filled.InvertColors, "False Color", isFalseColorEnabled, onToggleFalseColor)
        
        // LUT - with dropdown
        var showLutMenu by remember { mutableStateOf(false) }
        Box {
            Box(
                modifier = Modifier
                    .size(HudTheme.toolButtonSize)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isLutEnabled) HudTheme.buttonActiveColor else Color.Black.copy(alpha = 0.6f))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onToggleLut() },
                            onLongPress = { showLutMenu = true }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.ColorLens, "LUT", tint = Color.White, modifier = Modifier.size(HudTheme.iconSizeMedium))
            }
            
            DropdownMenu(expanded = showLutMenu, onDismissRequest = { showLutMenu = false }) {
                DropdownMenuItem(
                    text = { Text(if (activeLut == null) "✓ Nenhum (Desativado)" else "Nenhum (Desativado)") },
                    onClick = { onSelectLut(null); showLutMenu = false }
                )
                allLuts.forEach { lut ->
                    DropdownMenuItem(
                        text = { Text(if (activeLut?.id == lut.id) "✓ ${lut.displayName}" else lut.displayName) },
                        onClick = { onSelectLut(lut.id); showLutMenu = false }
                    )
                }
                androidx.compose.material3.Divider()
                DropdownMenuItem(
                    text = { Text("Gerenciar LUTs...", color = HudTheme.buttonActiveColor) },
                    onClick = { onNavigateToLuts(); showLutMenu = false }
                )
            }
        }
        
        // Aspect Ratio - with dropdown
        var showAspectRatioMenu by remember { mutableStateOf(false) }
        val aspectRatios = listOf("OFF", "4:3", "16:9", "2.35:1", "1:1")
        Box {
            Box(
                modifier = Modifier
                    .size(HudTheme.toolButtonSize)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (currentAspectRatio != "OFF") HudTheme.buttonActiveColor else Color.Black.copy(alpha = 0.6f))
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onToggleAspectRatio() },
                            onLongPress = { showAspectRatioMenu = true }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.AspectRatio, "Aspect Ratio", tint = Color.White, modifier = Modifier.size(HudTheme.iconSizeMedium))
            }
            
            DropdownMenu(expanded = showAspectRatioMenu, onDismissRequest = { showAspectRatioMenu = false }) {
                aspectRatios.forEach { ratio ->
                    DropdownMenuItem(
                        text = { Text(ratio) },
                        onClick = { onSetAspectRatio(ratio); showAspectRatioMenu = false }
                    )
                }
            }
        }
    }
}

@Composable
fun SingleLensToggleButton(
    availableLenses: List<CameraInfoModel>,
    currentLens: CameraInfoModel?,
    onLensSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (availableLenses.isEmpty()) return
    
    val currentIndex = availableLenses.indexOfFirst { it.id == currentLens?.id }
    
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable {
                val nextIndex = (currentIndex + 1) % availableLenses.size
                onLensSelect(availableLenses[nextIndex].id)
            },
        contentAlignment = Alignment.Center
    ) {
        val label = if (currentLens != null) getLensLabel(currentLens.name) else "1x"
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}

@Composable
fun IconToggleButton(
    icon: ImageVector,
    contentDescription: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(HudTheme.toolButtonSize)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) HudTheme.buttonActiveColor else Color.Black.copy(alpha = 0.6f))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(HudTheme.iconSizeMedium))
    }
}
