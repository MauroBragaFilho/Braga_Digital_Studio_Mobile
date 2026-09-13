package com.bragastudio.mobile.featurepreview

import android.Manifest
import android.content.pm.PackageManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.graphics.SurfaceTexture
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
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.bragastudio.mobile.corecapture.domain.CaptureState

@Composable
fun PreviewScreen(
    viewModel: PreviewViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit = {},
    onNavigateToLuts: () -> Unit = {},
    onNavigateHome: () -> Unit = {}
) {
    val context = LocalContext.current

    // Interface nova (topbar minimalista + controles manuais/scopes/LUT via
    // dial circular) é o único modo de UI do app, persistido no ViewModel
    // (DataStore). Não há mais alternância manual entre clássica/nova.
    val isModernUiEnabled by viewModel.isModernUiEnabled.collectAsState()

    // TODO: mover para DataStore/preferências do usuário (ver feature-settings)
    // quando o toggle for exposto na tela de Configurações.

    // 1. STATE COLLECTION
    val currentLens by viewModel.currentLens.collectAsState()
    val availableLenses by viewModel.availableLenses.collectAsState()
    val availableAudioDevices by viewModel.availableAudioDevices.collectAsState()
    val videoSettings by viewModel.videoSettings.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingTime by viewModel.recordingTimeMs.collectAsState()
    val audioLevels by viewModel.audioLevels.collectAsState()
    val hwMetrics by viewModel.hardwareMetrics.collectAsState()
    val metrics by viewModel.hardwareMetrics.collectAsState() // ✅ Obtem metrics do ViewModel
    val videoScopes by viewModel.videoScopes.collectAsState() // ✅ Usamos videoScopes para isScopesVisible
    val isFalseColorEnabled by viewModel.isFalseColorEnabled.collectAsState()
    val isZebraEnabled by viewModel.isZebraEnabled.collectAsState()
    val isLutEnabled by viewModel.isLutEnabled.collectAsState()
    val isFocusPeakingEnabled by viewModel.isFocusPeakingEnabled.collectAsState()
    val captureState by viewModel.captureState.collectAsState()
    val zoomFactor by viewModel.zoomFactor.collectAsState()
    val panX by viewModel.panX.collectAsState()
    val panY by viewModel.panY.collectAsState()
    val ndiSettings by viewModel.ndiSettings.collectAsState()
    // Controle manual real da câmera nativa (Camera2Device) — ISO, obturador,
    // WB e foco já existiam no ViewModel/CaptureDevice, só não estavam
    // conectados a nenhum controle visual para a fonte "Camera"/"USB".
    val nativeCurrentIso by viewModel.currentIso.collectAsState()
    val nativeCurrentShutter by viewModel.currentShutter.collectAsState()
    val nativeCurrentWb by viewModel.currentWb.collectAsState()
    val nativeCurrentFocus by viewModel.currentFocus.collectAsState()
    // REMOVIDO: val isHistogramVisible by viewModel.isHistogramVisible.collectAsState() // Não é mais usado
    val currentAspectRatio by viewModel.currentAspectRatio.collectAsState() // Novo estado
    val currentGrid by viewModel.currentGrid.collectAsState() // ✅ Novo estado
    val selectedAudioDeviceName by viewModel.selectedAudioDeviceName.collectAsState() // ✅ Novo estado
    
    val allLuts by viewModel.allLuts.collectAsState()
    val activeLut by viewModel.activeLut.collectAsState()
    val monitorSettings by viewModel.monitorSettings.collectAsState()
    val isSonyActive by viewModel.isSonyActive.collectAsState()
    val torchEnabled by viewModel.torchEnabled.collectAsState()

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
        ActivityResultContracts.RequestPermission()
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
    val sonyTelemetry by viewModel.sonyTelemetry.collectAsState()
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

    var displayRotation by remember { mutableStateOf(android.view.Surface.ROTATION_0) }
    
    DisposableEffect(context) {
        val displayManager = context.getSystemService(android.content.Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val listener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
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

    val rotationDegrees = when (displayRotation) {
        android.view.Surface.ROTATION_0 -> 0f
        android.view.Surface.ROTATION_90 -> 270f
        android.view.Surface.ROTATION_180 -> 180f
        android.view.Surface.ROTATION_270 -> 90f
        else -> 0f
    }
    LaunchedEffect(rotationDegrees) {
        viewModel.updateRotationDegrees(rotationDegrees)
    }

    // Guarda referência da TextureView ativa para podermos reabrir a câmera
    // (attachSurface) quando o app voltar ao primeiro plano, sem depender do
    // onSurfaceTextureAvailable (que só dispara quando a surface é criada do zero).
    var activeTextureView by remember { mutableStateOf<TextureView?>(null) }

    // A câmera deve ficar aberta SOMENTE enquanto o usuário está de fato olhando
    // pra tela de monitor/preview. onSurfaceTextureDestroyed (mais abaixo) cobre
    // navegação para outra tela, mas NÃO dispara quando o usuário só minimiza o
    // app (Home / troca de app): nesse caso a Activity vai para ON_STOP mas a
    // TextureView continua viva, e a câmera ficaria aberta em segundo plano.
    // Este observer cobre esse caso.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    viewModel.detachSurface()
                }
                Lifecycle.Event.ON_START -> {
                    val textureView = activeTextureView
                    if (textureView != null && textureView.isAvailable) {
                        val surfaceTexture = textureView.surfaceTexture
                        if (surfaceTexture != null) {
                            viewModel.attachSurface(android.view.Surface(surfaceTexture))
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

    if (hasCameraPermission && hasAudioPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newZoom = (zoomFactor * zoom).coerceIn(1.0f, 5.0f)
                        val newPanX = if (newZoom > 1.0f) panX - (pan.x / size.width) else 0f
                        val newPanY = if (newZoom > 1.0f) panY - (pan.y / size.height) else 0f
                        viewModel.updateZoomAndPan(newZoom, newPanX, newPanY)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { viewModel.updateZoomAndPan(1.0f, 0f, 0f) },
                        onTap = { isHudVisible = !isHudVisible }
                    )
                }
        ) {
           // Camera Preview com correção de proporção do sensor
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        activeTextureView = this
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                                // REMOVIDO setDefaultBufferSize para evitar tela preta em aparelhos incompatíveis
                                viewModel.attachSurface(android.view.Surface(surfaceTexture))

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
                                viewModel.detachSurface()
                                if (activeTextureView === this@apply) {
                                    activeTextureView = null
                                }
                                return true
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
                    .wrapContentSize(Alignment.Center)
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
                currentAspectRatio = currentAspectRatio
            )

            // 4. NOVA INTERFACE HUD (HUD Inteligente)
            CameraHUDOverlay(
                isHudVisible = isHudVisible,
                displayRotation = displayRotation,
                isRecording = isRecording,
                isNdiEnabled = ndiSettings.isEnabled,
                fps = videoSettings.fps,
                videoSettings = videoSettings,
                recordingTime = recordingTime,
                metrics = metrics,
                storageFreeGB = hwMetrics.storageFreeGB,
                batteryPercentage = hwMetrics.batteryPercentage,
                currentLens = currentLens,
                availableLenses = availableLenses,
                availableAudioDevices = availableAudioDevices,
                onLensSelect = { lensId: String -> viewModel.selectLens(lensId) },
                audioLevelLeft = audioLevels.leftLevel,
                audioLevelRight = audioLevels.rightLevel,
                selectedAudioDeviceName = selectedAudioDeviceName,
                isScopesVisible = videoScopes.isVisible,
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
                onToggleCameraSource = {
                    // O ciclo (Camera→USB→SONY→Camera) precisa da mesma checagem de
                    // permissão: se o próximo passo do ciclo for SONY, intercepta.
                    val next = when (videoSettings.videoSource) {
                        "Camera" -> "USB"
                        "USB" -> "SONY"
                        else -> "Camera"
                    }
                    if (next == "SONY") requestSonySourceSwitch() else viewModel.setCameraSource(next)
                },
                onSetCameraSource = { source ->
                    if (source == "SONY") requestSonySourceSwitch() else viewModel.setCameraSource(source)
                },
                onCycleResolution = { viewModel.cycleResolution() },
                onSetResolution = { viewModel.setResolution(it) },
                onCycleFps = { viewModel.cycleFps() },
                onSetFps = { viewModel.setFps(it) },
                onCycleBitrate = { viewModel.cycleBitrate() },
                onSetBitrate = { viewModel.setBitrate(it) },
                onCycleCodec = { viewModel.cycleCodec() },
                onSetCodec = { viewModel.setCodec(it) },
                onCycleAudioDevice = { viewModel.cycleAudioDevice() },
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
                onToggleAspectRatio = { viewModel.toggleAspectRatio() },
                onToggleGrid = { viewModel.toggleGrid() },
                isSonyActive = isSonyActive,
                sonyTelemetry = sonyTelemetry,
                onSetIso = { viewModel.setIso(it) },
                onSetShutter = { viewModel.setShutter(it) },
                onSonySetAperture = { viewModel.sonySetAperture(it) },
                onSonyTakePicture = { viewModel.sonyTakePicture() },
                isModernUiEnabled = isModernUiEnabled,
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
                onToggleHdr = { viewModel.toggleHdr() }
            )

            // Controle de zoom + mini-mapa (só quando o HUD está visível, para não
            // atrapalhar o "clean feed" usado em gravações/monitoramento externo)
            if (isHudVisible) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 100.dp)
                ) {
                    ZoomControl(
                        zoomFactor = zoomFactor,
                        panX = panX,
                        panY = panY,
                        onSetZoom = { zoom, px, py -> viewModel.updateZoomAndPan(zoom, px, py) }
                    )
                }
            }

            // 5. ELEMENTOS SEMPRE VISÍVEIS (Scopes fora do Clean Feed, se preferir)
            if (videoScopes.isVisible) {
                Box(modifier = Modifier.align(Alignment.BottomStart).padding(start = 72.dp, bottom = 80.dp)) {
                    com.bragastudio.mobile.featurepreview.components.scopes.ScopesOverlay(
                        videoScopes = videoScopes,
                        onCycleScope = { viewModel.cycleScopeType() },
                        modifier = Modifier.size(160.dp, 90.dp)
                    )
                }
            }

            // Feedback de erro de gravação/NDI: antes esses erros só iam pro Logcat
            // e o operador não tinha nenhuma pista de por que o REC não funcionou.
            val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
            LaunchedEffect(Unit) {
                viewModel.errorEvents.collect { message ->
                    snackbarHostState.showSnackbar(
                        message = message,
                        duration = androidx.compose.material3.SnackbarDuration.Long
                    )
                }
            }
            androidx.compose.material3.SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp)
            )
        }
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text(text = "Permissões de Câmera e Microfone necessárias", color = Color.White, fontSize = 16.sp)
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