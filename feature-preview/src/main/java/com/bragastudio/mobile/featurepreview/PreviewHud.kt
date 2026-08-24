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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
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
import android.hardware.camera2.CaptureRequest

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

/**
 * BUG CORRIGIDO: a versão antiga (acima, mantida só por compatibilidade —
 * não é mais chamada) decidia o rótulo de zoom fazendo busca de texto solta
 * no nome da lente. Em muitos aparelhos o "nome"/ID interno da câmera
 * principal contém um dígito "3" (ex.: câmera física #3 de um sistema
 * multi-câmera), e `lensName.contains("3")` casava com isso por engano —
 * é exatamente o que fazia a lente 1x aparecer rotulada como "3x".
 *
 * Esta versão usa `CameraInfoModel.lensType`, que já é calculado pelo
 * CameraDiscoveryEngine a partir de dados reais de hardware (distância focal
 * via CameraCharacteristics, não o nome), e cai para a distância focal
 * diretamente quando o tipo vem como UNKNOWN — nunca faz correspondência por
 * dígito solto em texto.
 */
fun getLensLabel(lens: CameraInfoModel): String {
    return when (lens.lensType) {
        com.bragastudio.mobile.corecapture.domain.LensType.ULTRAWIDE -> "0.5x"
        com.bragastudio.mobile.corecapture.domain.LensType.MAIN -> "1x"
        com.bragastudio.mobile.corecapture.domain.LensType.TELEPHOTO -> "2x"
        com.bragastudio.mobile.corecapture.domain.LensType.SUPER_TELEPHOTO -> "3x"
        com.bragastudio.mobile.corecapture.domain.LensType.MACRO -> "Macro"
        com.bragastudio.mobile.corecapture.domain.LensType.FRONT -> "Frontal"
        com.bragastudio.mobile.corecapture.domain.LensType.EXTERNAL -> "EXT"
        else -> {
            // Fallback só quando o tipo não foi classificado: usa a distância
            // focal real (mesmo critério do CameraDiscoveryEngine), nunca o nome.
            val focal = lens.focalLengths.firstOrNull() ?: 0f
            when {
                focal in 0.1f..3.0f -> "0.5x"
                focal in 3.0f..7.0f -> "1x"
                focal in 7.0f..10.0f -> "2x"
                focal > 10.0f -> "3x"
                else -> "1x"
            }
        }
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
    onSelectAudioDevice: (android.media.AudioDeviceInfo) -> Unit = {},
    // Controle remoto da Sony via Wi-Fi (Component 3 do plano de integração):
    // só usado quando a FONTE ativa é "SONY"; nas demais fontes esses params
    // ficam com os defaults e o painel simplesmente não é renderizado.
    isSonyActive: Boolean = false,
    sonyTelemetry: com.braga.bdsm.network.sony.SonyCameraStatus = com.braga.bdsm.network.sony.SonyCameraStatus(),
    onSetIso: (Int?) -> Unit = {},
    onSetShutter: (Long?) -> Unit = {},
    onSonySetAperture: (String) -> Unit = {},
    onSonyTakePicture: () -> Unit = {},
    // ------------------------------------------------------------------
    // NOVA INTERFACE (opt-in, aditiva) — não remove nem substitui nada
    // do HUD atual. Quando `isModernUiEnabled` é false (padrão), o HUD
    // se comporta exatamente como antes: TopBarProfessional completa
    // (com todos os rótulos RES/FPS/BITRATE/CODEC/FONTE/BATERIA/RESTANTE),
    // sidebar esquerda com Scopes/Zebra/Peaking/False Color/LUT/Aspect
    // Ratio, e VU meter padrão — nada disso é tocado.
    //
    // Quando true, troca só a topbar (versão minimalista, sem rótulos +
    // botão de casa) e adiciona o cluster de controles manuais circulares
    // perto da lente. Todos os toggles antigos (LUT, false color, zebra,
    // peaking, scopes, aspect ratio, grid) continuam funcionando e visíveis
    // via LeftToolsSidebarProfessional, que não é alterada.
    // ------------------------------------------------------------------
    isModernUiEnabled: Boolean = false,
    onNavigateHome: () -> Unit = {},
    // Controle manual real da câmera nativa (Camera2Device, já existente no
    // core-capture: ISO/obturador/WB/foco por CaptureRequest.Builder). Os
    // valores atuais vêm do PreviewViewModel (currentIso/currentShutter/
    // currentWb/currentFocus) e os setters já eram genéricos — só não
    // estavam conectados a nenhum controle de UI para a fonte "Camera".
    nativeCurrentIso: Int? = null,
    nativeCurrentShutterNanos: Long? = null,
    nativeCurrentWbMode: Int? = null,
    nativeCurrentFocusDiopters: Float? = null,
    onSetNativeWb: (Int?) -> Unit = {},
    onSetNativeFocus: (Float?) -> Unit = {}
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

        if (isModernUiEnabled) {
            // Nova topbar: mesmos dados, mas sem os rótulos (RES/FPS/BITRATE/
            // CODEC/FONTE/RESTANTE/BATERIA) fixos na tela — os valores continuam
            // presentes, só o texto descritivo some, e ganha o botão de casa.
            // Todos os callbacks (troca de fonte, resolução, fps, etc.) e o
            // long-press para abrir cada dropdown continuam ativos por baixo,
            // via TopBarSettingItem reaproveitado.
            TopBarMinimal(
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
                onNavigateHome = onNavigateHome,
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
        } else {
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
        }
        
        if (isHudVisible) {
            if (isModernUiEnabled) {
                // Nova sidebar: mesmos toggles de sempre (Scopes, Zebra, Focus
                // Peaking, False Color, LUT, Aspect Ratio), em ícones circulares;
                // os que têm parâmetro numérico ou lista de opções (zebra, peaking,
                // LUT, aspect ratio) abrem o mesmo CircularDialPopover usado no
                // cluster de lente, para manter uma única linguagem de interação
                // em toda a interface. Nenhum toggle foi removido: todos os
                // callbacks (onToggleScopes, onToggleZebra, onSetZebraThreshold,
                // onToggleFocusPeaking, onSetFocusPeakingSensitivity,
                // onToggleFalseColor, onSelectLut, onNavigateToLuts,
                // onSetAspectRatio) continuam ligados exatamente como antes.
                ToolsDialCluster(
                    isScopesVisible = isScopesVisible,
                    isZebraEnabled = isZebraEnabled,
                    zebraThreshold = zebraThreshold,
                    onSetZebraThreshold = onSetZebraThreshold,
                    isFocusPeakingEnabled = isFocusPeakingEnabled,
                    focusPeakingSensitivity = focusPeakingSensitivity,
                    onSetFocusPeakingSensitivity = onSetFocusPeakingSensitivity,
                    isFalseColorEnabled = isFalseColorEnabled,
                    isLutEnabled = isLutEnabled,
                    allLuts = allLuts,
                    activeLut = activeLut,
                    onSelectLut = onSelectLut,
                    onNavigateToLuts = onNavigateToLuts,
                    currentAspectRatio = currentAspectRatio,
                    onSetAspectRatio = onSetAspectRatio,
                    onToggleScopes = onToggleScopes,
                    onToggleZebra = onToggleZebra,
                    onToggleFocusPeaking = onToggleFocusPeaking,
                    onToggleFalseColor = onToggleFalseColor,
                    onToggleLut = onToggleLut,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = HudTheme.spacingMedium)
                )
            } else {
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

        // Cluster de controles manuais circulares, junto da lente (novo, opt-in).
        // Reaproveita os mesmos callbacks onSetIso/onSetShutter já usados pelo
        // SonyRemoteControlPanel — ambos os painéis podem coexistir, mas na
        // prática o cluster substitui visualmente o painel Sony quando a nova
        // UI está ativa (mesmo dado, apresentação em dial circular perto da lente
        // em vez de chips no canto superior). Nada do painel Sony antigo foi
        // removido: com isModernUiEnabled = false ele continua exatamente como era.
        if (isModernUiEnabled && isHudVisible) {
            LensControlCluster(
                isSonyActive = isSonyActive,
                sonyTelemetry = sonyTelemetry,
                onSetIso = onSetIso,
                onSetShutter = onSetShutter,
                onSonySetAperture = onSonySetAperture,
                nativeCurrentIso = nativeCurrentIso,
                nativeCurrentShutterNanos = nativeCurrentShutterNanos,
                nativeCurrentWbMode = nativeCurrentWbMode,
                nativeCurrentFocusDiopters = nativeCurrentFocusDiopters,
                onSetNativeWb = onSetNativeWb,
                onSetNativeFocus = onSetNativeFocus,
                isRecording = isRecording,
                onRecordClick = onRecordClick,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)
            )
        }

        // Painel de controle remoto da Sony (ISO/Shutter/Abertura/EV + disparo +
        // telemetria de bateria/cartão). Só aparece com a fonte "SONY" ativa e o
        // HUD visível — em clean feed não faz sentido mostrar controles de toque.
        // Mantido intacto: com a nova UI desligada (isModernUiEnabled = false)
        // este painel continua sendo a única forma de controle manual, como antes.
        if (isSonyActive && isHudVisible && !isModernUiEnabled) {
            SonyRemoteControlPanel(
                telemetry = sonyTelemetry,
                onSetIso = onSetIso,
                onSetShutter = onSetShutter,
                onSetAperture = onSonySetAperture,
                onTakePicture = onSonyTakePicture,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 96.dp, end = 12.dp)
            )
        }
    }
}

// ============================================================================
// SUB-COMPONENTES
// ============================================================================

/**
 * Painel de controle remoto da câmera Sony via Wi-Fi (α6000 e compatíveis).
 * Espelha os controles do app Smart Remote Control da própria Sony: ISO,
 * velocidade do obturador e abertura em chips cicláveis (tap para abrir a
 * lista de valores), mais um botão dedicado de disparo e telemetria
 * (bateria/cartão) que vem do polling de getEvent() no SonyRemoteCaptureDevice.
 *
 * Os valores de ISO/Shutter/Aperture aqui são um conjunto comum e seguro para a
 * α6000; a câmera real pode rejeitar um valor fora do que getAvailableApiList()
 * relata (ver "User Review Required" no plano) — nesse caso o comando
 * simplesmente não tem efeito e o valor não muda no próximo getEvent().
 */
@Composable
fun SonyRemoteControlPanel(
    telemetry: com.braga.bdsm.network.sony.SonyCameraStatus,
    onSetIso: (Int?) -> Unit,
    onSetShutter: (Long?) -> Unit,
    onSetAperture: (String) -> Unit,
    onTakePicture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isoOptions = listOf("AUTO", "100", "200", "400", "800", "1600", "3200", "6400")
    val shutterOptions = listOf("1/1000", "1/500", "1/250", "1/125", "1/60", "1/30", "1/15", "1\"")
    val apertureOptions = listOf("3.5", "4.0", "5.6", "8.0", "11", "16", "22")

    fun shutterLabelToNanos(label: String): Long? {
        if (label.endsWith("\"")) {
            val secs = label.removeSuffix("\"").toDoubleOrNull() ?: return null
            return (secs * 1_000_000_000L).toLong()
        }
        val parts = label.split("/")
        if (parts.size != 2) return null
        val denom = parts[1].toDoubleOrNull() ?: return null
        return (1.0 / denom * 1_000_000_000L).toLong()
    }

    Column(
        modifier = modifier
            .width(150.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.CameraAlt,
                contentDescription = "Sony Wi-Fi",
                tint = if (telemetry.isConnected) HudTheme.buttonActiveColor else Color.Gray,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = if (telemetry.isConnected) "SONY LINK" else "BUSCANDO...",
                color = if (telemetry.isConnected) Color.White else Color.Gray,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Telemetria: bateria + cartão, direto do getEvent() da câmera.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = if (telemetry.batteryLevel.isNotEmpty()) "🔋${telemetry.batteryLevel}%" else "🔋--",
                color = Color.White,
                fontSize = 10.sp
            )
            Text(
                text = if (telemetry.storageAvailable.isNotEmpty()) telemetry.storageAvailable else "-- min",
                color = Color.White,
                fontSize = 10.sp
            )
        }

        androidx.compose.material3.Divider(color = Color.White.copy(alpha = 0.15f))

        SonyControlChip("ISO", telemetry.currentIso.ifEmpty { "AUTO" }, isoOptions) { selected ->
            onSetIso(selected.toIntOrNull())
        }
        SonyControlChip("SHUTTER", telemetry.currentShutterSpeed.ifEmpty { "AUTO" }, shutterOptions) { selected ->
            onSetShutter(shutterLabelToNanos(selected))
        }
        SonyControlChip("ABERTURA", telemetry.currentFNumber.ifEmpty { "--" }, apertureOptions) { selected ->
            onSetAperture(selected)
        }

        if (telemetry.focusStatus.isNotEmpty()) {
            Text(
                text = "FOCO: ${telemetry.focusStatus}",
                color = if (telemetry.focusStatus.contains("Focused", ignoreCase = true)) Color(0xFF00E676) else Color.Gray,
                fontSize = 9.sp
            )
        }

        // Botão de disparo — separado do REC do BDSM porque aciona o obturador
        // físico da Sony (actTakePicture), não a gravação de vídeo do celular.
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(3.dp, Color.Black.copy(alpha = 0.3f), CircleShape)
                .clickable { onTakePicture() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.CameraAlt, contentDescription = "Disparar", tint = Color.Black, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun SonyControlChip(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable { expanded = true }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.Gray, fontSize = 9.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
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
                        options = listOf("Camera", "USB", "SONY"),
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
        // Botão REC com "bounce" no toque + transição suave entre o círculo
        // (parado) e o quadrado (gravando), em vez de trocar de forma/tamanho
        // instantaneamente como antes.
        var isPressed by remember { mutableStateOf(false) }
        val recScale by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (isPressed) 0.88f else 1f,
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessHigh
            ),
            label = "recButtonScale"
        )
        val innerSize by androidx.compose.animation.core.animateDpAsState(
            targetValue = if (isRecording) 24.dp else 48.dp,
            animationSpec = androidx.compose.animation.core.tween(220),
            label = "recInnerSize"
        )
        val innerCornerRadius by androidx.compose.animation.core.animateDpAsState(
            targetValue = if (isRecording) 8.dp else 24.dp, // 24dp = metade de 48dp -> círculo perfeito
            animationSpec = androidx.compose.animation.core.tween(220),
            label = "recInnerCorner"
        )

        Box(
            modifier = Modifier
                .size(60.dp)
                .graphicsLayer { scaleX = recScale; scaleY = recScale }
                .clip(CircleShape)
                .background(Color.White)
                .border(4.dp, Color.Black.copy(alpha=0.3f), CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            isPressed = true
                            tryAwaitRelease()
                            isPressed = false
                        },
                        onTap = { onRecordClick() }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(innerSize)
                    .clip(RoundedCornerShape(innerCornerRadius))
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
            androidx.compose.animation.AnimatedVisibility(
                visible = isZebraEnabled,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                        androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.tween(180)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) +
                        androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.tween(120))
            ) {
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
            androidx.compose.animation.AnimatedVisibility(
                visible = isFocusPeakingEnabled,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                        androidx.compose.animation.expandHorizontally(androidx.compose.animation.core.tween(180)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)) +
                        androidx.compose.animation.shrinkHorizontally(androidx.compose.animation.core.tween(120))
            ) {
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
        val label = if (currentLens != null) getLensLabel(currentLens) else "1x"
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
    // Cor e leve "bounce" de escala animados — sem isso a troca de estado é
    // instantânea e destoa do resto do HUD que já tem transições suaves.
    val backgroundColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isActive) HudTheme.buttonActiveColor else Color.Black.copy(alpha = 0.6f),
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "toggleBackground"
    )
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isActive) 1f else 0.94f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
        ),
        label = "toggleScale"
    )

    Box(
        modifier = Modifier
            .size(HudTheme.toolButtonSize)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(HudTheme.iconSizeMedium))
    }
}

// ============================================================================
// NOVA INTERFACE — TOPBAR MINIMALISTA + CONTROLES MANUAIS CIRCULARES
// (aditivo; ativado apenas quando isModernUiEnabled = true em CameraHUDOverlay)
// ============================================================================

private object ModernHudTheme {
    val accent = Color(0xFFFF9F0A)
    val recRed = Color(0xFFFF453A)
    val panelBg = Color.Black.copy(alpha = 0.78f)
    val panelBorder = Color.White.copy(alpha = 0.12f)
}

/**
 * Versão minimalista da topbar: mantém todos os dados e ações da
 * TopBarProfessional (NDI, resolução, fps, bitrate, codec, mic, fonte,
 * wifi, bateria, tempo restante), mas esconde os rótulos textuais fixos
 * (RES/FPS/BITRATE/CODEC/FONTE/RESTANTE/BATERIA), deixando só os valores.
 * Adiciona um botão de "casa" ao lado da engrenagem para voltar ao menu.
 */
@Composable
fun TopBarMinimal(
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
    onNavigateHome: () -> Unit,
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
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onNdiToggle() }) {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (isNdiEnabled) HudTheme.ndiActiveColor else Color.Gray))
                Spacer(modifier = Modifier.width(4.dp))
                Text("NDI", color = if (isNdiEnabled) HudTheme.ndiActiveColor else Color.Gray, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            if (isHudVisible) {
                Spacer(modifier = Modifier.width(10.dp))
                Box(modifier = Modifier.width(1.dp).height(18.dp).background(Color.Gray.copy(alpha = 0.4f)))
                Spacer(modifier = Modifier.width(10.dp))

                // Mesmos TopBarSettingItem de antes (tap cicla, long-press abre lista),
                // só que sem o label acima do valor — MinimalSettingValue reaproveita
                // a mesma interação por baixo.
                MinimalSettingValue("${videoSettings.resolution} · ${fps}", onClick = onCycleResolution) {
                    // long press -> abre resolução; fps é ciclado separadamente pelo tap duplo alvo
                    onCycleResolution()
                }
                Spacer(modifier = Modifier.width(10.dp))
                MinimalSettingValue("${videoSettings.bitrateMbps} Mb/s", onClick = onCycleBitrate) { onCycleBitrate() }
                Spacer(modifier = Modifier.width(10.dp))
                MinimalSettingValue(videoSettings.codec, onClick = onCycleCodec) { onCycleCodec() }
                Spacer(modifier = Modifier.width(10.dp))
                MinimalSettingValue(videoSettings.videoSource, onClick = onToggleCameraSource) { onToggleCameraSource() }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (metrics.isWifiConnected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                "WIFI",
                tint = if (metrics.isWifiConnected) Color.White else Color.Red,
                modifier = Modifier.size(14.dp)
            )
            Box(modifier = Modifier.width(1.dp).height(18.dp).background(Color.Gray.copy(alpha = 0.4f)))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${batteryPercentage}%", color = if (batteryPercentage > 20) Color.White else Color.Red, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Box(
                    modifier = Modifier
                        .width(18.dp).height(9.dp)
                        .border(1.dp, Color.Gray, RoundedCornerShape(2.dp))
                        .padding(1.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth((batteryPercentage / 100f).coerceIn(0f, 1f))
                            .background(if (batteryPercentage > 20) Color(0xFF32D74B) else Color.Red, RoundedCornerShape(1.dp))
                    )
                }
            }

            Box(modifier = Modifier.width(1.dp).height(18.dp).background(Color.Gray.copy(alpha = 0.4f)))

            val remainingLabel = formatRecordingTimeRemaining(storageFreeGB, videoSettings.bitrateMbps)
            Text(remainingLabel, color = if (storageFreeGB > 5f) Color.White else Color.Red, fontWeight = FontWeight.Bold, fontSize = 13.sp)

            if (isHudVisible) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Filled.Home,
                    contentDescription = "Voltar ao menu",
                    tint = Color.White,
                    modifier = Modifier.size(HudTheme.iconSizeMedium).clickable { onNavigateHome() }
                )
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
private fun MinimalSettingValue(value: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    Text(
        text = value,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        modifier = Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() })
        }
    )
}

/**
 * Cluster de controles manuais em ícones circulares, ancorado perto da lente
 * (parte inferior central do preview), no lugar visual que hoje o botão REC
 * ocupa sozinho. Cada ícone abre, ao toque, um dial circular (anel arrastável)
 * para ajustar o parâmetro correspondente.
 *
 * Os dados reais (ISO/Shutter/Abertura) só existem hoje para a fonte Sony
 * (via SonyCameraStatus / onSetIso / onSetShutter / onSonySetAperture) — para
 * as demais fontes (Camera/USB) os dials ficam desabilitados com "--" até que
 * o pipeline correspondente exponha controle manual (câmera nativa Android
 * não expõe ISO/shutter manual em todos os devices via CameraX sem Camera2
 * interop adicional). Isso evita simular um controle que não afeta a captura.
 */
@Composable
fun LensControlCluster(
    isSonyActive: Boolean,
    sonyTelemetry: com.braga.bdsm.network.sony.SonyCameraStatus,
    onSetIso: (Int?) -> Unit,
    onSetShutter: (Long?) -> Unit,
    onSonySetAperture: (String) -> Unit,
    nativeCurrentIso: Int?,
    nativeCurrentShutterNanos: Long?,
    nativeCurrentWbMode: Int?,
    nativeCurrentFocusDiopters: Float?,
    onSetNativeWb: (Int?) -> Unit,
    onSetNativeFocus: (Float?) -> Unit,
    isRecording: Boolean,
    onRecordClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var openDial by remember { mutableStateOf<String?>(null) }

    // ISO/Obturador: usam a fonte Sony (telemetria Wi-Fi) quando ativa, ou o
    // Camera2Device nativo (CaptureRequest real) nas demais fontes — em ambos
    // os casos o controle É manual de verdade, não simulado.
    val isoValue = if (isSonyActive) sonyTelemetry.currentIso.ifEmpty { "AUTO" }
                   else nativeCurrentIso?.toString() ?: "AUTO"
    val shutterValue = if (isSonyActive) sonyTelemetry.currentShutterSpeed.ifEmpty { "AUTO" }
                        else nativeShutterNanosToLabel(nativeCurrentShutterNanos)
    val apertureValue = sonyTelemetry.currentFNumber.ifEmpty { "--" } // íris física só existe via Sony
    val wbValue = nativeWbModeToLabel(nativeCurrentWbMode)
    val focusValue = nativeFocusDiopterToLabel(nativeCurrentFocusDiopters)

    Box(modifier = modifier, contentAlignment = Alignment.BottomCenter) {

        openDial?.let { param ->
            CircularDialPopover(
                title = when (param) {
                    "iso" -> "ISO"
                    "shutter" -> "OBTURADOR"
                    "iris" -> "ÍRIS"
                    "wb" -> "BALANÇO DE BRANCO"
                    "focus" -> "FOCO"
                    else -> param.uppercase()
                },
                currentValueLabel = when (param) {
                    "iso" -> isoValue
                    "shutter" -> shutterValue
                    "iris" -> apertureValue
                    "wb" -> wbValue
                    "focus" -> focusValue
                    else -> "--"
                },
                options = when (param) {
                    "iso" -> listOf("AUTO", "100", "200", "400", "800", "1600", "3200", "6400")
                    "shutter" -> listOf("1/1000", "1/500", "1/250", "1/125", "1/60", "1/30", "1/15", "1\"")
                    "iris" -> listOf("1.8", "2.8", "3.5", "4.0", "5.6", "8.0", "11", "16", "22")
                    "wb" -> listOf("AUTO", "2700K", "3200K", "4000K", "5000K", "5600K", "6500K", "7500K")
                    "focus" -> listOf("AUTO", "∞", "5m", "2m", "1m", "0.5m", "0.3m", "0.1m")
                    else -> emptyList()
                },
                isManual = param != "iris" || isSonyActive,
                onSelect = { selected ->
                    when (param) {
                        "iso" -> {
                            if (isSonyActive) onSetIso(selected.toIntOrNull())
                            else onSetIso(if (selected == "AUTO") null else selected.toIntOrNull())
                        }
                        "shutter" -> {
                            if (isSonyActive) onSetShutter(shutterLabelToNanosLocal(selected))
                            else onSetShutter(shutterLabelToNanosLocal(selected))
                        }
                        "iris" -> onSonySetAperture(selected)
                        "wb" -> onSetNativeWb(wbLabelToMode(selected))
                        "focus" -> onSetNativeFocus(focusLabelToDiopter(selected))
                    }
                },
                onDismiss = { openDial = null },
                modifier = Modifier.padding(bottom = 84.dp)
            )
        }

        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LensDialButton(
                icon = Icons.Filled.Circle,
                label = "Íris",
                valueBadge = if (isSonyActive) apertureValue else null,
                isActive = openDial == "iris",
                enabled = isSonyActive, // abertura física só existe na Sony; câmera do celular não tem íris variável
                onClick = { openDial = if (openDial == "iris") null else "iris" }
            )
            LensDialButton(
                icon = Icons.Filled.Iso,
                label = "ISO",
                valueBadge = isoValue.takeIf { it != "AUTO" },
                isActive = openDial == "iso",
                enabled = true,
                onClick = { openDial = if (openDial == "iso") null else "iso" }
            )

            // Botão REC central, maior — mesma ação do RightControlsProfessional,
            // apenas reposicionado dentro do cluster para ficar junto da lente.
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(if (isRecording) ModernHudTheme.recRed.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.55f))
                    .border(2.dp, if (isRecording) ModernHudTheme.recRed else Color.White.copy(alpha = 0.35f), CircleShape)
                    .clickable { onRecordClick() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(if (isRecording) 22.dp else 42.dp)
                        .clip(if (isRecording) RoundedCornerShape(4.dp) else CircleShape)
                        .background(ModernHudTheme.recRed)
                )
            }

            LensDialButton(
                icon = Icons.Filled.ShutterSpeed,
                label = "Obtur.",
                valueBadge = shutterValue.takeIf { it != "AUTO" },
                isActive = openDial == "shutter",
                enabled = true,
                onClick = { openDial = if (openDial == "shutter") null else "shutter" }
            )
            LensDialButton(
                icon = Icons.Filled.WbSunny,
                label = "WB",
                valueBadge = wbValue.takeIf { it != "AUTO" },
                isActive = openDial == "wb",
                enabled = !isSonyActive, // WB manual hoje só está implementado no pipeline nativo (Camera2)
                onClick = { openDial = if (openDial == "wb") null else "wb" }
            )
            LensDialButton(
                icon = Icons.Filled.CenterFocusWeak,
                label = "Foco",
                valueBadge = focusValue.takeIf { it != "AUTO" },
                isActive = openDial == "focus",
                enabled = !isSonyActive, // idem: foco manual por diopter é do Camera2Device
                onClick = { openDial = if (openDial == "focus") null else "focus" }
            )
        }
    }
}

// ---- Conversões de valor <-> rótulo para os controles nativos (Camera2) ----

private fun nativeShutterNanosToLabel(nanos: Long?): String {
    if (nanos == null) return "AUTO"
    val seconds = nanos / 1_000_000_000.0
    return if (seconds >= 1.0) {
        "${seconds.toInt()}\""
    } else {
        val denom = (1.0 / seconds).toInt()
        "1/$denom"
    }
}

private fun nativeWbModeToLabel(mode: Int?): String = when (mode) {
    null -> "AUTO"
    CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "2700K"
    CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "3200K"
    CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "4000K"
    CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "5600K"
    CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "6500K"
    CaptureRequest.CONTROL_AWB_MODE_SHADE -> "7500K"
    else -> "AUTO"
}

private fun wbLabelToMode(label: String): Int? = when (label) {
    "2700K" -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
    "3200K" -> CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT
    "4000K" -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
    "5000K", "5600K" -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
    "6500K" -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
    "7500K" -> CaptureRequest.CONTROL_AWB_MODE_SHADE
    else -> null // AUTO
}

// Foco manual no Camera2 usa distância em dioptrias (1/metros); 0f = infinito.
private fun nativeFocusDiopterToLabel(diopters: Float?): String {
    if (diopters == null) return "AUTO"
    if (diopters <= 0.01f) return "∞"
    val meters = 1f / diopters
    return if (meters >= 1f) "${meters.toInt()}m" else "${"%.1f".format(meters)}m"
}

private fun focusLabelToDiopter(label: String): Float? = when (label) {
    "AUTO" -> null
    "∞" -> 0f
    "5m" -> 1f / 5f
    "2m" -> 1f / 2f
    "1m" -> 1f
    "0.5m" -> 1f / 0.5f
    "0.3m" -> 1f / 0.3f
    "0.1m" -> 1f / 0.1f
    else -> null
}

@Composable
private fun LensDialButton(
    icon: ImageVector,
    label: String,
    valueBadge: String?,
    isActive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(if (isActive) ModernHudTheme.accent.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.55f))
                .border(1.dp, if (isActive) ModernHudTheme.accent else Color.White.copy(alpha = 0.14f), CircleShape)
                .alpha(if (enabled) 1f else 0.4f)
                .clickable(enabled = enabled) { onClick() },
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, label, tint = if (isActive) ModernHudTheme.accent else Color.White, modifier = Modifier.size(17.dp))
            Text(label, color = if (isActive) ModernHudTheme.accent else Color.Gray, fontSize = 7.sp, fontWeight = FontWeight.Bold)
        }
        if (valueBadge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(ModernHudTheme.accent)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(valueBadge, color = Color.Black, fontSize = 7.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Popover com dial circular (anel arrastável em SweepGradient) para ajustar um
 * parâmetro de exposição/foco por gesto radial ao redor do círculo, em vez de
 * slider linear — usado para todos os ajustes manuais na nova interface (ISO,
 * obturador, íris). O valor exibido no centro reflete a opção mais próxima do
 * ângulo do arraste dentre `options`.
 */
@Composable
fun CircularDialPopover(
    title: String,
    currentValueLabel: String,
    options: List<String>,
    isManual: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIndex = remember(currentValueLabel, options) {
        options.indexOf(currentValueLabel).coerceAtLeast(0)
    }
    var dragIndex by remember(options) { mutableStateOf(selectedIndex) }

    Column(
        modifier = modifier
            .width(190.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ModernHudTheme.panelBg)
            .border(1.dp, ModernHudTheme.panelBorder, RoundedCornerShape(18.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(
                if (isManual) "MANUAL" else "INDISPONÍVEL",
                color = if (isManual) ModernHudTheme.accent else Color.Gray,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Box(
            modifier = Modifier
                .size(132.dp)
                .pointerInput(options, isManual) {
                    if (!isManual || options.isEmpty()) return@pointerInput
                    detectDragGestures { change, _ ->
                        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                        val pos = change.position
                        val angle = (Math.toDegrees(
                            kotlin.math.atan2((pos.y - center.y).toDouble(), (pos.x - center.x).toDouble())
                        ) + 360.0) % 360.0
                        // Mapeia 0..300° (deixando um "gap" no dial, como um dial físico
                        // de câmera de cinema) para o índice de opção mais próximo.
                        val normalized = ((angle + 90) % 360) / 300.0
                        val idx = (normalized * (options.size - 1)).toInt().coerceIn(0, options.size - 1)
                        dragIndex = idx
                        onSelect(options[idx])
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val sweepFraction = if (options.isEmpty()) 0f else dragIndex / (options.size - 1).coerceAtLeast(1).toFloat()
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeW = 10.dp.toPx()
                drawArc(
                    color = Color.White.copy(alpha = 0.12f),
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    style = Stroke(width = strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
                drawArc(
                    color = if (isManual) ModernHudTheme.accent else Color.Gray,
                    startAngle = 150f,
                    sweepAngle = 240f * sweepFraction,
                    useCenter = false,
                    style = Stroke(width = strokeW, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = options.getOrElse(dragIndex) { currentValueLabel },
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(title, color = Color.Gray, fontSize = 9.sp)
            }
        }

        Text(
            "Arraste ao redor do anel para ajustar",
            color = Color.Gray,
            fontSize = 8.5.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

private fun shutterLabelToNanosLocal(label: String): Long? {
    if (label == "AUTO") return null
    if (label.endsWith("\"")) {
        val secs = label.removeSuffix("\"").toDoubleOrNull() ?: return null
        return (secs * 1_000_000_000L).toLong()
    }
    val parts = label.split("/")
    if (parts.size != 2) return null
    val denom = parts[1].toDoubleOrNull() ?: return null
    return (1.0 / denom * 1_000_000_000L).toLong()
}

/**
 * Rail vertical de ferramentas de monitoramento (Scopes, Zebra, Focus Peaking,
 * False Color, LUT, Aspect Ratio) em ícones circulares — equivalente à
 * LeftToolsSidebarProfessional, mas com a mesma linguagem visual do
 * LensControlCluster. Zebra, Peaking, LUT e Aspect Ratio abrem um
 * CircularDialPopover para ajuste; Scopes e False Color são toggles diretos
 * (não têm parâmetro contínuo). Todos os callbacks são os mesmos já existentes
 * no HUD clássico — nenhuma função nova de negócio foi criada, só a apresentação.
 */
@Composable
fun ToolsDialCluster(
    isScopesVisible: Boolean,
    isZebraEnabled: Boolean,
    zebraThreshold: Int,
    onSetZebraThreshold: (Int) -> Unit,
    isFocusPeakingEnabled: Boolean,
    focusPeakingSensitivity: Float,
    onSetFocusPeakingSensitivity: (Float) -> Unit,
    isFalseColorEnabled: Boolean,
    isLutEnabled: Boolean,
    allLuts: List<com.bragastudio.mobile.core.model.Lut>,
    activeLut: com.bragastudio.mobile.core.model.Lut?,
    onSelectLut: (String?) -> Unit,
    onNavigateToLuts: () -> Unit,
    currentAspectRatio: String,
    onSetAspectRatio: (String) -> Unit,
    onToggleScopes: () -> Unit,
    onToggleZebra: () -> Unit,
    onToggleFocusPeaking: () -> Unit,
    onToggleFalseColor: () -> Unit,
    onToggleLut: () -> Unit,
    modifier: Modifier = Modifier
) {
    var openDial by remember { mutableStateOf<String?>(null) }

    val zebraOptions = remember { (0..100 step 10).map { it.toString() } }
    val peakingOptions = remember { listOf("BAIXA", "MED", "ALTA") }
    val lutOptions = remember(allLuts) { listOf("Nenhum (Desativado)") + allLuts.map { it.displayName } }
    val aspectOptions = remember { listOf("OFF", "4:3", "16:9", "2.35:1", "1:1") }

    val zebraValueLabel = zebraThreshold.toString()
    val peakingValueLabel = when {
        focusPeakingSensitivity < 0.34f -> "BAIXA"
        focusPeakingSensitivity < 0.67f -> "MED"
        else -> "ALTA"
    }
    val lutValueLabel = activeLut?.displayName ?: "Nenhum (Desativado)"

    Box(modifier = modifier) {

        openDial?.let { param ->
            Box(modifier = Modifier.align(Alignment.CenterStart).offset(x = 58.dp)) {
                when (param) {
                    "zebra" -> CircularDialPopover(
                        title = "ZEBRA · LIMIAR",
                        currentValueLabel = zebraValueLabel,
                        options = zebraOptions,
                        isManual = true,
                        onSelect = { onSetZebraThreshold(it.toIntOrNull() ?: zebraThreshold) },
                        onDismiss = { openDial = null }
                    )
                    "peaking" -> CircularDialPopover(
                        title = "FOCUS PEAKING · SENS.",
                        currentValueLabel = peakingValueLabel,
                        options = peakingOptions,
                        isManual = true,
                        onSelect = { selected ->
                            val value = when (selected) {
                                "BAIXA" -> 0.15f
                                "ALTA" -> 0.85f
                                else -> 0.5f
                            }
                            onSetFocusPeakingSensitivity(value)
                        },
                        onDismiss = { openDial = null }
                    )
                    "lut" -> CircularDialPopover(
                        title = "LUT",
                        currentValueLabel = lutValueLabel,
                        options = lutOptions,
                        isManual = true,
                        onSelect = { selected ->
                            onSelectLut(if (selected == "Nenhum (Desativado)") null else allLuts.find { it.displayName == selected }?.id)
                        },
                        onDismiss = { openDial = null }
                    )
                    "aspect" -> CircularDialPopover(
                        title = "ASPECT RATIO",
                        currentValueLabel = currentAspectRatio,
                        options = aspectOptions,
                        isManual = true,
                        onSelect = { onSetAspectRatio(it) },
                        onDismiss = { openDial = null }
                    )
                }
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LensDialButton(
                icon = Icons.Filled.BarChart,
                label = "Scopes",
                valueBadge = null,
                isActive = isScopesVisible,
                enabled = true,
                onClick = onToggleScopes
            )
            LensDialButton(
                icon = Icons.Filled.Texture,
                label = "Zebra",
                valueBadge = if (isZebraEnabled) "$zebraThreshold%" else null,
                isActive = isZebraEnabled,
                enabled = true,
                onClick = {
                    onToggleZebra()
                    openDial = if (!isZebraEnabled) "zebra" else null
                }
            )
            LensDialButton(
                icon = Icons.Filled.CenterFocusStrong,
                label = "Peaking",
                valueBadge = if (isFocusPeakingEnabled) peakingValueLabel.take(3) else null,
                isActive = isFocusPeakingEnabled,
                enabled = true,
                onClick = {
                    onToggleFocusPeaking()
                    openDial = if (!isFocusPeakingEnabled) "peaking" else null
                }
            )
            LensDialButton(
                icon = Icons.Filled.InvertColors,
                label = "F. Color",
                valueBadge = null,
                isActive = isFalseColorEnabled,
                enabled = true,
                onClick = onToggleFalseColor
            )
            LensDialButton(
                icon = Icons.Filled.ColorLens,
                label = "LUT",
                valueBadge = if (isLutEnabled) "•" else null,
                isActive = isLutEnabled,
                enabled = true,
                onClick = { openDial = if (openDial == "lut") null else "lut" }
            )
            LensDialButton(
                icon = Icons.Filled.AspectRatio,
                label = "Aspect",
                valueBadge = if (currentAspectRatio != "OFF") currentAspectRatio else null,
                isActive = currentAspectRatio != "OFF",
                enabled = true,
                onClick = { openDial = if (openDial == "aspect") null else "aspect" }
            )
        }
    }
}
