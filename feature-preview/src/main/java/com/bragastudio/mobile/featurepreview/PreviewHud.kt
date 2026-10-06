package com.bragastudio.mobile.featurepreview

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bragastudio.mobile.core.domain.HardwareMetrics
import com.bragastudio.mobile.core.domain.VideoSettings
import com.bragastudio.mobile.corecapture.domain.CameraInfoModel
import com.bragastudio.mobile.corecapture.domain.CaptureMetadata
import com.bragastudio.mobile.corecapture.domain.ManualLimits
import com.bragastudio.mobile.network.TallyState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// ============================================================================
// COMPONENTE PRINCIPAL DO HUD
// ============================================================================
//
// Estados de alta frequência (M29): `recordingTime` (~33 Hz) e `audioLevel*`
// (~6-12 Hz) continuam aceitos como valores (compatibilidade com o chamador
// atual), mas o HUD só os repassa às folhas (TimecodeText / AudioMetersOverlay)
// como PROVEDORES `() -> T`. Quem chama pode passar `recordingTimeProvider` /
// `audioLevel*Provider` (lendo o State dentro da lambda) para que NEM a raiz do
// HUD recomponha a cada tick — nesse caso os parâmetros por valor ficam nos
// defaults.
//
// Navegação durante o REC: Home / Configurações / Gerenciar LUTs navegam direto — a gravação
// continua (sessão de captura desacoplada da tela; ver MediaGraph/CaptureForegroundService).
@Composable
fun CameraHUDOverlay(
    isHudVisible: Boolean,
    isRecording: Boolean,
    isNdiEnabled: Boolean,
    fps: Int,
    videoSettings: VideoSettings,
    recordingTime: Long = 0L,
    metrics: HardwareMetrics,
    storageFreeGB: Float,
    batteryPercentage: Int,
    currentLens: CameraInfoModel?,
    availableLenses: List<CameraInfoModel>,
    onLensSelect: (String) -> Unit,
    audioLevelLeft: Float = 0f,
    audioLevelRight: Float = 0f,
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
    onToggleFocusPeaking: () -> Unit,
    onRecordClick: () -> Unit,
    onNdiToggle: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLuts: () -> Unit,
    onToggleScopes: () -> Unit,
    onToggleZebra: () -> Unit,
    onToggleLut: () -> Unit,
    onToggleFalseColor: () -> Unit,
    onToggleGrid: () -> Unit,
    availableAudioDevices: List<android.media.AudioDeviceInfo> = emptyList(),
    onSetCameraSource: (String) -> Unit = {},
    onSetResolution: (String) -> Unit = {},
    onSetFps: (Int) -> Unit = {},
    onSetBitrate: (Int) -> Unit = {},
    onSetCodec: (String) -> Unit = {},
    onSelectAudioDevice: (android.media.AudioDeviceInfo) -> Unit = {},
    // Controle remoto da Sony via Wi-Fi: só usado quando a FONTE ativa é "SONY";
    // nas demais fontes esses params ficam com os defaults e o chip não aparece.
    isSonyActive: Boolean = false,
    sonyTelemetry: com.bragastudio.mobile.core.model.SonyCameraStatus = com.bragastudio.mobile.core.model.SonyCameraStatus(),
    onSetIso: (Int?) -> Unit = {},
    onSetShutter: (Long?) -> Unit = {},
    onSonySetAperture: (String) -> Unit = {},
    onSonyTakePicture: () -> Unit = {},
    // NOTA (M30/B40): a UI "clássica" foi removida; os antigos parâmetros ignorados
    // `isModernUiEnabled`/`onToggleAspectRatio` saíram da assinatura (a UI moderna é
    // a única e o aspect ratio é escolhido pelo popover da leftbar via onSetAspectRatio).
    // Controles avançados de captura (Camera2): lanterna (torch), estabilização
    // de vídeo (OIS/EIS) e HDR. A disponibilidade de cada recurso é derivada de
    // currentLens (hasTorch/hasOis/hasEis/supportsHdr/supportsHdr10 — HDR real só
    // liga com HLG10 + codec HEVC).
    isTorchEnabled: Boolean = false,
    onToggleTorch: () -> Unit = {},
    isStabilizationEnabled: Boolean = false,
    onToggleStabilization: () -> Unit = {},
    isHdrEnabled: Boolean = false,
    onToggleHdr: () -> Unit = {},
    onNavigateHome: () -> Unit = {},
    // Controle manual real da câmera nativa (Camera2Device): ISO/obturador/WB/foco
    // por CaptureRequest.Builder. Valores atuais vêm do PreviewViewModel.
    nativeCurrentIso: Int? = null,
    nativeCurrentShutterNanos: Long? = null,
    nativeCurrentWbMode: Int? = null,
    nativeCurrentFocusDiopters: Float? = null,
    onSetNativeWb: (Int?) -> Unit = {},
    onSetNativeFocus: (Float?) -> Unit = {},
    // ---- Adições aditivas (todas com default; não quebram o chamador atual) ----
    // M29: provedores de alta frequência. Se informados, têm prioridade sobre os
    // valores por parâmetro acima.
    recordingTimeProvider: (() -> Long)? = null,
    audioLevelLeftProvider: (() -> Float)? = null,
    audioLevelRightProvider: (() -> Float)? = null,
    // M32: picos por canal (fração 0..1, mesma escala do nível). Se nulos, o nível
    // atual é tratado como pico.
    audioPeakLeftProvider: (() -> Float)? = null,
    audioPeakRightProvider: (() -> Float)? = null,
    // M30: íris "AUTO" da Sony exige trocar o modo de exposição (NÃO é setFNumber("AUTO")).
    // Enquanto o chamador não ligar isto, escolher AUTO na íris não faz nada.
    onSonySetIrisAuto: () -> Unit = {},
    // Tally vindo do OBS (BDSM Link): PROGRAM = vermelho, PREVIEW = verde. O REC local
    // continua mostrando vermelho independentemente do tally.
    tally: TallyState = TallyState.OFF,
    // Preparando/Finalizando o take (RecState.isTransitioning): desabilita o botão REC.
    isRecTransitioning: Boolean = false,
    // Telemetria real do sensor (ISO/obturador/AE) lida só dentro do cluster de controles
    // manuais, para a raiz do HUD não recompor a cada resultado de captura.
    captureMetadataProvider: () -> CaptureMetadata = { CaptureMetadata() },
    // Faixas ISO/obturador/foco da câmera ativa: limitam as opções dos dials.
    manualLimits: ManualLimits = ManualLimits(),
    // Incrementa a cada toque na imagem: traz o dock de volta (e tira o esmaecimento da faixa).
    dockWakeSignal: Int = 0,
    // "Ocultar interface" (clean feed): o toque na imagem a traz de volta.
    onHideInterface: () -> Unit = {},
    // Zoom digital (indicador + mini-mapa sob a faixa de informações).
    zoomFactorProvider: () -> Float = { 1f },
    panXProvider: () -> Float = { 0f },
    panYProvider: () -> Float = { 0f },
    // "Finalizando gravação…" (take anterior sendo fechado), exibido sob a faixa.
    finalizingMessage: String? = null,
    // Overlay de scopes (arrastável): fornecido pelo chamador, que lê os scopes de alta
    // frequência fora da raiz do HUD.
    scopesContent: (@Composable () -> Unit)? = null,
) {
    // --- M29: provedores estáveis para as folhas -----------------------------
    // rememberUpdatedState + lambda lembrada: a identidade do provedor é estável
    // entre recomposições e a leitura do valor acontece SÓ dentro da folha.
    val recordingTimeState = rememberUpdatedState(recordingTime)
    val audioLeftState = rememberUpdatedState(audioLevelLeft)
    val audioRightState = rememberUpdatedState(audioLevelRight)
    val timeProvider: () -> Long = recordingTimeProvider ?: remember<() -> Long> { { recordingTimeState.value } }
    val levelLeftProvider: () -> Float = audioLevelLeftProvider ?: remember<() -> Float> { { audioLeftState.value } }
    val levelRightProvider: () -> Float = audioLevelRightProvider ?: remember<() -> Float> { { audioRightState.value } }

    // Layout por constraints (não por rotação): paisagem = mais larga que alta.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val singleRow = maxWidth >= 600.dp
        val availableHeight = maxHeight

        // Tally Light: borda pulsante em volta de todo o quadro (inclusive no clean feed).
        val tallyLight = tallyIndicator(tally, isRecording)
        if (tallyLight != TallyIndicator.NONE) {
            TallyBorder(modifier = Modifier.fillMaxSize(), indicator = tallyLight)
        }

        // ---- Auto-ocultar: SÓ o dock se oculta; a faixa apenas esmaece -------------
        var interactionTick by remember { mutableIntStateOf(0) }
        val openPopovers = remember { mutableStateMapOf<String, Boolean>() }
        val anyPopoverOpen = openPopovers.values.any { it }
        val onPopoverOpenChanged: (String, Boolean) -> Unit = { key, open -> openPopovers[key] = open }
        val onInteraction: () -> Unit = { interactionTick++ }
        var dockVisible by remember { mutableStateOf(true) }
        var stripDimmed by remember { mutableStateOf(false) }
        LaunchedEffect(dockWakeSignal, interactionTick, isRecording, anyPopoverOpen) {
            dockVisible = true
            stripDimmed = false
            val start = SystemClock.elapsedRealtime()
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - start
                dockVisible = !isDockHidden(elapsed, isRecording, anyPopoverOpen)
                stripDimmed = isStripDimmed(elapsed, anyPopoverOpen)
                if (!dockVisible && stripDimmed) break
                delay(250)
            }
        }

        val density = LocalDensity.current
        var stripHeightPx by remember { mutableIntStateOf(0) }
        val stripHeight = if (isHudVisible) with(density) { stripHeightPx.toDp() } else 0.dp

        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (isHudVisible) {
                HudInfoStrip(
                    singleRow = singleRow,
                    dimmed = stripDimmed,
                    isRecording = isRecording,
                    timeProvider = timeProvider,
                    videoSettings = videoSettings,
                    isWifiConnected = metrics.isWifiConnected,
                    storageFreeGB = storageFreeGB,
                    batteryPercentage = batteryPercentage,
                    isNdiEnabled = isNdiEnabled,
                    onNdiToggle = onNdiToggle,
                    currentLens = currentLens,
                    isTorchEnabled = isTorchEnabled,
                    isStabilizationEnabled = isStabilizationEnabled,
                    isHdrEnabled = isHdrEnabled,
                    selectedAudioDeviceName = selectedAudioDeviceName,
                    availableAudioDevices = availableAudioDevices,
                    onToggleTorch = onToggleTorch,
                    onToggleStabilization = onToggleStabilization,
                    onToggleHdr = onToggleHdr,
                    onSetCameraSource = onSetCameraSource,
                    onSelectAudioDevice = onSelectAudioDevice,
                    onSetResolution = onSetResolution,
                    onSetFps = onSetFps,
                    onSetBitrate = onSetBitrate,
                    onSetCodec = onSetCodec,
                    onNavigateHome = onNavigateHome,
                    onNavigateToSettings = onNavigateToSettings,
                    onHideInterface = onHideInterface,
                    onPopoverOpenChanged = onPopoverOpenChanged,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .onGloballyPositioned { stripHeightPx = it.size.height }
                        .onAnyPress(onInteraction),
                    exposureTiles = {
                        // Fonte USB (capturadora genérica) não expõe ISO/obturador/WB/foco.
                        if (videoSettings.videoSource != "USB") {
                            ExposureTiles(
                                isSonyActive = isSonyActive,
                                sonyTelemetry = sonyTelemetry,
                                onSetIso = onSetIso,
                                onSetShutter = onSetShutter,
                                onSonySetAperture = onSonySetAperture,
                                onSonySetIrisAuto = onSonySetIrisAuto,
                                nativeCurrentIso = nativeCurrentIso,
                                nativeCurrentShutterNanos = nativeCurrentShutterNanos,
                                nativeCurrentWbMode = nativeCurrentWbMode,
                                nativeCurrentFocusDiopters = nativeCurrentFocusDiopters,
                                onSetNativeWb = onSetNativeWb,
                                onSetNativeFocus = onSetNativeFocus,
                                captureMetadataProvider = captureMetadataProvider,
                                manualLimits = manualLimits,
                                fps = fps,
                                onPopoverOpenChanged = onPopoverOpenChanged,
                            )
                        }
                    },
                )
            }

            // Dock de ferramentas: lateral em paisagem; horizontal acima do REC em retrato.
            val dock: @Composable (Modifier) -> Unit = { m ->
                AnimatedVisibility(visible = isHudVisible && dockVisible, enter = fadeIn(), exit = fadeOut(), modifier = m) {
                    ToolsDock(
                        vertical = landscape,
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
                        currentGrid = currentGrid,
                        onToggleGrid = onToggleGrid,
                        onSetGrid = onSetGrid,
                        onToggleScopes = onToggleScopes,
                        onToggleZebra = onToggleZebra,
                        onToggleFocusPeaking = onToggleFocusPeaking,
                        onToggleFalseColor = onToggleFalseColor,
                        onToggleLut = onToggleLut,
                        onInteraction = onInteraction,
                        onPopoverOpenChanged = onPopoverOpenChanged,
                    )
                }
            }
            val recGroup: @Composable (Modifier) -> Unit = { m ->
                RecAndLensControls(
                    landscape = landscape,
                    isHudVisible = isHudVisible,
                    isRecording = isRecording,
                    currentLens = currentLens,
                    availableLenses = availableLenses,
                    onRecordClick = onRecordClick,
                    onLensSelect = onLensSelect,
                    isRecTransitioning = isRecTransitioning,
                    modifier = m.onAnyPress(onInteraction),
                )
            }
            val meters: @Composable (Modifier) -> Unit = { m ->
                if (isHudVisible) {
                    AudioMetersOverlay(
                        audioLevelLeft = levelLeftProvider,
                        audioLevelRight = levelRightProvider,
                        peakLeft = audioPeakLeftProvider,
                        peakRight = audioPeakRightProvider,
                        modifier = m,
                    )
                }
            }

            if (landscape) {
                dock(Modifier.align(Alignment.CenterStart).padding(start = 4.dp, top = stripHeight))
                recGroup(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp))
                meters(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
            } else {
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    dock(Modifier)
                    recGroup(Modifier)
                    meters(Modifier.padding(top = 6.dp))
                }
            }

            // Scopes: overlay translúcido pequeno, ARRASTÁVEL (a posição é mantida na sessão).
            if (scopesContent != null) {
                var dragX by remember { mutableFloatStateOf(0f) }
                var dragY by remember { mutableFloatStateOf(0f) }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = stripHeight + 8.dp, start = if (landscape) 112.dp else 12.dp)
                        .offset { IntOffset(dragX.roundToInt(), dragY.roundToInt()) }
                        .pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                dragX += drag.x
                                dragY += drag.y
                            }
                        },
                ) { scopesContent() }
            }

            // Zoom digital + mensagem de finalização, sob a faixa de informações.
            if (isHudVisible) {
                Column(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = stripHeight + 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (finalizingMessage != null) {
                        Text(
                            text = finalizingMessage,
                            color = Color.White,
                            fontSize = HudTheme.fontSizeNormal,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    ZoomControlHost(zoomFactorProvider, panXProvider, panYProvider)
                }
            }

            // M30: chip de status da Sony (conexão/bateria/cartão) + botão de disparo, logo
            // abaixo da faixa. Só com a fonte "SONY" ativa e a interface visível.
            if (isSonyActive && isHudVisible) {
                SonyStatusChip(
                    telemetry = sonyTelemetry,
                    onSetIso = onSetIso,
                    onSetShutter = onSetShutter,
                    onSetAperture = onSonySetAperture,
                    onSetApertureAuto = onSonySetIrisAuto,
                    onTakePicture = onSonyTakePicture,
                    maxPanelHeight = (availableHeight - stripHeight - 24.dp).coerceAtLeast(160.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = stripHeight + 4.dp, end = 12.dp),
                )
            }
        }
    }
}
