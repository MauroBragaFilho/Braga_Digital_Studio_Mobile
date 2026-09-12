package com.bragastudio.mobile.featurepreview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.window.Popup
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
@Composable
private fun AdvancedToggleChip(
    label: String,
    enabled: Boolean,
    supported: Boolean,
    onClick: () -> Unit,
    compact: Boolean
) {
    if (!supported) return
    val bg = if (enabled) HudTheme.buttonActiveColor else HudTheme.buttonInactiveColor
    val fg = if (enabled) Color.White else Color.White.copy(alpha = 0.7f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = fg,
            fontWeight = FontWeight.Bold,
            fontSize = if (compact) 9.sp else 10.sp
        )
    }
}

/** Selo de qualidade da câmera ativa: tier de resolução + FPS máximo + recursos. */
@Composable
private fun CameraQualityBadge(camera: CameraInfoModel?, compact: Boolean) {
    if (camera == null) return
    val tier = when {
        (camera.maxResolution?.height ?: 0) >= 2160 -> "4K"
        (camera.maxResolution?.height ?: 0) >= 1440 -> "1440p"
        (camera.maxResolution?.height ?: 0) >= 1080 -> "1080p"
        else -> "${camera.maxResolution?.height ?: 0}p"
    }
    val feats = buildList {
        if (camera.hasOis) add("OIS")
        if (camera.hasEis) add("EIS")
        if (camera.supportsHdr) add("HDR")
        if (camera.hasTorch) add("TORCH")
    }
    val label = listOfNotNull(tier, "${camera.maxFps} FPS", feats.joinToString("+").takeIf { it.isNotEmpty() })
        .joinToString(" · ")
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(HudTheme.buttonActiveColor.copy(alpha = 0.85f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (compact) 9.sp else 10.sp
        )
    }
}

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

/**
 * Contorno sutil atrás de ícones "soltos" (sem fundo próprio, como os da
 * topbar/menu inferior que ficam direto sobre o preview) — um halo escuro
 * leve para garantir contraste caso a imagem por trás fique branca/estourada.
 * Ícones que já vivem dentro de um botão circular com fundo (LensDialButton,
 * REC etc.) não precisam disso, pois já têm contraste garantido pelo fundo.
 */
fun Modifier.drawIconOutline(): Modifier = this.drawBehind {
    drawCircle(
        color = Color.Black.copy(alpha = 0.35f),
        radius = size.minDimension * 0.62f
    )
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
 * no nome da lente. Em muitos aparelhos o nome/ID interno da câmera
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
    focusPeakingColor: String = "Red",
    onSetFocusPeakingColor: (String) -> Unit = {},
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
    // Controles avançados de captura (Camera2): lanterna (torch), estabilização
    // de vídeo (OIS/EIS) e HDR — toggles da UI (Blocos D). A disponibilidade de
    // cada recurso é derivada de currentLens (hasTorch/hasOis/hasEis/supportsHdr).
    isTorchEnabled: Boolean = false,
    onToggleTorch: () -> Unit = {},
    isStabilizationEnabled: Boolean = false,
    onToggleStabilization: () -> Unit = {},
    isHdrEnabled: Boolean = false,
    onToggleHdr: () -> Unit = {},
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

    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // --------------------------------------------------------------------
        // ADAPTAÇÃO RESPONSIVA (retrato/paisagem + tamanhos de tela variados)
        // --------------------------------------------------------------------
        // maxWidth/maxHeight aqui são os do Box que hospeda o HUD, então já
        // refletem a orientação atual do dispositivo em tempo real (a Activity
        // é fullSensor — ver AndroidManifest). Em vez de fixar dp absolutos,
        // calculamos alguns limiares:
        //  - isLandscape: largura > altura -> reflui a topbar e o menu inferior
        //    para caber sem cortar itens (ambos já usam horizontalScroll, então
        //    "caber" aqui significa reduzir o quanto de espaço vertical cada
        //    barra ocupa, já que em paisagem a altura é o recurso mais escasso).
        //  - isCompactWidth: telas estreitas (celulares pequenos, ex. ~360dp de
        //    largura em retrato) -> reduz fontes/ícones para não sobrepor.
        //  - isShortHeight: telas com pouca altura disponível (paisagem em
        //    celulares, ou retrato com teclado/HUD do sistema ocupando espaço)
        //    -> reduz o espaçamento vertical entre elementos empilhados
        //    (ex.: ToolsDialCluster na lateral) e ativa scroll vertical nele.
        // isLandscape e isTablet ainda não são consumidos por nenhum layout —
        // fazem parte do trabalho pendente de leftbar ancorada/responsiva
        // (ver notas de sessão). Mantidos calculados aqui, prontos para uso
        // quando essa parte for implementada, só suprimindo o warning por ora.
        @Suppress("UNUSED_VARIABLE") val isLandscape = maxWidth > maxHeight
        val isCompactWidth = maxWidth < 380.dp
        val isShortHeight = maxHeight < 400.dp
        @Suppress("UNUSED_VARIABLE") val isTablet = maxWidth > 600.dp && maxHeight > 600.dp
        val hudCompact = isCompactWidth || isShortHeight

        // Altura REAL (medida, não estimada) da topbar e do menu inferior —
        // usada pela leftbar para "encaixar" entre as duas sem cortar nem
        // sobrar espaço. Tentativas anteriores usavam valores fixos em dp
        // (chutados), que erravam em telas com proporções diferentes das
        // testadas. onGloballyPositioned mede o tamanho real renderizado de
        // cada barra a cada recomposição, então funciona em qualquer aparelho.
        val density = LocalDensity.current
        var topBarHeightPx by remember { mutableStateOf(0) }
        var bottomBarHeightPx by remember { mutableStateOf(0) }
        val topBarHeightDp = with(density) { topBarHeightPx.toDp() }
        val bottomBarHeightDp = with(density) { bottomBarHeightPx.toDp() }

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
                isTorchEnabled = isTorchEnabled,
                onToggleTorch = onToggleTorch,
                isStabilizationEnabled = isStabilizationEnabled,
                onToggleStabilization = onToggleStabilization,
                isHdrEnabled = isHdrEnabled,
                onToggleHdr = onToggleHdr,
                currentLens = currentLens,
                compact = hudCompact,
                modifier = Modifier.align(Alignment.TopCenter).onGloballyPositioned { topBarHeightPx = it.size.height }.background(if (isHudVisible) Color.Black.copy(alpha = 0.45f) else Color.Transparent).padding(top = if (hudCompact) HudTheme.spacingSmall else HudTheme.spacingMedium, bottom = if (hudCompact) HudTheme.spacingSmall else HudTheme.spacingMedium),
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
                    focusPeakingColor = focusPeakingColor,
                    onSetFocusPeakingColor = onSetFocusPeakingColor,
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
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = if (hudCompact) HudTheme.spacingSmall else HudTheme.spacingMedium)
                        // "Encaixa" a leftbar entre a topbar e o menu inferior usando
                        // a altura REAL medida de cada uma (topBarHeightDp/
                        // bottomBarHeightDp, via onGloballyPositioned) em vez de
                        // estimativas fixas em dp. BUG CORRIGIDO: as estimativas
                        // anteriores (chutadas) erravam em telas com proporção
                        // diferente das testadas — a barra inferior, por exemplo,
                        // varia de altura conforme mostra ou não a trilha de scroll
                        // e os controles manuais, então um valor fixo nunca ia bater
                        // certo em todos os casos. Agora não há mais chute: some as
                        // duas alturas reais e usa isso como respiro.
                        .fillMaxHeight()
                        .padding(
                            top = topBarHeightDp,
                            bottom = bottomBarHeightDp
                        )
                        // Sem teto artificial (0.8x etc.) agora que o respiro já é
                        // medido de verdade — o espaço disponível já É exatamente
                        // a área entre as barras. verticalScroll continua como rede
                        // de segurança apenas para o caso extremo de uma tela muito
                        // baixa (paisagem em celular) onde nem isso baste.
                        .verticalScroll(rememberScrollState()),
                    compact = hudCompact
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
            showManualControls = isModernUiEnabled,
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
            compact = hudCompact,
            videoSource = videoSettings.videoSource,
            modifier = Modifier.align(Alignment.BottomCenter).onGloballyPositioned { bottomBarHeightPx = it.size.height }.background(if (isHudVisible) Color.Black.copy(alpha = 0.35f) else Color.Transparent).padding(vertical = if (hudCompact) 2.dp else 4.dp)
        )

        // NOTA: o cluster de controles manuais (ISO/Obturador/WB/Foco/Íris) foi
        // movido para dentro do menu inferior (BottomInfoProfessional), junto do
        // botão REC único — antes havia um segundo círculo de REC flutuando aqui
        // perto da lente, duplicando o botão já existente em RightControlsProfessional.
        // Esse duplicado foi removido; o cluster agora só entrega os 5 controles
        // manuais e é chamado de dentro de BottomInfoProfessional.

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

        androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

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
@Suppress("UNUSED_PARAMETER") // onSetZoom: mantido na assinatura para simetria com os demais controles com dial; ainda não conectado
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

        // Chip com o valor atual de zoom digital (pixel a pixel). Os atalhos
        // fixos "1x"/"2x" que existiam aqui foram escondidos a pedido — essa
        // troca de nível já é feita pelo seletor de lente física (rail da
        // câmera) ou por pinça na tela; manter os dois ao mesmo tempo perto
        // da lente duplicava a função e ocupava espaço.
        if (zoomFactor > 1.01f) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = String.format("%.1fx", zoomFactor),
                    color = HudTheme.buttonActiveColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
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
@Suppress("UNUSED_PARAMETER") // recordingTime/isRecording/fps: não exibidos aqui, essa info já é mostrada em outro trecho do HUD; mantidos por ora
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
    modifier: Modifier = Modifier,
    // Controles manuais de câmera (ISO/Obturador/WB/Foco/Íris), integrados ao
    // menu inferior quando a nova interface está ativa. O botão REC não faz
    // mais parte deste cluster — o único REC do app é o de RightControlsProfessional,
    // evitando o botão duplicado que existia antes.
    showManualControls: Boolean = false,
    isSonyActive: Boolean = false,
    sonyTelemetry: com.braga.bdsm.network.sony.SonyCameraStatus = com.braga.bdsm.network.sony.SonyCameraStatus(),
    onSetIso: (Int?) -> Unit = {},
    onSetShutter: (Long?) -> Unit = {},
    onSonySetAperture: (String) -> Unit = {},
    nativeCurrentIso: Int? = null,
    nativeCurrentShutterNanos: Long? = null,
    nativeCurrentWbMode: Int? = null,
    nativeCurrentFocusDiopters: Float? = null,
    onSetNativeWb: (Int?) -> Unit = {},
    onSetNativeFocus: (Float?) -> Unit = {},
    compact: Boolean = false,
    // Fonte de vídeo atual ("Camera", "USB", "SONY") — usada para: (1) ocultar
    // o cluster inteiro de controles manuais quando a fonte é USB (não faz
    // sentido mostrar ISO/Obturador/WB/Foco/Íris para uma capturadora
    // genérica, que não expõe nenhum desses parâmetros); (2) ocultar o botão
    // de Íris quando a fonte é a câmera do próprio celular (sem abertura
    // física variável) — antes ele só ficava desabilitado/apagado, agora
    // desaparece de fato.
    videoSource: String = ""
) {
    var showGridMenu by remember { mutableStateOf(false) }
    // ScrollState nomeado (em vez de criado inline dentro de horizontalScroll())
    // para poder ler seu progresso e desenhar a trilha de rolagem visual acima
    // da linha de botões.
    val manualControlsScroll = rememberScrollState()
    val showManualControlsRow = showManualControls && isHudVisible && videoSource != "USB"

    Column(modifier = modifier.fillMaxWidth()) {

        // Trilha de rolagem horizontal, fina, acima da linha de botões — só
        // aparece quando o cluster de controles manuais está visível.
        if (showManualControlsRow) {
            val scrollProgress = if (manualControlsScroll.maxValue > 0) {
                manualControlsScroll.value.toFloat() / manualControlsScroll.maxValue.toFloat()
            } else 0f
            val trackWidth = 100.dp
            val thumbWidth = 30.dp
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 3.dp)
                    .width(trackWidth)
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.15f))
            ) {
                Box(
                    modifier = Modifier
                        .offset(x = (trackWidth - thumbWidth) * scrollProgress)
                        .width(thumbWidth)
                        .height(2.5.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(ModernHudTheme.accent.copy(alpha = 0.8f))
                )
            }
        }

        // Tudo em UMA linha só: Grid | controles manuais (rolável, no meio) | VU.
        // Antes eram duas linhas empilhadas (status + controles manuais), o
        // que deixava a barra bem mais grossa do que precisava.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = HudTheme.spacingMedium, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // GUIA -> GRID
            if (isHudVisible) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 14.dp).pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onToggleGrid() },
                            onLongPress = { showGridMenu = true }
                        )
                    }.padding(4.dp)
                ) {
                    Icon(Icons.Filled.GridOn, "Grid", tint = if (currentGrid != "OFF") HudTheme.buttonActiveColor else Color.White, modifier = Modifier.size(if (compact) 14.dp else 18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (currentGrid != "OFF") currentGrid else "GRID", color = if (currentGrid != "OFF") HudTheme.buttonActiveColor else Color.White, fontWeight = FontWeight.Bold, fontSize = if (compact) 11.sp else 13.sp)
                }
            } else {
                Spacer(modifier = Modifier.width(48.dp))
            }

            // Controles manuais no meio, ocupando o espaço restante e rolando
            // horizontalmente por dentro — nunca empurra Grid/VU para fora.
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (showManualControlsRow) {
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
                        modifier = Modifier.horizontalScroll(manualControlsScroll)
                    )
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
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.55f)) // mais transparente, a pedido
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            modifier = Modifier.width(100.dp).height(6.dp)
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
                androidx.compose.material3.HorizontalDivider()
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
@Suppress("UNUSED_PARAMETER") // onCycleAudioDevice: troca de fonte de áudio ainda não tem gatilho no topbar minimal; mantido para quando for adicionado
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
    compact: Boolean = false,
    // Timecode + indicador de REC movidos para a topbar (antes ficavam no
    // menu inferior, ver BottomInfoProfessional) — mais perto do que o
    // operador olha primeiro para conferir se está gravando.
    recordingTime: Long = 0L,
    // Controles avançados (Camera2) exibidos como chips na topbar minimalista.
    isTorchEnabled: Boolean = false,
    onToggleTorch: () -> Unit = {},
    isStabilizationEnabled: Boolean = false,
    onToggleStabilization: () -> Unit = {},
    isHdrEnabled: Boolean = false,
    onToggleHdr: () -> Unit = {},
    currentLens: com.bragastudio.mobile.corecapture.domain.CameraInfoModel? = null,
    isRecording: Boolean = false,
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
    // Escala de fonte/ícone/espaçamento: reduz ~15-20% em telas compactas
    // (largura estreita em retrato ou pouca altura em paisagem) para nunca
    // cortar ou sobrepor itens, mantendo tudo legível.
    val fontScale = if (compact) 0.85f else 1f
    val hGap = if (compact) 6.dp else 10.dp
    val ndiFontSize = (12 * fontScale).sp
    val statFontSize = (13 * fontScale).sp

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = if (compact) HudTheme.spacingMedium else HudTheme.spacingLarge),
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
                Text("NDI", color = if (isNdiEnabled) HudTheme.ndiActiveColor else Color.Gray, fontWeight = FontWeight.Bold, fontSize = ndiFontSize)
            }

            if (isHudVisible) {
                Spacer(modifier = Modifier.width(hGap))
                Box(modifier = Modifier.width(1.dp).height(18.dp).background(Color.Gray.copy(alpha = 0.4f)))
                Spacer(modifier = Modifier.width(hGap))

                // Cada item agora abre um DropdownMenu real ao segurar (long-press),
                // igual ao comportamento do menu anterior (TopBarSettingItem) — só
                // que sem o rótulo de texto fixo acima do valor. Resolução e FPS
                // agora são dois controles independentes: FPS pode ser ajustado
                // direto pelo preview, sem precisar mexer na resolução.
                MinimalSettingItem(
                    value = videoSettings.resolution,
                    options = listOf("1080p", "1440p", "4K"),
                    onClick = onCycleResolution,
                    onSelect = onSetResolution,
                    fontSize = statFontSize
                )
                Spacer(modifier = Modifier.width(hGap))
                MinimalSettingItem(
                    value = "${fps}fps",
                    options = listOf("24", "30", "60"),
                    onClick = onCycleFps,
                    onSelect = { onSetFps(it.toIntOrNull() ?: 30) },
                    fontSize = statFontSize
                )
                Spacer(modifier = Modifier.width(hGap))
                MinimalSettingItem(
                    value = "${videoSettings.bitrateMbps} Mb/s",
                    options = listOf("25", "50", "100"),
                    onClick = onCycleBitrate,
                    onSelect = { onSetBitrate(it.toIntOrNull() ?: 50) },
                    fontSize = statFontSize
                )
                Spacer(modifier = Modifier.width(hGap))
                MinimalSettingItem(
                    value = videoSettings.codec,
                    options = listOf("H.264", "H.265"),
                    onClick = onCycleCodec,
                    onSelect = onSetCodec,
                    fontSize = statFontSize
                )
                Spacer(modifier = Modifier.width(hGap))
                MinimalSettingItem(
                    value = videoSettings.videoSource,
                    options = listOf("Camera", "USB", "SONY"),
                    onClick = onToggleCameraSource,
                    onSelect = onSetCameraSource,
                    fontSize = statFontSize,
                    icon = Icons.Filled.Videocam
                )
                Spacer(modifier = Modifier.width(hGap))
                // Seletor de microfone — os dados (availableAudioDevices,
                // selectedAudioDeviceName, onSelectAudioDevice) já chegavam como
                // parâmetro nesta função, só não havia nenhum item visual para
                // eles. Usa o mesmo ícone de "entrada" do seletor de câmera
                // (Videocam para vídeo, Mic para áudio — ambos representam uma
                // fonte de entrada, mesma linguagem visual).
                MinimalSettingItem(
                    value = selectedAudioDeviceName.take(10).ifBlank { "Mic" },
                    options = availableAudioDevices.map { it.productName?.toString() ?: "Mic" }.ifEmpty { listOf("Padrão") },
                    onClick = { /* sem ação de "ciclar" — toque também abre a lista, já que normalmente há poucos microfones */ },
                    onSelect = { name ->
                        availableAudioDevices.find { (it.productName?.toString() ?: "Mic") == name }
                            ?.let { onSelectAudioDevice(it) }
                    },
                    fontSize = statFontSize,
                    icon = Icons.Filled.Mic,
                    openOnTapToo = true
                )
                // ---- Controles avançados de captura (Camera2) ----
                CameraQualityBadge(
                    camera = currentLens,
                    compact = compact
                )
                Spacer(modifier = Modifier.width(hGap))
                AdvancedToggleChip(
                    label = "TORCH",
                    enabled = isTorchEnabled,
                    supported = currentLens?.hasTorch == true,
                    onClick = onToggleTorch,
                    compact = compact
                )
                Spacer(modifier = Modifier.width(hGap))
                AdvancedToggleChip(
                    label = "EIS",
                    enabled = isStabilizationEnabled,
                    supported = currentLens?.hasOis == true || currentLens?.hasEis == true,
                    onClick = onToggleStabilization,
                    compact = compact
                )
                Spacer(modifier = Modifier.width(hGap))
                AdvancedToggleChip(
                    label = "HDR",
                    enabled = isHdrEnabled,
                    supported = currentLens?.supportsHdr == true,
                    onClick = onToggleHdr,
                    compact = compact
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(hGap)) {
            Icon(
                if (metrics.isWifiConnected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                "WIFI",
                tint = if (metrics.isWifiConnected) Color.White else Color.Red,
                modifier = Modifier.drawIconOutline().size(if (compact) 12.dp else 14.dp)
            )
            Box(modifier = Modifier.width(1.dp).height(18.dp).background(Color.Gray.copy(alpha = 0.4f)))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${batteryPercentage}%", color = if (batteryPercentage > 20) Color.White else Color.Red, fontWeight = FontWeight.Bold, fontSize = statFontSize)
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
            Text(remainingLabel, color = if (storageFreeGB > 5f) Color.White else Color.Red, fontWeight = FontWeight.Bold, fontSize = statFontSize)

            if (isHudVisible) {
                Box(modifier = Modifier.width(1.dp).height(18.dp).background(Color.Gray.copy(alpha = 0.4f)))

                // Timecode + REC — movidos para cá a pedido, ficando junto do
                // resto dos indicadores de status, sempre visíveis independente
                // de qual barra inferior estiver com scroll aberto.
                Text(
                    text = formatTimecode(recordingTime, fps),
                    color = Color.White,
                    fontSize = statFontSize,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isRecording) Color.Red else Color.Black.copy(alpha = 0.4f))
                        .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!isRecording) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color.Gray))
                        }
                        Text("REC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (compact) 9.sp else 10.sp)
                    }
                }
            }

            if (isHudVisible) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.Filled.Home,
                    contentDescription = "Voltar ao menu",
                    tint = Color.White,
                    modifier = Modifier
                        .drawIconOutline()
                        .size(HudTheme.iconSizeMedium).clickable { onNavigateHome() }
                )
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Settings",
                    tint = Color.White,
                    modifier = Modifier
                        .drawIconOutline()
                        .size(HudTheme.iconSizeMedium).clickable { onNavigateToSettings() }
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
 * Item de configuração da topbar minimalista: mostra só o valor (sem rótulo
 * de texto acima, ao contrário do TopBarSettingItem clássico), mas mantém
 * exatamente a mesma interação — tap cicla para o próximo valor, long-press
 * abre a lista completa de opções em um DropdownMenu — igual ao menu anterior.
 */
@Composable
private fun MinimalSettingItem(
    value: String,
    options: List<String>,
    onClick: () -> Unit,
    onSelect: (String) -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    icon: ImageVector? = null,
    openOnTapToo: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (icon != null) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.drawIconOutline().size(12.dp))
            }
            Text(
                text = value,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = fontSize,
                modifier = Modifier.pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onClick(); if (openOnTapToo) expanded = true },
                        onLongPress = { expanded = true }
                    )
                }
            )
        }
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

    Box(modifier = modifier) {

        // Dial abre em um Popup (janela separada, fora do fluxo de layout) —
        // antes usava um Box com offset() dentro do próprio cluster, e o
        // Compose contava o tamanho do dial (~200dp) no cálculo de altura
        // desse Box mesmo com o offset, o que fazia a barra inferior "crescer
        // até metade da tela" ao abrir qualquer controle. Popup resolve isso:
        // desenha por cima de tudo, sem influenciar o layout de quem o chamou.
        openDial?.let { param ->
            Popup(
                alignment = Alignment.BottomCenter,
                offset = androidx.compose.ui.unit.IntOffset(0, -220),
                onDismissRequest = { openDial = null }
            ) {
                HorizontalDialPopover(
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
                        "shutter" -> listOf("AUTO", "1/1000", "1/500", "1/250", "1/125", "1/60", "1/30", "1/15", "1\"")
                        // "AUTO" adicionado — Sony também tem íris automática,
                        // e agora arrastar totalmente para a esquerda = Automático,
                        // então a primeira opção da lista precisa ser ela.
                        "iris" -> listOf("AUTO", "1.8", "2.8", "3.5", "4.0", "5.6", "8.0", "11", "16", "22")
                        "wb" -> listOf("AUTO", "2700K", "3200K", "4000K", "5000K", "5600K", "6500K", "7500K")
                        "focus" -> listOf("AUTO", "∞", "5m", "2m", "1m", "0.5m", "0.3m", "0.1m")
                        else -> emptyList()
                    },
                    isManual = param != "iris" || isSonyActive,
                    onSelect = { selected ->
                        when (param) {
                            "iso" -> {
                                if (isSonyActive) onSetIso(if (selected == "AUTO") null else selected.toIntOrNull())
                                else onSetIso(if (selected == "AUTO") null else selected.toIntOrNull())
                            }
                            "shutter" -> onSetShutter(shutterLabelToNanosLocal(selected))
                            "iris" -> onSonySetAperture(selected)
                            "wb" -> onSetNativeWb(wbLabelToMode(selected))
                            "focus" -> onSetNativeFocus(focusLabelToDiopter(selected))
                        }
                    },
                    onDismiss = { openDial = null }
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Íris: abertura física só existe na Sony. Antes ficava aqui
            // desabilitada/apagada quando a fonte era a câmera do celular;
            // agora some de fato (o Row simplesmente não a inclui), já que
            // não há nenhum controle real por trás dela nesse caso.
            if (isSonyActive) {
                LensDialButton(
                    icon = Icons.Filled.Circle,
                    label = "Íris",
                    valueBadge = apertureValue,
                    isActive = openDial == "iris",
                    enabled = true,
                    onClick = { openDial = if (openDial == "iris") null else "iris" },
                    compact = true
                )
            }
            LensDialButton(
                icon = Icons.Filled.Iso,
                label = "ISO",
                valueBadge = isoValue.takeIf { it != "AUTO" },
                isActive = openDial == "iso",
                enabled = true,
                onClick = { openDial = if (openDial == "iso") null else "iso" },
                compact = true
            )
            LensDialButton(
                icon = Icons.Filled.ShutterSpeed,
                label = "Obtur.",
                valueBadge = shutterValue.takeIf { it != "AUTO" },
                isActive = openDial == "shutter",
                enabled = true,
                onClick = { openDial = if (openDial == "shutter") null else "shutter" },
                compact = true
            )
            LensDialButton(
                icon = Icons.Filled.WbSunny,
                label = "WB",
                valueBadge = wbValue.takeIf { it != "AUTO" },
                isActive = openDial == "wb",
                enabled = !isSonyActive, // WB manual hoje só está implementado no pipeline nativo (Camera2)
                onClick = { openDial = if (openDial == "wb") null else "wb" },
                compact = true
            )
            LensDialButton(
                icon = Icons.Filled.CenterFocusWeak,
                label = "Foco",
                valueBadge = focusValue.takeIf { it != "AUTO" },
                isActive = openDial == "focus",
                enabled = !isSonyActive, // idem: foco manual por diopter é do Camera2Device
                onClick = { openDial = if (openDial == "focus") null else "focus" },
                compact = true
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
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    compact: Boolean = false
) {
    val size = if (compact) 34.dp else 40.dp
    val iconSize = if (compact) 13.dp else 15.dp
    val fontSize = if (compact) 6.sp else 6.5.sp
    // BUG CORRIGIDO: antes a chave do pointerInput era (onClick, onLongClick).
    // Como essas lambdas são recriadas a cada recomposição (e esta tela
    // recompõe constantemente por causa do VU de áudio em tempo real), o
    // Compose reiniciava o detector de gestos várias vezes por segundo —
    // interrompendo o toque no meio do caminho quase sempre. Resultado: os
    // botões pareciam "desativados". Agora a chave é Unit (nunca muda, o
    // detector só é criado uma vez) e os callbacks são lidos via
    // rememberUpdatedState, então sempre chamam a versão mais recente sem
    // precisar recriar o gesto.
    val currentOnClick = rememberUpdatedState(onClick)
    val currentOnLongClick = rememberUpdatedState(onLongClick)
    Box {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (isActive) ModernHudTheme.accent.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.4f))
                .border(1.dp, if (isActive) ModernHudTheme.accent else Color.White.copy(alpha = 0.14f), CircleShape)
                .alpha(if (enabled) 1f else 0.4f)
                .then(
                    if (enabled) Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { currentOnClick.value() },
                            onLongPress = { currentOnLongClick.value?.invoke() }
                        )
                    } else Modifier
                ),
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, label, tint = if (isActive) ModernHudTheme.accent else Color.White, modifier = Modifier.size(iconSize))
            Text(label, color = if (isActive) ModernHudTheme.accent else Color.Gray, fontSize = fontSize, fontWeight = FontWeight.Bold)
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
                Text(valueBadge, color = Color.Black, fontSize = 7.sp, fontWeight = FontWeight.Bold)
            }
        }
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
 * Lista rolável de LUTs (não é dial circular — nomes variam muito de tamanho
 * e a leitura em lista é mais rápida para escolher entre várias opções do que
 * girar um anel). "Nenhum (Desativado)" não aparece aqui: o toque no próprio
 * botão de LUT (fora deste painel) já liga/desliga o LUT ativo.
 */
@Composable
@Suppress("UNUSED_PARAMETER") // onDismiss: dismiss real é feito via Popup(onDismissRequest) no chamador; parametro mantido por compatibilidade
fun LutListPopover(
    luts: List<com.bragastudio.mobile.core.model.Lut>,
    activeLutId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(220.dp)
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ModernHudTheme.panelBg)
            .border(1.dp, ModernHudTheme.panelBorder, RoundedCornerShape(18.dp))
            .padding(vertical = 12.dp),
    ) {
        Text(
            "LUT",
            color = Color.Gray,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            luts.forEach { lut ->
                val isSelected = lut.id == activeLutId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(lut.id) }
                        .background(if (isSelected) ModernHudTheme.accent.copy(alpha = 0.14f) else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        lut.displayName,
                        color = if (isSelected) ModernHudTheme.accent else Color.White,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        Icon(Icons.Filled.Check, null, tint = ModernHudTheme.accent, modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (luts.isEmpty()) {
                Text(
                    "Nenhum LUT importado",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

/**
 * Lista rolável genérica de opções em texto simples — mesmo visual/estrutura
 * da LutListPopover, mas sem depender do tipo `Lut`. Usada pelo Aspect Ratio
 * e reutilizável para qualquer outro controle futuro que faça mais sentido
 * como lista do que como dial circular.
 */
@Composable
@Suppress("UNUSED_PARAMETER") // onDismiss: dismiss real é feito via Popup(onDismissRequest) no chamador; parametro mantido por compatibilidade
fun SimpleListPopover(
    title: String,
    options: List<String>,
    activeOption: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(200.dp)
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ModernHudTheme.panelBg)
            .border(1.dp, ModernHudTheme.panelBorder, RoundedCornerShape(18.dp))
            .padding(vertical = 12.dp),
    ) {
        Text(
            title,
            color = Color.Gray,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            options.forEach { option ->
                val isSelected = option == activeOption
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option) }
                        .background(if (isSelected) ModernHudTheme.accent.copy(alpha = 0.14f) else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        option,
                        color = if (isSelected) ModernHudTheme.accent else Color.White,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (isSelected) {
                        Icon(Icons.Filled.Check, null, tint = ModernHudTheme.accent, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/**
 * Painel combinado do Focus Peaking, aberto ao segurar o botão de Peaking:
 * antes só a sensibilidade tinha um controle exposto; a cor já existia
 * persistida (MonitorSettings.focusPeakingColor) mas sem nenhuma UI.
 */
@Composable
@Suppress("UNUSED_PARAMETER") // onDismiss: dismiss real é feito via Popup(onDismissRequest) no chamador; parametro mantido por compatibilidade
fun FocusPeakingPanel(
    sensitivityValueLabel: String,
    sensitivityOptions: List<String>,
    onSelectSensitivity: (String) -> Unit,
    currentColor: String,
    onSelectColor: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorOptions = listOf(
        "Red" to Color(0xFFFF3B30),
        "Green" to Color(0xFF34C759),
        "Blue" to Color(0xFF0A84FF),
        "Yellow" to Color(0xFFFFD60A),
        "White" to Color.White
    )

    Column(
        modifier = modifier
            .width(210.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(ModernHudTheme.panelBg)
            .border(1.dp, ModernHudTheme.panelBorder, RoundedCornerShape(18.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("FOCUS PEAKING", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)

        // Botão de cor: cada swatch é clicável direto (é uma escolha entre
        // poucas opções fixas, não precisa de dial circular aqui).
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("COR", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                colorOptions.forEach { (name, swatch) ->
                    val isSelected = currentColor.equals(name, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(swatch)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) ModernHudTheme.accent else Color.White.copy(alpha = 0.3f),
                                shape = CircleShape
                            )
                            .clickable { onSelectColor(name) }
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))

        // Sensibilidade: mesmo miolo de dial circular usado em todo o resto do
        // app (CircularDialContent, compartilhado com CircularDialPopover).
        CircularDialContent(
            title = "SENSIBILIDADE",
            currentValueLabel = sensitivityValueLabel,
            options = sensitivityOptions,
            isManual = true,
            onSelect = onSelectSensitivity
        )
    }
}

/**
 * Miolo compartilhado de todo dial circular do app: anel arrastável (gesto
 * radial), valor grande no centro, título embaixo. Usado tanto standalone
 * (dentro de CircularDialPopover, com moldura própria) quanto embutido em
 * painéis compostos (como FocusPeakingPanel, que soma cor + sensibilidade
 * numa mesma moldura).
 */
@Composable
fun CircularDialContent(
    title: String,
    currentValueLabel: String,
    options: List<String>,
    isManual: Boolean,
    onSelect: (String) -> Unit
) {
    val selectedIndex = remember(currentValueLabel, options) {
        options.indexOf(currentValueLabel).coerceAtLeast(0)
    }
    // BUG CORRIGIDO: a chave era só `options` (uma lista fixa que nunca muda
    // entre recomposições), então `dragIndex` ficava preso no valor inicial e
    // só era atualizado por arraste local — nunca resincronizava com o valor
    // real vindo de fora (ex.: sensibilidade mudada e persistida, mas o dial
    // continuava mostrando o valor antigo até fechar/reabrir o popover, que
    // recriava o composable do zero). Agora a chave é a mesma de
    // `selectedIndex` (currentValueLabel + options), então qualquer mudança
    // externa do valor atual resincroniza o dial imediatamente.
    var dragIndex by remember(currentValueLabel, options) { mutableStateOf(selectedIndex) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(132.dp)
                .pointerInput(options, isManual) {
                    if (!isManual || options.isEmpty()) return@pointerInput
                    detectDragGestures { change, _ ->
                        change.consume()
                        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                        val pos = change.position
                        val angle = (Math.toDegrees(
                            kotlin.math.atan2((pos.y - center.y).toDouble(), (pos.x - center.x).toDouble())
                        ) + 360.0) % 360.0
                        // O anel é desenhado de startAngle=150° a 150°+240°=390°(=30°),
                        // no mesmo sentido horário que atan2 já produz aqui. A versão
                        // anterior usava uma fórmula sem relação com esse arco
                        // (+90 / 300°), fazendo o valor mudar num ângulo bem diferente
                        // de onde o dedo estava — daí a sensação de "precisar arrastar
                        // em outro ponto da tela". Agora o ângulo é relativo ao início
                        // real do arco (150°) e normalizado pela abertura real (240°).
                        val relativeAngle = (angle - 150.0 + 360.0) % 360.0
                        val normalized = (relativeAngle / 240.0).coerceIn(0.0, 1.0)
                        val idx = (normalized * (options.size - 1) + 0.5).toInt().coerceIn(0, options.size - 1)
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
    }
}

/**
 * Popover com controle deslizante HORIZONTAL — estilo régua, como em câmeras
 * de cinema reais (Blackmagic/RED/ARRI) para ISO, obturador, WB e foco. Usado
 * especificamente pelos controles manuais de exposição (LensControlCluster);
 * o resto da interface (Zebra, Peaking, LUT, Aspect Ratio na leftbar) continua
 * usando o dial circular (CircularDialPopover), que combina melhor com toggles
 * simples de poucas opções.
 *
 * Arrastar totalmente para a esquerda sempre cai na primeira opção da lista —
 * por convenção, todo `options` passado aqui deve começar com "AUTO", então
 * "soltar tudo à esquerda" = voltar ao automático, como pedido.
 */
@Composable
@Suppress("UNUSED_PARAMETER") // onDismiss: dismiss real é feito via Popup(onDismissRequest) no chamador; parametro mantido por compatibilidade
fun HorizontalDialPopover(
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
    // Mesma correção aplicada ao dial circular: chave inclui currentValueLabel,
    // não só options, para nunca ficar com um valor "preso" de uma sessão
    // anterior.
    var dragIndex by remember(currentValueLabel, options) { mutableStateOf(selectedIndex) }
    val isAuto = options.getOrNull(dragIndex) == "AUTO"

    Column(
        modifier = modifier
            .width(280.dp)
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
                when {
                    !isManual -> "INDISPONÍVEL"
                    isAuto -> "AUTOMÁTICO"
                    else -> "MANUAL"
                },
                color = if (isManual && !isAuto) ModernHudTheme.accent else Color.Gray,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = options.getOrElse(dragIndex) { currentValueLabel },
            color = if (isAuto) Color.Gray else Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        // Régua horizontal: trilha + marcações de cada opção + indicador
        // preenchido do início (esquerda) até a posição atual.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .pointerInput(options, isManual) {
                    if (!isManual || options.size < 2) return@pointerInput
                    detectDragGestures { change, _ ->
                        change.consume()
                        val fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val idx = (fraction * (options.size - 1) + 0.5f).toInt().coerceIn(0, options.size - 1)
                        dragIndex = idx
                        onSelect(options[idx])
                    }
                },
            contentAlignment = Alignment.CenterStart
        ) {
            val fillFraction = if (options.size > 1) dragIndex / (options.size - 1).toFloat() else 0f

            // Trilha de fundo
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.15f))
            )
            // Preenchimento do início até a posição atual
            Box(
                modifier = Modifier
                    .fillMaxWidth(fillFraction)
                    .height(4.dp)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (isAuto) Color.Gray else ModernHudTheme.accent)
            )
            // Marcações de cada opção
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                options.forEachIndexed { idx, _ ->
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height(if (idx == 0) 14.dp else 8.dp) // marca do AUTO um pouco maior, para se destacar
                            .background(Color.White.copy(alpha = if (idx <= dragIndex) 0.5f else 0.2f))
                    )
                }
            }
            // Manípulo (thumb)
            Box(
                modifier = Modifier
                    .fillMaxWidth(fillFraction)
                    .align(Alignment.CenterStart),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(3.dp, if (isAuto) Color.Gray else ModernHudTheme.accent, CircleShape)
                )
            }
        }

        Text(
            "Arraste — solte totalmente à esquerda para Automático",
            color = Color.Gray,
            fontSize = 8.5.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * Popover com dial circular (anel arrastável) para ajustar um parâmetro de
 * exposição/foco por gesto radial — usado para todos os ajustes manuais na
 * nova interface (ISO, obturador, íris, WB, foco, zebra, LUT, aspect ratio).
 * A moldura (título + badge Manual/Indisponível + dica de uso) fica aqui;
 * o miolo do dial em si é o CircularDialContent compartilhado acima.
 */
@Composable
@Suppress("UNUSED_PARAMETER") // onDismiss: dismiss real é feito via Popup(onDismissRequest) no chamador; parametro mantido por compatibilidade
fun CircularDialPopover(
    title: String,
    currentValueLabel: String,
    options: List<String>,
    isManual: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
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

        CircularDialContent(
            title = title,
            currentValueLabel = currentValueLabel,
            options = options,
            isManual = isManual,
            onSelect = onSelect
        )

        Text(
            "Arraste ao redor do anel para ajustar",
            color = Color.Gray,
            fontSize = 8.5.sp,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
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
// ATENÇÃO: onNavigateToLuts é recebido mas não tem nenhum gatilho de UI dentro
// deste composable (ex.: um long-press no botão de LUT abrindo a LutsScreen).
// Parece uma feature que ficou pela metade — sinalizar para decidir se deve
// ser conectada ou removida da assinatura.
@Suppress("UNUSED_PARAMETER")
fun ToolsDialCluster(
    isScopesVisible: Boolean,
    isZebraEnabled: Boolean,
    zebraThreshold: Int,
    onSetZebraThreshold: (Int) -> Unit,
    isFocusPeakingEnabled: Boolean,
    focusPeakingSensitivity: Float,
    onSetFocusPeakingSensitivity: (Float) -> Unit,
    focusPeakingColor: String,
    onSetFocusPeakingColor: (String) -> Unit,
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
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    var openDial by remember { mutableStateOf<String?>(null) }

    val zebraOptions = remember { (0..100 step 10).map { it.toString() } }
    val peakingOptions = remember { listOf("BAIXA", "MED", "ALTA") }
    // "Nenhum (Desativado)" fica de fora da lista — o toque no próprio botão
    // de LUT já liga/desliga; a lista serve só para ESCOLHER qual LUT usar.
    val aspectOptions = remember { listOf("OFF", "4:3", "16:9", "2.35:1", "1:1") }

    val zebraValueLabel = zebraThreshold.toString()
    val peakingValueLabel = when {
        focusPeakingSensitivity < 0.34f -> "BAIXA"
        focusPeakingSensitivity < 0.67f -> "MED"
        else -> "ALTA"
    }
    val lutValueLabel = activeLut?.displayName ?: "Nenhum (Desativado)" // usado no badge do botão LUT

    Box(modifier = modifier) {

        openDial?.let { param ->
            Popup(
                alignment = Alignment.CenterStart,
                offset = androidx.compose.ui.unit.IntOffset(160, 0),
                onDismissRequest = { openDial = null }
            ) {
                when (param) {
                    "zebra" -> CircularDialPopover(
                        title = "ZEBRA · LIMIAR",
                        currentValueLabel = zebraValueLabel,
                        options = zebraOptions,
                        isManual = true,
                        onSelect = { onSetZebraThreshold(it.toIntOrNull() ?: zebraThreshold) },
                        onDismiss = { openDial = null }
                    )
                    "peaking" -> FocusPeakingPanel(
                        sensitivityValueLabel = peakingValueLabel,
                        sensitivityOptions = peakingOptions,
                        onSelectSensitivity = { selected ->
                            val value = when (selected) {
                                "BAIXA" -> 0.15f
                                "ALTA" -> 0.85f
                                else -> 0.5f
                            }
                            onSetFocusPeakingSensitivity(value)
                        },
                        currentColor = focusPeakingColor,
                        onSelectColor = onSetFocusPeakingColor,
                        onDismiss = { openDial = null }
                    )
                    "lut" -> LutListPopover(
                        luts = allLuts,
                        activeLutId = activeLut?.id,
                        onSelect = { lutId -> onSelectLut(lutId) },
                        onDismiss = { openDial = null }
                    )
                    "aspect" -> SimpleListPopover(
                        title = "ASPECT RATIO",
                        options = aspectOptions,
                        activeOption = currentAspectRatio,
                        onSelect = { onSetAspectRatio(it) },
                        onDismiss = { openDial = null }
                    )
                }
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Scopes: só liga/desliga, sem parâmetro — sem mudança de interação.
            LensDialButton(
                icon = Icons.Filled.BarChart,
                label = "Scopes",
                valueBadge = null,
                isActive = isScopesVisible,
                enabled = true,
                onClick = onToggleScopes,
                compact = compact
            )
            // Zebra: TAP liga/desliga (não abre mais o dial sozinho); SEGURAR
            // abre a lista/dial de limiar (sensibilidade). Antes o tap fazia
            // as duas coisas ao mesmo tempo (ligar E abrir o dial).
            LensDialButton(
                icon = Icons.Filled.Texture,
                label = "Zebra",
                valueBadge = if (isZebraEnabled) "$zebraThreshold%" else null,
                isActive = isZebraEnabled,
                enabled = true,
                onClick = { onToggleZebra() },
                onLongClick = { openDial = "zebra" },
                compact = compact
            )
            // Focus Peaking: TAP liga/desliga; SEGURAR abre o painel com cor +
            // sensibilidade (ver "peaking" no Popup acima, que agora inclui a
            // escolha de cor além do slider de sensibilidade).
            LensDialButton(
                icon = Icons.Filled.CenterFocusStrong,
                label = "Peaking",
                valueBadge = if (isFocusPeakingEnabled) peakingValueLabel.take(3) else null,
                isActive = isFocusPeakingEnabled,
                enabled = true,
                onClick = { onToggleFocusPeaking() },
                onLongClick = { openDial = "peaking" },
                compact = compact
            )
            LensDialButton(
                icon = Icons.Filled.InvertColors,
                label = "F. Color",
                valueBadge = null,
                isActive = isFalseColorEnabled,
                enabled = true,
                onClick = onToggleFalseColor,
                compact = compact
            )
            // LUT: TAP liga/desliga o LUT ativo (ou volta pro último selecionado
            // se estava desligado); SEGURAR abre a lista de LUTs disponíveis.
            LensDialButton(
                icon = Icons.Filled.ColorLens,
                label = "LUT",
                valueBadge = if (isLutEnabled) lutValueLabel.take(4) else null,
                isActive = isLutEnabled,
                enabled = true,
                onClick = { onToggleLut() },
                onLongClick = { openDial = "lut" },
                compact = compact
            )
            // Aspect Ratio: TAP cicla para a próxima opção da lista (comportamento
            // pedido — clicar avança, não fica preso "abrindo/fechando" o dial);
            // SEGURAR abre a lista completa para pular direto numa opção.
            LensDialButton(
                icon = Icons.Filled.AspectRatio,
                label = "Aspect",
                valueBadge = if (currentAspectRatio != "OFF") currentAspectRatio else null,
                isActive = currentAspectRatio != "OFF",
                enabled = true,
                onClick = {
                    val currentIdx = aspectOptions.indexOf(currentAspectRatio).coerceAtLeast(0)
                    val nextIdx = (currentIdx + 1) % aspectOptions.size
                    onSetAspectRatio(aspectOptions[nextIdx])
                },
                onLongClick = { openDial = "aspect" },
                compact = compact
            )
        }
    }
}
