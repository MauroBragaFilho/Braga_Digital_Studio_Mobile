package com.bragastudio.mobile.featurepreview

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bragastudio.mobile.common.ui.theme.BdsmDarkSurfaceTheme
import com.bragastudio.mobile.corecapture.domain.CaptureState

@Composable
fun PreviewScreen(
    viewModel: PreviewViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit = {},
    onNavigateToLuts: () -> Unit = {},
    onNavigateHome: () -> Unit = {},
) {
    val context = LocalContext.current

    // Mantém a tela acesa enquanto o Preview está visível (monitoramento). Não é mais
    // necessário para o take sobreviver: a sessão de captura é independente da tela
    // (MediaGraph + CaptureForegroundService). Usa a View raiz, sem WAKE_LOCK.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // 1. STATE COLLECTION
    val currentLens by viewModel.currentLens.collectAsStateWithLifecycle()
    val availableLenses by viewModel.availableLenses.collectAsStateWithLifecycle()
    val availableAudioDevices by viewModel.availableAudioDevices.collectAsStateWithLifecycle()
    val videoSettings by viewModel.videoSettings.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    // M29: tempo de gravação (~33 Hz), níveis e picos de áudio (~6-12 Hz) NÃO são
    // delegados (`by`): guardamos o State e só o lemos dentro de lambdas passadas
    // ao HUD, para a raiz do PreviewScreen e do HUD não recomporem a cada tick.
    val recordingTimeState = viewModel.recordingTimeMs.collectAsStateWithLifecycle()
    val audioLevelsState = viewModel.audioLevels.collectAsStateWithLifecycle()
    val audioPeaksState = viewModel.audioPeaks.collectAsStateWithLifecycle()
    val captureMetadataState = viewModel.captureMetadata.collectAsStateWithLifecycle()
    val manualLimits by viewModel.manualLimits.collectAsStateWithLifecycle()
    val tally by viewModel.tally.collectAsStateWithLifecycle()
    val isRecTransitioning by viewModel.isRecTransitioning.collectAsStateWithLifecycle()
    val isFinalizing by viewModel.isFinalizing.collectAsStateWithLifecycle()
    val hwMetrics by viewModel.hardwareMetrics.collectAsStateWithLifecycle()
    val isScopesVisible by viewModel.isScopesVisible.collectAsStateWithLifecycle()
    val isFalseColorEnabled by viewModel.isFalseColorEnabled.collectAsStateWithLifecycle()
    val isZebraEnabled by viewModel.isZebraEnabled.collectAsStateWithLifecycle()
    val isLutEnabled by viewModel.isLutEnabled.collectAsStateWithLifecycle()
    val isFocusPeakingEnabled by viewModel.isFocusPeakingEnabled.collectAsStateWithLifecycle()
    val captureState by viewModel.captureState.collectAsStateWithLifecycle()
    // Zoom/pan mudam a cada evento do gesto de pinça: guardamos o State e só o lemos dentro do
    // gesto e do ZoomControlHost, para a raiz não recompor durante o pinch.
    val zoomFactorState = viewModel.zoomFactor.collectAsStateWithLifecycle()
    val panXState = viewModel.panX.collectAsStateWithLifecycle()
    val panYState = viewModel.panY.collectAsStateWithLifecycle()
    val ndiSettings by viewModel.ndiSettings.collectAsStateWithLifecycle()
    // Controle manual real da câmera nativa (Camera2Device) — ISO, obturador,
    // WB e foco já existiam no ViewModel/CaptureDevice, só não estavam
    // conectados a nenhum controle visual para a fonte "Camera"/"USB".
    val nativeCurrentIso by viewModel.currentIso.collectAsStateWithLifecycle()
    val nativeCurrentShutter by viewModel.currentShutter.collectAsStateWithLifecycle()
    val nativeCurrentWb by viewModel.currentWb.collectAsStateWithLifecycle()
    val nativeCurrentFocus by viewModel.currentFocus.collectAsStateWithLifecycle()
    // REMOVIDO: val isHistogramVisible by viewModel.isHistogramVisible.collectAsStateWithLifecycle() // Não é mais usado
    val currentAspectRatio by viewModel.currentAspectRatio.collectAsStateWithLifecycle() // Novo estado
    val currentGrid by viewModel.currentGrid.collectAsStateWithLifecycle() // ✅ Novo estado
    val selectedAudioDeviceName by viewModel.selectedAudioDeviceName.collectAsStateWithLifecycle() // ✅ Novo estado

    val allLuts by viewModel.allLuts.collectAsStateWithLifecycle()
    val activeLut by viewModel.activeLut.collectAsStateWithLifecycle()
    val monitorSettings by viewModel.monitorSettings.collectAsStateWithLifecycle()
    val isSonyActive by viewModel.isSonyActive.collectAsStateWithLifecycle()
    val torchEnabled by viewModel.torchEnabled.collectAsStateWithLifecycle()

    // A fonte "SONY" depende de descoberta SSDP + leitura do SSID da rede da
    // câmera, o que exige permissão de localização (Android 8-12) ou "Wi-Fi
    // Próximo" (Android 13+) — nenhuma das duas é pedida junto de Câmera/Microfone
    // no MainActivity de propósito, para não forçar essa permissão em quem nunca
    // vai usar uma Sony. Pedimos aqui, sob demanda, só quando o usuário escolhe
    // essa fonte no HUD.
    val sonyPermission = if (android.os.Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }
    var pendingSonySelection by remember { mutableStateOf(false) }
    val sonyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingSonySelection) {
            viewModel.setCameraSource("SONY")
        }
        pendingSonySelection = false
    }
    fun requestSonySourceSwitch() {
        val alreadyGranted = ContextCompat.checkSelfPermission(context, sonyPermission) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            viewModel.setCameraSource("SONY")
        } else {
            pendingSonySelection = true
            sonyPermissionLauncher.launch(sonyPermission)
        }
    }
    val sonyTelemetry by viewModel.sonyTelemetry.collectAsStateWithLifecycle()
    val focusPeakingSensitivitySlider = when (monitorSettings.focusPeakingSensitivity) {
        "Low" -> 0.2f
        "High" -> 0.85f
        else -> 0.5f
    }

    // Permissões já foram solicitadas na MainActivity, então assumimos que estão concedidas
    val hasCameraPermission = true
    val hasAudioPermission = true

    LaunchedEffect(Unit) {
        viewModel.onPermissionsGranted()
    }

    // 3. UI STATE & GESTURES
    var isHudVisible by remember { mutableStateOf(true) }
    // Cada toque na imagem acorda o dock (auto-ocultar) em vez de esconder toda a interface.
    var dockWakeSignal by remember { mutableIntStateOf(0) }

    var displayRotation by remember { mutableStateOf(android.view.Surface.ROTATION_0) }

    DisposableEffect(context) {
        val displayManager = context.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                // M37: ignora displays externos (HDMI/cast); só o display padrão
                // deve girar o preview e as saídas.
                if (displayId != android.view.Display.DEFAULT_DISPLAY) return
                val rot = displayManager.getDisplay(displayId)?.rotation ?: android.view.Surface.ROTATION_0
                displayRotation = rot
            }
        }
        displayManager.registerDisplayListener(listener, null)
        displayRotation = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: android.view.Surface.ROTATION_0

        onDispose {
            displayManager.unregisterDisplayListener(listener)
        }
    }

    val rotationDegrees = rotationDegreesFor(displayRotation)
    LaunchedEffect(rotationDegrees) {
        viewModel.updateRotationDegrees(rotationDegrees)
    }

    // Guarda referência da TextureView ativa para podermos reabrir a câmera
    // (attachSurface) quando o app voltar ao primeiro plano, sem depender do
    // onSurfaceTextureAvailable (que só dispara quando a surface é criada do zero).
    var activeTextureView by remember { mutableStateOf<TextureView?>(null) }
    // Surface reaproveitada entre ON_STOP/ON_START (M50): antes cada ON_START
    // criava uma Surface nova sem nunca liberar a anterior.
    val surfaceRef = remember { PreviewSurfaceRef() }

    // Sessão desacoplada da tela: ON_STOP e onSurfaceTextureDestroyed (mais abaixo) só SOLTAM
    // a surface de preview. Com REC/NDI/BSP ativos o MediaGraph mantém câmera, GL, saídas e
    // áudio (ancorados pelo CaptureForegroundService); sem nenhuma saída ativa ele desliga a
    // câmera como antes. No ON_START a surface é reanexada ao grafo vivo, sem reiniciar a câmera.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.detachSurface()

                Lifecycle.Event.ON_START -> {
                    val textureView = activeTextureView
                    if (textureView != null && textureView.isAvailable) {
                        val surfaceTexture = textureView.surfaceTexture
                        if (surfaceTexture != null) {
                            viewModel.attachSurface(surfaceRef.obtain(surfaceTexture))
                        }
                    }
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Monitor é SEMPRE escuro (sobre vídeo), independente do tema do app.
    BdsmDarkSurfaceTheme {
        if (hasCameraPermission && hasAudioPermission) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newZoom = (zoomFactorState.value * zoom).coerceIn(1.0f, 5.0f)
                            val newPanX = if (newZoom > 1.0f) panXState.value - (pan.x / size.width) else 0f
                            val newPanY = if (newZoom > 1.0f) panYState.value - (pan.y / size.height) else 0f
                            viewModel.updateZoomAndPan(newZoom, newPanX, newPanY)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { viewModel.updateZoomAndPan(1.0f, 0f, 0f) },
                            onTap = { if (isHudVisible) dockWakeSignal++ else isHudVisible = true },
                        )
                    },
            ) {
                // Camera Preview com correção de proporção do sensor
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            activeTextureView = this
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                                    // REMOVIDO setDefaultBufferSize para evitar tela preta em aparelhos incompatíveis
                                    viewModel.attachSurface(surfaceRef.obtain(surfaceTexture))

                                    post {
                                        fixTextureViewAspectRatio(this@apply, viewModel.sensorOrientation, videoSettings.videoSource == "USB", displayRotation)
                                    }
                                }

                                override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                                    post {
                                        fixTextureViewAspectRatio(this@apply, viewModel.sensorOrientation, videoSettings.videoSource == "USB", displayRotation)
                                    }
                                }

                                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                                    if (activeTextureView === this@apply) {
                                        activeTextureView = null
                                    }
                                    // M50: só liberamos Surface e SurfaceTexture DEPOIS que o
                                    // MediaGraph terminou o detach (a sessão pode seguir sem preview) (senão o render nativo ainda
                                    // faria eglSwapBuffers numa surface abandonada). Por isso
                                    // retornamos false: a liberação do SurfaceTexture é nossa.
                                    val oldSurface = surfaceRef.clear()
                                    viewModel.detachSurface().invokeOnCompletion {
                                        oldSurface?.release()
                                        surfaceTexture.release()
                                    }
                                    return false
                                }

                                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
                            }
                        }
                    },
                    update = { textureView ->
                        // Trigger recomposition on displayRotation change
                        val rot = displayRotation
                        if (textureView.isAvailable) {
                            fixTextureViewAspectRatio(textureView, viewModel.sensorOrientation, videoSettings.videoSource == "USB", rot)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .wrapContentSize(Alignment.Center),
                )

                // USB Waiting Overlay
                if (videoSettings.videoSource == "USB" && captureState == CaptureState.IDLE) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
                        Text(text = "Aguardando USB...", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Camera Error Overlay
                if (captureState == CaptureState.ERROR) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "⚠️ Erro no Sensor da Câmera", color = Color.Red, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Text(text = "A lente atual rejeitou a configuração ou o driver falhou.", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                            Text(text = "Tente alterar a resolução ou trocar de lente.", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(onClick = { viewModel.retryCamera() }) {
                                Text("Tentar novamente")
                            }
                        }
                    }
                }

                // Grids e Aspect sempre visíveis (independente do HUD estar oculto)
                GridAndAspectOverlay(
                    currentGrid = currentGrid,
                    currentAspectRatio = currentAspectRatio,
                )

                // 4. NOVA INTERFACE HUD (HUD Inteligente)
                CameraHUDOverlay(
                    isHudVisible = isHudVisible,
                    isRecording = isRecording,
                    isNdiEnabled = ndiSettings.isEnabled,
                    fps = videoSettings.fps,
                    videoSettings = videoSettings,
                    recordingTimeProvider = { recordingTimeState.value },
                    metrics = hwMetrics,
                    storageFreeGB = hwMetrics.storageFreeGB,
                    batteryPercentage = hwMetrics.batteryPercentage,
                    currentLens = currentLens,
                    availableLenses = availableLenses,
                    availableAudioDevices = availableAudioDevices,
                    onLensSelect = { lensId: String -> viewModel.selectLens(lensId) },
                    audioLevelLeftProvider = { audioLevelsState.value.leftLevel },
                    audioLevelRightProvider = { audioLevelsState.value.rightLevel },
                    // Pico linear do serviço -> mesma escala (-60..0 dBFS -> 0..1) da barra.
                    audioPeakLeftProvider = { linearToFraction(audioPeaksState.value.peakLeft) },
                    audioPeakRightProvider = { linearToFraction(audioPeaksState.value.peakRight) },
                    selectedAudioDeviceName = selectedAudioDeviceName,
                    isScopesVisible = isScopesVisible,
                    isZebraEnabled = isZebraEnabled,
                    zebraThreshold = monitorSettings.zebraThreshold,
                    onSetZebraThreshold = { viewModel.setZebraThreshold(it) },
                    focusPeakingSensitivity = focusPeakingSensitivitySlider,
                    onSetFocusPeakingSensitivity = { viewModel.setFocusPeakingSensitivity(it) },
                    focusPeakingColor = monitorSettings.focusPeakingColor,
                    onSetFocusPeakingColor = { viewModel.setFocusPeakingColor(it) },
                    isLutEnabled = isLutEnabled,
                    isFocusPeakingEnabled = isFocusPeakingEnabled,
                    isFalseColorEnabled = isFalseColorEnabled,
                    currentAspectRatio = currentAspectRatio,
                    currentGrid = currentGrid,
                    allLuts = allLuts,
                    activeLut = activeLut,
                    onSelectLut = { lutId -> viewModel.setActiveLut(lutId) },
                    onSetAspectRatio = { ratio -> viewModel.setAspectRatio(ratio) },
                    onSetGrid = { grid -> viewModel.setGrid(grid) },
                    onSetCameraSource = { source ->
                        if (source == "SONY") requestSonySourceSwitch() else viewModel.setCameraSource(source)
                    },
                    onSetResolution = { viewModel.setResolution(it) },
                    onSetFps = { viewModel.setFps(it) },
                    onSetBitrate = { viewModel.setBitrate(it) },
                    onSetCodec = { viewModel.setCodec(it) },
                    onSelectAudioDevice = { viewModel.selectAudioDevice(it) },
                    onToggleFocusPeaking = { viewModel.toggleFocusPeaking() },
                    onRecordClick = { viewModel.toggleRecording() },
                    onNdiToggle = { viewModel.toggleNdi() },
                    onNavigateToSettings = onNavigateToSettings,
                    onNavigateToLuts = onNavigateToLuts,
                    onToggleScopes = { viewModel.toggleScopesVisibility() },
                    onToggleZebra = { viewModel.toggleZebra() },
                    onToggleLut = { viewModel.toggleLut() },
                    onToggleFalseColor = { viewModel.toggleFalseColor() },
                    onToggleGrid = { viewModel.toggleGrid() },
                    isSonyActive = isSonyActive,
                    sonyTelemetry = sonyTelemetry,
                    onSetIso = { viewModel.setIso(it) },
                    onSetShutter = { viewModel.setShutter(it) },
                    onSonySetAperture = { viewModel.sonySetAperture(it) },
                    onSonyTakePicture = { viewModel.sonyTakePicture() },
                    onNavigateHome = onNavigateHome,
                    nativeCurrentIso = nativeCurrentIso,
                    nativeCurrentShutterNanos = nativeCurrentShutter,
                    nativeCurrentWbMode = nativeCurrentWb,
                    nativeCurrentFocusDiopters = nativeCurrentFocus,
                    onSetNativeWb = { viewModel.setWb(it) },
                    onSetNativeFocus = { viewModel.setFocus(it) },
                    isTorchEnabled = torchEnabled,
                    onToggleTorch = { viewModel.toggleTorch() },
                    isStabilizationEnabled = videoSettings.stabilizationEnabled,
                    onToggleStabilization = { viewModel.toggleVideoStabilization() },
                    isHdrEnabled = videoSettings.hdrEnabled,
                    onToggleHdr = { viewModel.toggleHdr() },
                    onSonySetIrisAuto = { viewModel.sonySetIrisAuto() },
                    tally = tally,
                    isRecTransitioning = isRecTransitioning,
                    captureMetadataProvider = { captureMetadataState.value },
                    manualLimits = manualLimits,
                    dockWakeSignal = dockWakeSignal,
                    onHideInterface = { isHudVisible = false },
                    zoomFactorProvider = { zoomFactorState.value },
                    panXProvider = { panXState.value },
                    panYProvider = { panYState.value },
                    finalizingMessage = if (isFinalizing) FINALIZING_MESSAGE else null,
                    scopesContent = if (isScopesVisible) {
                        { ScopesHost(viewModel) }
                    } else {
                        null
                    },
                )

                // Feedback de erro de gravação/NDI: antes esses erros só iam pro Logcat
                // e o operador não tinha nenhuma pista de por que o REC não funcionou.
                val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
                LaunchedEffect(Unit) {
                    viewModel.errorEvents.collect { message ->
                        snackbarHostState.showSnackbar(
                            message = message,
                            duration = androidx.compose.material3.SnackbarDuration.Long,
                        )
                    }
                }
                androidx.compose.material3.SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 100.dp),
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text(text = "Permissões de Câmera e Microfone necessárias", color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

/**
 * Corrige a proporção do TextureView para não esticar a imagem.
 */
private fun fixTextureViewAspectRatio(textureView: TextureView, @Suppress("UNUSED_PARAMETER") sensorOrientation: Int, @Suppress("UNUSED_PARAMETER") isUsbCamera: Boolean, @Suppress("UNUSED_PARAMETER") displayRotation: Int) {
    val viewWidth = textureView.width.toFloat()
    val viewHeight = textureView.height.toFloat()

    if (viewWidth == 0f || viewHeight == 0f) return

    val isPortrait = viewHeight > viewWidth
    val sensorAspectRatio = if (isPortrait) 9f / 16f else 16f / 9f
    val viewAspectRatio = viewWidth / viewHeight

    val matrix = android.graphics.Matrix()
    val scaleX: Float
    val scaleY: Float

    if (sensorAspectRatio > viewAspectRatio) {
        scaleX = 1f
        scaleY = viewAspectRatio / sensorAspectRatio
    } else {
        scaleX = sensorAspectRatio / viewAspectRatio
        scaleY = 1f
    }

    matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
    textureView.setTransform(matrix)
}

/**
 * Coleta `videoScopes` (~10 Hz) num escopo de recomposição próprio, para a raiz
 * do PreviewScreen e o HUD não recomporem a cada tick dos scopes (M29).
 */
@Composable
private fun ScopesHost(viewModel: PreviewViewModel) {
    val videoScopes by viewModel.videoScopes.collectAsStateWithLifecycle()
    com.bragastudio.mobile.featurepreview.components.scopes.ScopesOverlay(
        videoScopes = videoScopes,
        onCycleScope = { viewModel.cycleScopeType() },
        modifier = Modifier.size(136.dp, 76.dp),
    )
}

/**
 * Mapeia a rotação do display (Surface.ROTATION_*) para os graus aplicados ao
 * render nativo (sentido inverso: o conteúdo gira contra o aparelho).
 */
internal fun rotationDegreesFor(displayRotation: Int): Float = when (displayRotation) {
    Surface.ROTATION_0 -> 0f
    Surface.ROTATION_90 -> 270f
    Surface.ROTATION_180 -> 180f
    Surface.ROTATION_270 -> 90f
    else -> 0f
}

/**
 * Guarda a [Surface] criada a partir do SurfaceTexture da TextureView para
 * reaproveitá-la enquanto o mesmo SurfaceTexture existir (ON_STOP -> ON_START).
 */
internal class PreviewSurfaceRef {
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null

    fun obtain(surfaceTexture: SurfaceTexture): Surface {
        val current = surface
        if (current != null && current.isValid && texture === surfaceTexture) return current
        current?.release()
        return Surface(surfaceTexture).also {
            surface = it
            texture = surfaceTexture
        }
    }

    /** Entrega a Surface atual (para o chamador liberar após o detach) e esquece-a. */
    fun clear(): Surface? {
        val old = surface
        surface = null
        texture = null
        return old
    }
}
