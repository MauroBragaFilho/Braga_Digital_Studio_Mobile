package com.bragastudio.mobile.featuresettings

import android.content.Intent
import android.media.AudioDeviceInfo
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bragastudio.mobile.common.components.* // <- Import adicionado
import com.bragastudio.mobile.coremedia.domain.LutManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateUp: () -> Unit = {},
    onNavigateToEasterEgg: () -> Unit = {},
    onNavigateToDiagnostics: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    val videoSettings by viewModel.videoSettings.collectAsState()
    val monitorSettings by viewModel.monitorSettings.collectAsState()
    val availableAudioDevices by viewModel.availableAudioDevices.collectAsState()
    val selectedAudioDevice by viewModel.selectedAudioDevice.collectAsState()

    var showMicrophoneDialog by remember { mutableStateOf(false) }

    var showResolutionDialog by remember { mutableStateOf(false) }
    var showFpsDialog by remember { mutableStateOf(false) }
    var showBitrateDialog by remember { mutableStateOf(false) }
    var showCodecDialog by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }
    
    var showZebraDialog by remember { mutableStateOf(false) }
    var showPeakingColorDialog by remember { mutableStateOf(false) }
    var showPeakingSensitivityDialog by remember { mutableStateOf(false) }
    
    var aboutClickCount by remember { mutableStateOf(0) }
    var lastAboutClickTime by remember { mutableStateOf(0L) }

    // --- NOVO: Obter versão real ---
    val appVersionName = remember {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "Desconhecida"
        } catch (e: Exception) {
            "Erro"
        }
    }
    val appVersionCode = remember {
        try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.longVersionCode
        } catch (e: Exception) {
            -1L
        }
    }
    // --- FIM NOVO ---



    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val contentResolver = context.contentResolver
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)
            viewModel.setRecordingDirectoryUri(uri.toString())
        }
    }



    fun getAudioDeviceName(device: AudioDeviceInfo?): String {
        if (device == null) return "Microfone do Celular"
        return when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microfone do Celular"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Fone de Ouvido com Fio"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "Microfone USB"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Dispositivo Bluetooth"
            else -> device.productName?.toString() ?: "Dispositivo de Áudio Externo"
        }
    }

    // Diálogos
    if (showResolutionDialog) OptionsDialog("Resolução (Gravação)", listOf("1080p", "1440p", "4K"), videoSettings.resolution, { viewModel.setVideoResolution(it); showResolutionDialog = false }, { showResolutionDialog = false })
    if (showSourceDialog) OptionsDialog("Fonte de Vídeo", listOf("Camera", "USB", "SONY"), videoSettings.videoSource, { viewModel.setVideoSource(it); showSourceDialog = false }, { showSourceDialog = false })
    if (showFpsDialog) OptionsDialog("Taxa de Quadros", listOf("24", "30", "60"), videoSettings.fps.toString(), { viewModel.setVideoFps(it.toInt()); showFpsDialog = false }, { showFpsDialog = false })
    if (showBitrateDialog) OptionsDialog("Bitrate (Mbps)", listOf("25", "50", "100"), videoSettings.bitrateMbps.toString(), { viewModel.setVideoBitrate(it.toInt()); showBitrateDialog = false }, { showBitrateDialog = false })
    if (showCodecDialog) OptionsDialog("Codec de Vídeo", listOf("H.264", "H.265"), videoSettings.codec, { viewModel.setVideoCodec(it); showCodecDialog = false }, { showCodecDialog = false })
    
    if (showZebraDialog) OptionsDialog("Limite da Zebra", listOf("70", "80", "90", "100"), "${monitorSettings.zebraThreshold}", { viewModel.setZebraThreshold(it.toInt()); showZebraDialog = false }, { showZebraDialog = false })
    if (showPeakingColorDialog) OptionsDialog("Cor Focus Peaking", listOf("Red", "Green", "Blue", "White"), monitorSettings.focusPeakingColor, { viewModel.setFocusPeakingColor(it); showPeakingColorDialog = false }, { showPeakingColorDialog = false })
    if (showPeakingSensitivityDialog) OptionsDialog("Sensibilidade Focus", listOf("Low", "Medium", "High"), monitorSettings.focusPeakingSensitivity, { viewModel.setFocusPeakingSensitivity(it); showPeakingSensitivityDialog = false }, { showPeakingSensitivityDialog = false })

    if (showMicrophoneDialog) {
        val inputDevices = availableAudioDevices.filter { it.isSource }
        val deviceNames = inputDevices.map { getAudioDeviceName(it) }
        val currentName = getAudioDeviceName(selectedAudioDevice)
        OptionsDialog("Selecionar Microfone", deviceNames, currentName, { selectedName ->
            val selectedDevice = inputDevices.find { getAudioDeviceName(it) == selectedName }
            if (selectedDevice != null) viewModel.selectAudioDevice(selectedDevice)
            showMicrophoneDialog = false
        }, { showMicrophoneDialog = false })
    }



    Scaffold(
        containerColor = Color(0xFF0A0A0A),
        topBar = { 
            TopAppBar(
                title = { Text("Configurações", color = Color.White, fontWeight = FontWeight.Bold) }, 
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            ) 
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            SettingsSection("Áudio") {
                SettingsItem(Icons.Filled.Mic, "Microfone de Entrada", getAudioDeviceName(selectedAudioDevice), Color(0xFF4CAF50)) { showMicrophoneDialog = true }
            }

            SettingsSection("Vídeo / Gravação") {
                SettingsItem(Icons.Filled.Usb, "Fonte de Vídeo", videoSettings.videoSource, Color(0xFF2979FF)) { showSourceDialog = true }
                SettingsDivider()
                
                val displayFolder = videoSettings.recordingDirectoryUri?.let { uriString ->
                    try {
                        android.net.Uri.parse(uriString).lastPathSegment?.substringAfterLast(":")?.substringAfterLast("/") ?: "Movies/BDSM"
                    } catch (e: Exception) {
                        "Movies/BDSM"
                    }
                } ?: "Movies/BDSM"
                
                SettingsItem(Icons.Filled.Folder, "Local de Salvamento", displayFolder, Color(0xFF4CAF50)) { folderLauncher.launch(null) }
                SettingsDivider()
                SettingsSwitchItem(Icons.Filled.PhotoLibrary, "Salvar na Galeria", videoSettings.saveToGallery, Color(0xFFE91E63)) { viewModel.setSaveToGallery(it) }
                SettingsDivider()
                SettingsItem(Icons.Filled.Videocam, "Resolução (Gravação)", videoSettings.resolution, Color(0xFF2979FF)) { showResolutionDialog = true }
                SettingsDivider()
                SettingsItem(Icons.Filled.Speed, "Taxa de Quadros (FPS)", "${videoSettings.fps} FPS", Color(0xFF2979FF)) { showFpsDialog = true }
                SettingsDivider()
                SettingsItem(Icons.Filled.HighQuality, "Codec", videoSettings.codec, Color(0xFF2979FF)) { showCodecDialog = true }
                SettingsDivider()
                SettingsItem(Icons.Filled.Tune, "Bitrate", "${videoSettings.bitrateMbps} Mbps", Color(0xFF2979FF)) { showBitrateDialog = true }
            }

            SettingsSection("Monitoramento") {
                SettingsItem(Icons.Filled.Gradient, "Limite da Zebra", "${monitorSettings.zebraThreshold}", Color(0xFF9C27B0)) { showZebraDialog = true }
                SettingsDivider()
                SettingsItem(Icons.Filled.ColorLens, "Cor Focus Peaking", monitorSettings.focusPeakingColor, Color(0xFF9C27B0)) { showPeakingColorDialog = true }
                SettingsDivider()
                SettingsItem(Icons.Filled.Sensors, "Sensibilidade Focus", monitorSettings.focusPeakingSensitivity, Color(0xFF9C27B0)) { showPeakingSensitivityDialog = true }
            }

            SettingsSection("Sistema") {
                SettingsItem(Icons.Filled.Build, "Diagnóstico de Hardware", "Câmeras e Sensores", Color(0xFFFF9800)) {
                    onNavigateToDiagnostics()
                }
            }

            SettingsSection("Sobre") {
                SettingsItem(Icons.Filled.Info, "Versão", "$appVersionName (Code: $appVersionCode)", Color(0xFF9E9E9E)) {
                    val now = System.currentTimeMillis()
                    if (now - lastAboutClickTime < 500) {
                        aboutClickCount++
                    } else {
                        aboutClickCount = 1
                    }
                    lastAboutClickTime = now
                    if (aboutClickCount == 5) {
                        aboutClickCount = 0
                        onNavigateToEasterEgg()
                    }
                }
            }
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}