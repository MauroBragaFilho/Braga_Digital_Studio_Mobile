package com.bragastudio.mobile.featuresettings

import android.media.AudioDeviceInfo
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bragastudio.mobile.common.components.BdsmLargeTitleScaffold
import com.bragastudio.mobile.common.components.ChoiceDialog
import com.bragastudio.mobile.common.components.ConfirmDialog
import com.bragastudio.mobile.common.components.SegmentedChoice
import com.bragastudio.mobile.common.components.SettingsDivider
import com.bragastudio.mobile.common.components.SettingsHelpText
import com.bragastudio.mobile.common.components.SettingsItem
import com.bragastudio.mobile.common.components.SettingsSection
import com.bragastudio.mobile.common.components.SettingsSwitchItem
import com.bragastudio.mobile.common.module.LocalModuleHost
import com.bragastudio.mobile.common.module.ModulePlacement
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import com.bragastudio.mobile.core.domain.LandscapeNavSide
import com.bragastudio.mobile.core.domain.ThemeMode

/** Navegação que a tela de uma categoria pode pedir (rotas ficam no módulo, não aqui). */
data class SettingsNavigation(
    val onUp: () -> Unit = {},
    val onEasterEgg: () -> Unit = {},
    val onDiagnostics: () -> Unit = {},
    val onNdi: () -> Unit = {},
    val onNdiAdvanced: () -> Unit = {},
    val onLicenses: () -> Unit = {},
    val onOnboarding: () -> Unit = {},
)

/**
 * Tela de UMA categoria de Configurações (UX v3): título da categoria + os itens que já existiam,
 * agora agrupados por assunto. As opções técnicas de gravação ficam em "Avançado" (recolhido).
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // uma tela, muitos diálogos/seções declarativos
@Composable
fun SettingsCategoryScreen(
    category: SettingsCategory,
    navigation: SettingsNavigation,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val video by viewModel.video.collectAsStateWithLifecycle()
    val monitor by viewModel.monitor.collectAsStateWithLifecycle()
    val audio by viewModel.audio.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val connectivity by viewModel.connectivity.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle()

    var advancedOpen by rememberSaveable { mutableStateOf(false) }

    var showMicrophoneDialog by remember { mutableStateOf(false) }
    // Android 12+: microfone Bluetooth (SCO) exige BLUETOOTH_CONNECT em tempo de execução; pedimos só aqui,
    // quando o usuário escolhe um dispositivo Bluetooth (sem pedir no primeiro uso do app).
    val btDeniedMessage = stringResource(R.string.settings_bt_permission_denied)
    val btPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(context, btDeniedMessage, Toast.LENGTH_LONG).show()
        }
    }
    var showResolutionDialog by remember { mutableStateOf(false) }
    var showFpsDialog by remember { mutableStateOf(false) }
    var showBitrateDialog by remember { mutableStateOf(false) }
    var showCodecDialog by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }
    var showDestinationDialog by remember { mutableStateOf(false) }
    var showRestoreStorageDialog by remember { mutableStateOf(false) }
    var showZebraDialog by remember { mutableStateOf(false) }
    var showDisplayNameDialog by rememberSaveable { mutableStateOf(false) }
    var showPeakingColorDialog by remember { mutableStateOf(false) }
    var showPeakingSensitivityDialog by remember { mutableStateOf(false) }

    var aboutClickCount by remember { mutableIntStateOf(0) }
    var lastAboutClickTime by remember { mutableLongStateOf(0L) }

    val appVersionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }

    // Ao abrir Gravação: confere se a pasta escolhida ainda existe e é gravável (fallback automático para o app).
    LaunchedEffect(category) { if (category == SettingsCategory.RECORDING) viewModel.validateStorage() }

    // Mensagens pontuais do ViewModel (falha de permissão, fallback de pasta...).
    LaunchedEffect(viewModel) {
        viewModel.events.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    // takePersistableUriPermission/release e a validação acontecem no ViewModel (try/catch + IO).
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) viewModel.onFolderPicked(uri)
    }

    // Rótulos únicos por dispositivo: dois microfones do mesmo tipo (ex.: 2 USB) não colidem.
    val inputDevices = remember(audio.devices) { audio.devices.filter { it.isSource } }
    val audioLabels: Map<Int, String> = remember(inputDevices) { buildAudioLabels(inputDevices) }
    fun audioLabel(device: AudioDeviceInfo?): String = if (device == null) audioBaseName(null) else audioLabels[device.id] ?: audioBaseName(device)

    // ----------------------------------------------------------------- diálogos
    if (showResolutionDialog) {
        ChoiceDialog(
            stringResource(R.string.settings_dialog_resolution), Resolution.entries.toList(), video.resolution, { it.label },
            {
                viewModel.setVideoResolution(it)
                showResolutionDialog = false
            }, { showResolutionDialog = false },
        )
    }
    if (showSourceDialog) {
        val usbSourceHint = stringResource(R.string.settings_source_usb_hint)
        ChoiceDialog(
            stringResource(R.string.settings_dialog_source), VideoSourceOption.available(), video.videoSource, { it.label },
            {
                viewModel.setVideoSource(it)
                showSourceDialog = false
            }, { showSourceDialog = false },
            description = { if (it == VideoSourceOption.USB) usbSourceHint else null },
        )
    }
    if (showFpsDialog) {
        ChoiceDialog(
            stringResource(R.string.settings_dialog_fps), Fps.entries.toList(), video.fpsOption, { it.label },
            {
                viewModel.setVideoFps(it)
                showFpsDialog = false
            }, { showFpsDialog = false },
        )
    }
    if (showBitrateDialog) {
        val bitrateHints = mapOf(
            25 to stringResource(R.string.settings_bitrate_hint_25),
            50 to stringResource(R.string.settings_bitrate_hint_50),
            100 to stringResource(R.string.settings_bitrate_hint_100),
        )
        ChoiceDialog(
            stringResource(R.string.settings_dialog_bitrate), NumericOptions.BITRATES_MBPS, video.bitrateMbps, { "$it Mbps" },
            {
                viewModel.setVideoBitrate(it)
                showBitrateDialog = false
            }, { showBitrateDialog = false },
            description = { bitrateHints[it] },
        )
    }
    if (showCodecDialog) {
        val h264 = stringResource(R.string.settings_codec_hint_h264)
        val h265 = stringResource(R.string.settings_codec_hint_h265)
        ChoiceDialog(
            stringResource(R.string.settings_dialog_codec), Codec.entries.toList(), video.codec, { it.label },
            {
                viewModel.setVideoCodec(it)
                showCodecDialog = false
            }, { showCodecDialog = false },
            description = { if (it == Codec.H264) h264 else h265 },
        )
    }
    if (showDisplayNameDialog) {
        DisplayNameDialog(
            current = appearance.displayName,
            deviceName = appearance.deviceName,
            onConfirm = {
                viewModel.setDisplayName(it)
                showDisplayNameDialog = false
            },
            onDismiss = { showDisplayNameDialog = false },
        )
    }
    if (showZebraDialog) {
        ChoiceDialog(
            stringResource(R.string.settings_dialog_zebra), NumericOptions.ZEBRA_THRESHOLDS, monitor.zebraThreshold, { "$it" },
            {
                viewModel.setZebraThreshold(it)
                showZebraDialog = false
            }, { showZebraDialog = false },
        )
    }
    if (showPeakingColorDialog) {
        ChoiceDialog(
            stringResource(R.string.settings_dialog_peaking_color), PeakingColor.entries.toList(), monitor.peakingColor, { it.label },
            {
                viewModel.setFocusPeakingColor(it)
                showPeakingColorDialog = false
            }, { showPeakingColorDialog = false },
        )
    }
    if (showPeakingSensitivityDialog) {
        ChoiceDialog(
            stringResource(R.string.settings_dialog_peaking_sensitivity), PeakingSensitivity.entries.toList(), monitor.peakingSensitivity, { it.label },
            {
                viewModel.setFocusPeakingSensitivity(it)
                showPeakingSensitivityDialog = false
            }, { showPeakingSensitivityDialog = false },
        )
    }
    if (showMicrophoneDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_dialog_mic),
            options = inputDevices,
            // Comparação por id do dispositivo, não por nome de exibição.
            selected = inputDevices.firstOrNull { it.id == audio.selected?.id },
            label = { audioLabel(it) },
            onSelect = {
                viewModel.selectAudioDevice(it)
                if (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO &&
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.BLUETOOTH_CONNECT,
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    btPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                }
                showMicrophoneDialog = false
            },
            onDismiss = { showMicrophoneDialog = false },
        )
    }
    if (showDestinationDialog) {
        ChoiceDialog(
            title = stringResource(R.string.settings_dialog_destination),
            options = StorageDestination.entries.toList(),
            selected = storage.destination,
            label = { destinationOptionLabel(it, storage.galleryAvailable) },
            onSelect = { dest ->
                showDestinationDialog = false
                if (dest == StorageDestination.FOLDER) {
                    folderLauncher.launch(null)
                } else {
                    viewModel.selectDestination(dest)
                }
            },
            onDismiss = { showDestinationDialog = false },
            enabledOf = { it != StorageDestination.GALLERY || storage.galleryAvailable },
        )
    }
    if (showRestoreStorageDialog) {
        ConfirmDialog(
            title = stringResource(R.string.settings_dialog_restore_title),
            message = stringResource(R.string.settings_dialog_restore_message),
            confirmText = stringResource(R.string.settings_dialog_restore_confirm),
            destructive = false,
            onConfirm = {
                viewModel.restoreStorageDefaults()
                showRestoreStorageDialog = false
            },
            onDismiss = { showRestoreStorageDialog = false },
        )
    }

    val preset = QualityPreset.detect(video.resolution, video.fps, video.codec, video.bitrateMbps)
    val extraModules = LocalModuleHost.current.registry.at(ModulePlacement.SETTINGS_ADVANCED)

    // LazyColumn: só as seções visíveis são compostas/medidas na entrada da tela.
    // Subtela: o app bar expansível começa RECOLHIDO (guia One UI) e pode ser expandido ao rolar para baixo.
    BdsmLargeTitleScaffold(
        title = stringResource(settingsCategoryMeta(category).titleRes),
        onNavigateUp = navigation.onUp,
        startCollapsed = true,
    ) {
        when (category) {
            SettingsCategory.CAMERA -> cameraItems(
                video = video,
                onSource = { showSourceDialog = true },
                onResolution = { showResolutionDialog = true },
                onFps = { showFpsDialog = true },
            )

            SettingsCategory.AUDIO -> item(key = "audio", contentType = "section") {
                SettingsSection(stringResource(R.string.settings_section_audio), icon = Icons.Filled.Mic) {
                    SettingsItem(
                        icon = Icons.Filled.Mic,
                        title = stringResource(R.string.settings_mic_input),
                        value = audioLabel(audio.selected),
                    ) { showMicrophoneDialog = true }
                    SettingsDivider()
                    SettingsHelpText(stringResource(R.string.settings_audio_help))
                }
            }

            SettingsCategory.MONITOR -> item(key = "monitor", contentType = "section") {
                SettingsSection(stringResource(R.string.settings_section_monitoring), icon = Icons.Filled.Visibility) {
                    SettingsItem(
                        icon = Icons.Filled.Gradient,
                        title = stringResource(R.string.settings_zebra),
                        subtitle = stringResource(R.string.settings_zebra_sub),
                        value = "${monitor.zebraThreshold}",
                        monoValue = true,
                    ) { showZebraDialog = true }
                    SettingsDivider()
                    SettingsItem(
                        icon = Icons.Filled.ColorLens,
                        title = stringResource(R.string.settings_peaking_color),
                        subtitle = stringResource(R.string.settings_peaking_sub),
                        value = monitor.peakingColor.label,
                    ) { showPeakingColorDialog = true }
                    SettingsDivider()
                    SettingsItem(
                        icon = Icons.Filled.Sensors,
                        title = stringResource(R.string.settings_peaking_sensitivity),
                        value = monitor.peakingSensitivity.label,
                    ) { showPeakingSensitivityDialog = true }
                    SettingsDivider()
                    SettingsHelpText(stringResource(R.string.settings_monitor_help))
                }
            }

            SettingsCategory.NDI -> item(key = "ndi", contentType = "section") {
                SettingsSection(stringResource(R.string.settings_section_stream), icon = Icons.Filled.Router) {
                    SettingsItem(
                        icon = Icons.Filled.Router,
                        title = stringResource(R.string.settings_ndi),
                        subtitle = stringResource(if (connectivity.ndiEnabled) R.string.settings_ndi_sub_on else R.string.settings_ndi_sub_off),
                        value = stringResource(if (connectivity.ndiEnabled) R.string.settings_ndi_on else R.string.settings_ndi_off),
                    ) { navigation.onNdi() }
                    SettingsDivider()
                    SettingsItem(
                        icon = Icons.Filled.Tune,
                        title = stringResource(R.string.ndi3_advanced),
                        subtitle = stringResource(R.string.ndi_advanced_sub),
                    ) { navigation.onNdiAdvanced() }
                }
            }

            SettingsCategory.RECORDING -> recordingItems(
                video = video,
                storage = storage,
                preset = preset,
                advancedOpen = advancedOpen,
                onToggleAdvanced = { advancedOpen = !advancedOpen },
                onPreset = viewModel::applyQualityPreset,
                onCodec = { showCodecDialog = true },
                onBitrate = { showBitrateDialog = true },
                onHdr = viewModel::setVideoHdrEnabled,
                onDestination = { showDestinationDialog = true },
                onRestore = { showRestoreStorageDialog = true },
            )

            SettingsCategory.APP -> {
                item(key = "display-name", contentType = "section") {
                    SettingsSection(stringResource(R.string.settings_profile), icon = Icons.Filled.Person) {
                        SettingsItem(
                            icon = Icons.Filled.Person,
                            title = stringResource(R.string.settings_display_name),
                            subtitle = stringResource(R.string.settings_display_name_sub),
                            value = appearance.displayName.ifEmpty { appearance.deviceName },
                        ) { showDisplayNameDialog = true }
                    }
                }
                item(key = "theme", contentType = "section") {
                    SettingsSection(stringResource(R.string.settings_theme), icon = Icons.Filled.Palette) {
                        Column(modifier = Modifier.padding(BdsmTheme.spacing.lg)) {
                            SegmentedChoice(
                                options = ThemeMode.entries.toList(),
                                selected = appearance.themeMode,
                                label = { stringResource(themeModeLabelRes(it)) },
                                onSelect = viewModel::setThemeMode,
                                icon = { themeModeIcon(it) },
                            )
                        }
                        if (appearance.dynamicColorAvailable) {
                            SettingsDivider()
                            SettingsSwitchItem(
                                icon = Icons.Filled.ColorLens,
                                title = stringResource(R.string.settings_dynamic_color),
                                checked = appearance.dynamicColor,
                                subtitle = stringResource(R.string.settings_dynamic_color_sub),
                            ) { viewModel.setDynamicColorEnabled(it) }
                        }
                    }
                }
                item(key = "landscape-nav", contentType = "section") {
                    SettingsSection(stringResource(R.string.settings_landscape_nav), icon = Icons.Filled.Palette) {
                        Column(modifier = Modifier.padding(BdsmTheme.spacing.lg)) {
                            SegmentedChoice(
                                options = LandscapeNavSide.entries.toList(),
                                selected = appearance.landscapeNavSide,
                                label = { stringResource(landscapeNavSideLabelRes(it)) },
                                onSelect = viewModel::setLandscapeNavSide,
                            )
                        }
                    }
                }
                // Conteúdo contribuído por módulos (ex.: BDSM Link / OBS): gerado pelo registro.
                if (extraModules.isNotEmpty()) {
                    item(key = "modules", contentType = "section") {
                        Column(verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.xl)) {
                            extraModules.forEach { module -> module.SettingsContent() }
                        }
                    }
                }
                item(key = "system", contentType = "section") {
                    SettingsSection(stringResource(R.string.settings_section_system), icon = Icons.Filled.Build) {
                        SettingsItem(
                            icon = Icons.Filled.Build,
                            title = stringResource(R.string.settings_diagnostics),
                            subtitle = stringResource(R.string.settings_diagnostics_sub),
                        ) { navigation.onDiagnostics() }
                        SettingsDivider()
                        SettingsItem(
                            icon = Icons.Filled.VerifiedUser,
                            title = stringResource(R.string.settings_permissions),
                            subtitle = stringResource(R.string.settings_permissions_sub),
                        ) { navigation.onOnboarding() }
                    }
                }
            }

            SettingsCategory.ABOUT -> item(key = "about", contentType = "section") {
                SettingsSection(stringResource(R.string.settings_section_about), icon = Icons.Filled.Info) {
                    SettingsItem(
                        icon = Icons.Filled.Info,
                        title = stringResource(R.string.settings_version),
                        value = appVersionName,
                        monoValue = true,
                    ) {
                        val now = System.currentTimeMillis()
                        aboutClickCount = if (now - lastAboutClickTime < 500) aboutClickCount + 1 else 1
                        lastAboutClickTime = now
                        if (aboutClickCount == 5) {
                            aboutClickCount = 0
                            navigation.onEasterEgg()
                        }
                    }
                    SettingsDivider()
                    SettingsItem(
                        icon = Icons.Filled.Description,
                        title = stringResource(R.string.settings_licenses),
                        subtitle = stringResource(R.string.settings_licenses_sub),
                    ) { navigation.onLicenses() }
                }
            }
        }
    }
}

private fun LazyListScope.cameraItems(
    video: VideoUiState,
    onSource: () -> Unit,
    onResolution: () -> Unit,
    onFps: () -> Unit,
) {
    item(key = "camera", contentType = "section") {
        SettingsSection(stringResource(R.string.settings_section_camera), icon = Icons.Filled.Videocam) {
            SettingsItem(
                icon = Icons.Filled.Usb,
                title = stringResource(R.string.settings_video_source),
                value = video.videoSource.label,
            ) { onSource() }
            // Câmeras com saída HDMI (Sony a6000 etc.) entram por esta fonte, via placa de captura UVC.
            if (video.videoSource == VideoSourceOption.USB) {
                SettingsHelpText(stringResource(R.string.settings_source_usb_help))
            }
            SettingsDivider()
            SettingsItem(icon = Icons.Filled.Videocam, title = stringResource(R.string.settings_resolution), value = video.resolution.label) { onResolution() }
            SettingsDivider()
            SettingsItem(icon = Icons.Filled.Speed, title = stringResource(R.string.settings_fps), value = "${video.fps}", monoValue = true) { onFps() }
        }
    }
}

@Suppress("LongParameterList")
private fun LazyListScope.recordingItems(
    video: VideoUiState,
    storage: StorageUiState,
    preset: QualityPreset?,
    advancedOpen: Boolean,
    onToggleAdvanced: () -> Unit,
    onPreset: (QualityPreset) -> Unit,
    onCodec: () -> Unit,
    onBitrate: () -> Unit,
    onHdr: (Boolean) -> Unit,
    onDestination: () -> Unit,
    onRestore: () -> Unit,
) {
    item(key = "quality", contentType = "section") {
        SettingsSection(stringResource(R.string.settings_section_quality), icon = Icons.Filled.HighQuality) {
            QualityEssential(video = video, preset = preset, onPreset = onPreset)
        }
    }
    item(key = "where", contentType = "section") {
        SettingsSection(stringResource(R.string.settings_section_storage), icon = Icons.Filled.Folder) {
            SettingsItem(
                icon = Icons.Filled.Folder,
                title = stringResource(R.string.settings_storage_destination),
                value = destinationShort(storage),
            ) { onDestination() }
            if (storage.destination == StorageDestination.FOLDER && storage.folderStatus == FolderStatus.UNAVAILABLE) {
                SettingsHelpText(stringResource(R.string.settings_storage_folder_unavailable), MaterialTheme.colorScheme.error)
            }
        }
    }
    item(key = "advanced-toggle", contentType = "section") {
        SettingsSection(stringResource(R.string.settings_advanced), icon = Icons.Filled.Tune) {
            SettingsItem(
                icon = Icons.Filled.Tune,
                title = stringResource(R.string.settings_advanced_open),
                subtitle = stringResource(R.string.settings_advanced_rec_sub),
                trailingIcon = if (advancedOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            ) { onToggleAdvanced() }
        }
    }
    if (advancedOpen) {
        item(key = "advanced-video", contentType = "section") {
            SettingsSection(stringResource(R.string.settings_section_video_adv), icon = Icons.Filled.Videocam) {
                AdvancedVideoRows(video = video, onCodec = onCodec, onBitrate = onBitrate, onHdr = onHdr)
            }
        }
        item(key = "advanced-storage", contentType = "section") {
            SettingsSection(stringResource(R.string.settings_section_storage_details), icon = Icons.Filled.Folder) {
                SettingsHelpText(storageHelp(storage))
                SettingsDivider()
                SettingsItem(
                    icon = Icons.Filled.RestartAlt,
                    title = stringResource(R.string.settings_storage_restore),
                    subtitle = stringResource(R.string.settings_storage_restore_sub),
                ) { onRestore() }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Qualidade da gravação: presets primeiro, opções técnicas em "Avançado" (recolhido).
// ---------------------------------------------------------------------------

@Composable
private fun QualityEssential(video: VideoUiState, preset: QualityPreset?, onPreset: (QualityPreset) -> Unit) {
    Column(
        modifier = Modifier.padding(BdsmTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(BdsmTheme.spacing.md),
    ) {
        SegmentedChoice<QualityPreset?>(
            options = QualityPreset.entries.toList(),
            selected = preset,
            label = { stringResource(presetLabelRes(it!!)) },
            onSelect = { it?.let(onPreset) },
        )
        // Valor atual sempre visível, em linguagem simples: "Equilibrada · 1080p · 30 fps".
        val summary = if (preset != null) {
            stringResource(presetLabelRes(preset)) + " · " + preset.shortFormat
        } else {
            stringResource(R.string.settings_quality_custom) + " · " + QualityText.format(video.resolution, video.fps)
        }
        Text(text = summary, style = BdsmTheme.type.metric, color = MaterialTheme.colorScheme.onSurface)
        Text(
            text = if (preset != null) stringResource(presetDescriptionRes(preset)) else stringResource(R.string.settings_quality_custom_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Opções técnicas de gravação (só em Avançado): codec, taxa de dados e HDR. */
@Composable
private fun AdvancedVideoRows(
    video: VideoUiState,
    onCodec: () -> Unit,
    onBitrate: () -> Unit,
    onHdr: (Boolean) -> Unit,
) {
    SettingsItem(
        icon = Icons.Filled.HighQuality,
        title = stringResource(R.string.settings_codec),
        subtitle = stringResource(if (video.codec == Codec.H264) R.string.settings_codec_hint_h264 else R.string.settings_codec_hint_h265),
        value = video.codec.persisted,
        monoValue = true,
    ) { onCodec() }
    SettingsDivider()
    SettingsItem(
        icon = Icons.Filled.Tune,
        title = stringResource(R.string.settings_bitrate),
        subtitle = stringResource(R.string.settings_quality_size, QualityText.mbPerMinute(video.bitrateMbps)),
        value = "${video.bitrateMbps} Mbps",
        monoValue = true,
    ) { onBitrate() }
    SettingsDivider()
    // Preferência padrão de HDR (HLG10). Só habilitável com H.265 (mesma regra do HUD do Preview).
    SettingsSwitchItem(
        icon = Icons.Filled.HdrOn,
        title = stringResource(R.string.settings_hdr),
        checked = video.hdrEnabled,
        enabled = video.hdrAvailable,
        subtitle = stringResource(if (video.hdrAvailable) R.string.settings_hdr_available else R.string.settings_hdr_requires_h265),
    ) { onHdr(it) }
}
