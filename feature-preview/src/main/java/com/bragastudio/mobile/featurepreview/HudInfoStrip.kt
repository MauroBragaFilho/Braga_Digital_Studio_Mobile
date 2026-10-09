package com.bragastudio.mobile.featurepreview

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.HdrOff
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.bragastudio.mobile.core.domain.VideoSettings
import com.bragastudio.mobile.core.domain.VideoSources
import com.bragastudio.mobile.corecapture.domain.CameraInfoModel

private val ResolutionOptions = listOf("1080p", "1440p", "4K")
private val FpsOptions = listOf(24, 30, 60)
private val BitrateOptions = listOf(25, 50, 100)
private val CodecOptions = listOf("H.265", "H.264")

// "SONY" só entra com a fonte Sony Wi-Fi ligada (CaptureFeatureFlags); sem ela o seletor oferece Camera e USB.
private val SourceOptions = VideoSources.available()

/** Gradiente translúcido da faixa (sobre a imagem, sem faixa preta sólida). */
private val StripBrush = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.62f), Color.Black.copy(alpha = 0.0f)))

/**
 * Tile de texto tocável da faixa de informações (estilo câmera de cinema): prefixo
 * discreto + valor. AUTO em tom discreto, manual em âmbar. Alvo de toque de 48 dp.
 */
@Composable
internal fun HudTile(
    value: String,
    spokenName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    prefix: String? = null,
    isManual: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
) {
    val valueColor = if (isManual) ModernHudTheme.accent else Color.White.copy(alpha = 0.72f)
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .hudClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                enabled = enabled,
                onClickLabel = "Ajustar $spokenName",
            )
            .semantics(mergeDescendants = true) {
                contentDescription = tileSpokenLabel(spokenName, value)
                stateDescription = if (isManual) "Manual" else "Automático"
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.28f))
                .border(1.dp, if (isManual) ModernHudTheme.accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            if (prefix != null) {
                Text(prefix, color = Color.White.copy(alpha = 0.5f), fontSize = HudTheme.fontSizeMin, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
            }
            Text(
                value,
                color = valueColor,
                fontSize = HudTheme.fontSizeNormal,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Faixa de informações SEMPRE visível: REC + timecode, tiles (formato + exposição),
 * armazenamento, bateria e overflow (⋮). Em largura >= 600 dp tudo vai numa linha só;
 * em telas estreitas, em duas (status em cima, tiles embaixo). Só esmaece levemente
 * ([dimmed]) quando ocioso — nunca se oculta.
 */
@Composable
internal fun HudInfoStrip(
    singleRow: Boolean,
    dimmed: Boolean,
    isRecording: Boolean,
    timeProvider: () -> Long,
    videoSettings: VideoSettings,
    isWifiConnected: Boolean,
    storageFreeGB: Float,
    batteryPercentage: Int,
    isNdiEnabled: Boolean,
    onNdiToggle: () -> Unit,
    currentLens: CameraInfoModel?,
    isTorchEnabled: Boolean,
    isStabilizationEnabled: Boolean,
    isHdrEnabled: Boolean,
    selectedAudioDeviceName: String,
    availableAudioDevices: List<android.media.AudioDeviceInfo>,
    onToggleTorch: () -> Unit,
    onToggleStabilization: () -> Unit,
    onToggleHdr: () -> Unit,
    onSetCameraSource: (String) -> Unit,
    onSelectAudioDevice: (android.media.AudioDeviceInfo) -> Unit,
    onSetResolution: (String) -> Unit,
    onSetFps: (Int) -> Unit,
    onSetBitrate: (Int) -> Unit,
    onSetCodec: (String) -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onHideInterface: () -> Unit,
    onPopoverOpenChanged: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    exposureTiles: @Composable RowScope.() -> Unit = {},
) {
    val alpha by animateFloatAsState(if (dimmed) 0.62f else 1f, tween(400), label = "stripAlpha")
    val fps = videoSettings.fps

    val status: @Composable () -> Unit = {
        RecBlock(isRecording = isRecording, timeProvider = timeProvider, fps = fps)
    }
    val tiles: @Composable (Modifier) -> Unit = { m ->
        Row(
            modifier = m.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FormatTile(
                videoSettings = videoSettings,
                onSetResolution = onSetResolution,
                onSetFps = onSetFps,
                onSetBitrate = onSetBitrate,
                onSetCodec = onSetCodec,
                onPopoverOpenChanged = onPopoverOpenChanged,
            )
            exposureTiles()
        }
    }
    val right: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isWifiConnected) {
                Icon(
                    Icons.Filled.WifiOff,
                    contentDescription = "Wi-Fi desconectado",
                    tint = Color(0xFFFF453A),
                    modifier = Modifier.drawIconOutline().size(16.dp),
                )
            }
            if (isNdiEnabled) NdiChip(onNdiToggle)
            StorageAndBattery(storageFreeGB, videoSettings.bitrateMbps, batteryPercentage)
            OverflowMenuButton(
                isNdiEnabled = isNdiEnabled,
                onNdiToggle = onNdiToggle,
                videoSettings = videoSettings,
                currentLens = currentLens,
                isRecording = isRecording,
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
                onNavigateHome = onNavigateHome,
                onNavigateToSettings = onNavigateToSettings,
                onHideInterface = onHideInterface,
                onPopoverOpenChanged = onPopoverOpenChanged,
            )
        }
    }

    Box(modifier = modifier.fillMaxWidth().background(StripBrush).alpha(alpha)) {
        if (singleRow) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                status()
                tiles(Modifier.weight(1f).padding(horizontal = 12.dp))
                right()
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    status()
                    Spacer(Modifier.weight(1f))
                    right()
                }
                tiles(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RecBlock(isRecording: Boolean, timeProvider: () -> Long, fps: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Estado da gravação"
                stateDescription = if (isRecording) HudStrings.REC_STATE_ON else HudStrings.REC_STATE_OFF
            },
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (isRecording) ModernHudTheme.recRed else Color.White.copy(alpha = 0.35f)),
        )
        TimecodeText(
            timeProvider = timeProvider,
            fps = fps,
            fontSize = 16.sp,
            color = if (isRecording) Color.White else Color.White.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun NdiChip(onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .hudClickable(onClick = onToggle, role = Role.Switch, onClickLabel = "Desligar NDI")
            .semantics(mergeDescendants = true) {
                contentDescription = "NDI"
                stateDescription = HudStrings.STATE_ON
            },
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(HudTheme.ndiActiveColor))
        Text("NDI", color = HudTheme.ndiActiveColor, fontWeight = FontWeight.Bold, fontSize = HudTheme.fontSizeMin)
    }
}

@Composable
private fun StorageAndBattery(storageFreeGB: Float, bitrateMbps: Int, batteryPercentage: Int) {
    val remaining = formatRecordingTimeRemaining(storageFreeGB, bitrateMbps)
    val lowStorage = storageFreeGB <= 5f
    val lowBattery = batteryPercentage <= 20
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Tempo de gravação restante $remaining" },
        ) {
            Icon(
                Icons.Filled.Storage,
                contentDescription = null,
                tint = if (lowStorage) Color(0xFFFF453A) else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.drawIconOutline().size(13.dp),
            )
            Text(
                remaining,
                color = if (lowStorage) Color(0xFFFF453A) else Color.White,
                fontSize = HudTheme.fontSizeNormal,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Bateria do celular $batteryPercentage por cento" },
        ) {
            Box(
                modifier = Modifier
                    .size(width = 16.dp, height = 8.dp)
                    .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                    .padding(1.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((batteryPercentage / 100f).coerceIn(0.05f, 1f))
                        .background(if (lowBattery) Color(0xFFFF453A) else Color(0xFF32D74B), RoundedCornerShape(1.dp)),
                )
            }
            Text(
                batteryLabel(batteryPercentage),
                color = if (lowBattery) Color(0xFFFF453A) else Color.White,
                fontSize = HudTheme.fontSizeNormal,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** Tile de formato (`4K · 30 · H.265`): abre um painel com resolução/fps/bitrate/codec. */
@Composable
private fun FormatTile(
    videoSettings: VideoSettings,
    onSetResolution: (String) -> Unit,
    onSetFps: (Int) -> Unit,
    onSetBitrate: (Int) -> Unit,
    onSetCodec: (String) -> Unit,
    onPopoverOpenChanged: (String, Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    PopoverReporter("format", open, onPopoverOpenChanged)
    val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    val position = remember(gapPx) { HudPopupPositionProvider(HudPopupSide.BELOW_START, gapPx) }
    Box {
        HudTile(
            value = formatTileLabel(videoSettings.resolution, videoSettings.fps, videoSettings.codec),
            spokenName = "Formato de gravação",
            isManual = false,
            onClick = { open = true },
        )
        if (open) {
            Popup(popupPositionProvider = position, onDismissRequest = { open = false }) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ModernHudTheme.panelBg)
                        .border(1.dp, ModernHudTheme.panelBorder, RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ChipRow("RESOLUÇÃO", ResolutionOptions, { it }, { it == videoSettings.resolution }, onSetResolution)
                    ChipRow("FPS", FpsOptions, { it.toString() }, { it == videoSettings.fps }, onSetFps)
                    ChipRow("BITRATE (Mb/s)", BitrateOptions, { it.toString() }, { it == videoSettings.bitrateMbps }, onSetBitrate)
                    ChipRow("CODEC", CodecOptions, { it }, { it == videoSettings.codec }, onSetCodec)
                }
            }
        }
    }
}

@Composable
private fun <T> ChipRow(title: String, options: List<T>, label: (T) -> String, isSelected: (T) -> Boolean, onSelect: (T) -> Unit) {
    Column {
        Text(title, color = Color.White.copy(alpha = 0.5f), fontSize = HudTheme.fontSizeMin, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                val selected = isSelected(option)
                Box(
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .hudClickable(onClick = { onSelect(option) }, role = Role.RadioButton)
                        .semantics { stateDescription = if (selected) "Selecionado" else "" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        color = if (selected) Color.Black else Color.White,
                        fontSize = HudTheme.fontSizeNormal,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) ModernHudTheme.accent else Color.White.copy(alpha = 0.10f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** Informa ao HUD (para o auto-ocultar do dock) que um popover está aberto. */
@Composable
internal fun PopoverReporter(key: String, open: Boolean, report: (String, Boolean) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(open) { report(key, open) }
    DisposableEffect(Unit) { onDispose { report(key, false) } }
}

@Composable
private fun OverflowMenuButton(
    isNdiEnabled: Boolean,
    onNdiToggle: () -> Unit,
    videoSettings: VideoSettings,
    currentLens: CameraInfoModel?,
    isRecording: Boolean,
    isTorchEnabled: Boolean,
    isStabilizationEnabled: Boolean,
    isHdrEnabled: Boolean,
    selectedAudioDeviceName: String,
    availableAudioDevices: List<android.media.AudioDeviceInfo>,
    onToggleTorch: () -> Unit,
    onToggleStabilization: () -> Unit,
    onToggleHdr: () -> Unit,
    onSetCameraSource: (String) -> Unit,
    onSelectAudioDevice: (android.media.AudioDeviceInfo) -> Unit,
    onNavigateHome: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onHideInterface: () -> Unit,
    onPopoverOpenChanged: (String, Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<String?>(null) }
    PopoverReporter("overflow", open, onPopoverOpenChanged)
    val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }
    val position = remember(gapPx) { HudPopupPositionProvider(HudPopupSide.BELOW, gapPx) }
    val hdrSupportedNow = currentLens?.supportsHdr10 == true && videoSettings.codec == "H.265" && !isRecording

    Box {
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .hudClickable(onClick = {
                    page = null
                    open = true
                }, onClickLabel = "Abrir mais opções")
                .semantics { contentDescription = "Mais opções" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.MoreVert, null, tint = Color.White, modifier = Modifier.drawIconOutline().size(22.dp))
        }
        if (open) {
            Popup(popupPositionProvider = position, onDismissRequest = { open = false }) {
                when (page) {
                    "source" -> SimpleListPopover(
                        title = "FONTE DE VÍDEO",
                        options = SourceOptions,
                        activeOption = videoSettings.videoSource,
                        onSelect = {
                            onSetCameraSource(it)
                            open = false
                        },
                        onDismiss = { page = null },
                    )

                    "mic" -> SimpleListPopover(
                        title = "MICROFONE",
                        options = availableAudioDevices.map { it.productName?.toString() ?: "Mic" }.ifEmpty { listOf("Padrão") },
                        activeOption = selectedAudioDeviceName,
                        onSelect = { name ->
                            availableAudioDevices.find { (it.productName?.toString() ?: "Mic") == name }?.let(onSelectAudioDevice)
                            open = false
                        },
                        onDismiss = { page = null },
                    )

                    else -> Column(
                        modifier = Modifier
                            .width(260.dp)
                            .heightIn(max = 300.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(ModernHudTheme.panelBg)
                            .border(1.dp, ModernHudTheme.panelBorder, RoundedCornerShape(16.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 6.dp),
                    ) {
                        OverflowRow(Icons.Filled.Home, "Voltar ao menu", null) {
                            open = false
                            onNavigateHome()
                        }
                        OverflowRow(Icons.Filled.Settings, "Configurações", null) {
                            open = false
                            onNavigateToSettings()
                        }
                        OverflowRow(Icons.Filled.Videocam, "Fonte de vídeo", videoSettings.videoSource) { page = "source" }
                        OverflowRow(Icons.Filled.Mic, "Microfone", selectedAudioDeviceName.take(14).ifBlank { "Mic" }) { page = "mic" }
                        if (currentLens?.hasTorch == true) {
                            OverflowRow(
                                if (isTorchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                                "Lanterna",
                                if (isTorchEnabled) "ON" else "OFF",
                                active = isTorchEnabled,
                                onClick = onToggleTorch,
                            )
                        }
                        if (currentLens?.hasOis == true || currentLens?.hasEis == true) {
                            OverflowRow(Icons.Filled.Vibration, "Estabilização", if (isStabilizationEnabled) "ON" else "OFF", active = isStabilizationEnabled, onClick = onToggleStabilization)
                        }
                        OverflowRow(
                            if (isHdrEnabled) Icons.Filled.HdrOn else Icons.Filled.HdrOff,
                            if (hdrSupportedNow) "HDR (HLG10)" else "HDR (requer H.265 e lente compatível)",
                            if (isHdrEnabled) "ON" else "OFF",
                            active = isHdrEnabled,
                            enabled = hdrSupportedNow,
                            onClick = onToggleHdr,
                        )
                        OverflowRow(Icons.Filled.Videocam, "NDI", if (isNdiEnabled) "ON" else "OFF", active = isNdiEnabled, onClick = onNdiToggle)
                        OverflowRow(Icons.Filled.VisibilityOff, "Ocultar interface", null) {
                            open = false
                            onHideInterface()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverflowRow(
    icon: ImageVector,
    label: String,
    trailing: String?,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .hudClickable(onClick = onClick, enabled = enabled)
            .semantics(mergeDescendants = true) {
                contentDescription = if (trailing != null) "$label, $trailing" else label
                if (trailing == "ON" || trailing == "OFF") stateDescription = if (active) HudStrings.STATE_ON else HudStrings.STATE_OFF
            }
            .padding(horizontal = 16.dp),
    ) {
        Icon(icon, null, tint = if (active) ModernHudTheme.accent else Color.White.copy(alpha = if (enabled) 0.9f else 0.35f), modifier = Modifier.size(20.dp))
        Text(
            label,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.4f),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 2,
        )
        if (trailing != null) {
            Text(trailing, color = if (active) ModernHudTheme.accent else Color.White.copy(alpha = 0.6f), fontSize = HudTheme.fontSizeNormal, fontWeight = FontWeight.Bold)
        }
    }
}
