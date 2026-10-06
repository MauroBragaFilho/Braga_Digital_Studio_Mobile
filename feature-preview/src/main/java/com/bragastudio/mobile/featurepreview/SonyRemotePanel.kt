package com.bragastudio.mobile.featurepreview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.bragastudio.mobile.core.model.SonyCameraStatus

/**
 * Chip de status da Sony na UI moderna (M30): ponto de conexão + bateria +
 * cartão. Tocar abre/fecha o [SonyRemoteControlPanel] (ISO/Shutter/Abertura/
 * foco) num popover logo abaixo; ao lado fica o botão de disparo do obturador
 * físico — antes o painel só era renderizado na UI clássica, inalcançável.
 */
@Composable
fun SonyStatusChip(
    telemetry: SonyCameraStatus,
    onSetIso: (Int?) -> Unit,
    onSetShutter: (Long?) -> Unit,
    onSetAperture: (String) -> Unit,
    onSetApertureAuto: () -> Unit,
    onTakePicture: () -> Unit,
    modifier: Modifier = Modifier,
    maxPanelHeight: androidx.compose.ui.unit.Dp = 360.dp,
) {
    var panelOpen by remember { mutableStateOf(false) }
    val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    val popupPosition = remember(gapPx) { HudPopupPositionProvider(HudPopupSide.BELOW, gapPx) }

    val battery = if (telemetry.batteryLevel.isNotEmpty()) "${telemetry.batteryLevel}%" else "--"
    val storage = telemetry.storageAvailable.ifEmpty { "--" }
    val summary = if (telemetry.isConnected) {
        "${HudStrings.SONY_CHIP_CONNECTED}, bateria $battery, cartão $storage"
    } else {
        HudStrings.SONY_CHIP_SEARCHING
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            Row(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .hudClickable(
                        onClick = { panelOpen = !panelOpen },
                        onClickLabel = if (panelOpen) "Fechar painel Sony" else "Abrir painel Sony",
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = summary
                        stateDescription = if (panelOpen) "Painel aberto" else "Painel fechado"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Filled.CameraAlt,
                        contentDescription = null,
                        tint = if (telemetry.isConnected) HudTheme.buttonActiveColor else Color.Gray,
                        modifier = Modifier.size(16.dp),
                    )
                    if (telemetry.isConnected) {
                        Icon(
                            Icons.Filled.BatteryFull,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(battery, color = Color.White, fontSize = HudTheme.fontSizeMin, fontWeight = FontWeight.Bold)
                        Icon(
                            Icons.Filled.SdCard,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(storage, color = Color.White, fontSize = HudTheme.fontSizeMin, fontWeight = FontWeight.Bold)
                    } else {
                        Text("Sony...", color = Color.Gray, fontSize = HudTheme.fontSizeMin, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (panelOpen) {
                Popup(popupPositionProvider = popupPosition, onDismissRequest = { panelOpen = false }) {
                    SonyRemoteControlPanel(
                        telemetry = telemetry,
                        onSetIso = onSetIso,
                        onSetShutter = onSetShutter,
                        onSetAperture = onSetAperture,
                        onSetApertureAuto = onSetApertureAuto,
                        onTakePicture = onTakePicture,
                        modifier = Modifier
                            .heightIn(max = maxPanelHeight)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        }

        // Disparo direto (obturador físico da Sony, não o REC do celular).
        ShutterButton(onClick = onTakePicture, size = 48.dp)
    }
}

/** Botão de disparo da Sony: círculo branco com ícone de câmera, alvo de 48 dp. */
@Composable
private fun ShutterButton(onClick: () -> Unit, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White)
            .border(3.dp, Color.Black.copy(alpha = 0.3f), CircleShape)
            .hudClickable(onClick = onClick)
            .semantics { contentDescription = HudStrings.SONY_SHUTTER },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
    }
}

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
 *
 * Abertura "AUTO" NÃO vai para o setFNumber (a câmera não aceita "AUTO" como
 * f-number): chama [onSetApertureAuto], que deve trocar o modo de exposição.
 */
@Composable
fun SonyRemoteControlPanel(
    telemetry: SonyCameraStatus,
    onSetIso: (Int?) -> Unit,
    onSetShutter: (Long?) -> Unit,
    onSetAperture: (String) -> Unit,
    onTakePicture: () -> Unit,
    modifier: Modifier = Modifier,
    onSetApertureAuto: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .width(170.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CameraAlt,
                contentDescription = "Sony Wi-Fi",
                tint = if (telemetry.isConnected) HudTheme.buttonActiveColor else Color.Gray,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = if (telemetry.isConnected) "SONY LINK" else "BUSCANDO...",
                color = if (telemetry.isConnected) Color.White else Color.Gray,
                fontSize = HudTheme.fontSizeMin,
                fontWeight = FontWeight.Bold,
            )
        }

        // Telemetria: bateria + cartão, direto do getEvent() da câmera.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(Icons.Filled.BatteryFull, contentDescription = "Bateria", tint = Color.White, modifier = Modifier.size(14.dp))
                Text(
                    text = if (telemetry.batteryLevel.isNotEmpty()) "${telemetry.batteryLevel}%" else "--",
                    color = Color.White,
                    fontSize = HudTheme.fontSizeMin,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(Icons.Filled.SdCard, contentDescription = "Cartão", tint = Color.White, modifier = Modifier.size(14.dp))
                Text(
                    text = if (telemetry.storageAvailable.isNotEmpty()) telemetry.storageAvailable else "-- min",
                    color = Color.White,
                    fontSize = HudTheme.fontSizeMin,
                )
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

        SonyControlChip("ISO", telemetry.currentIso.ifEmpty { "AUTO" }, SonyIsoOptions) { selected ->
            onSetIso(selected.toIntOrNull()) // "AUTO" -> null
        }
        SonyControlChip("SHUTTER", telemetry.currentShutterSpeed.ifEmpty { "AUTO" }, SonyShutterOptions) { selected ->
            onSetShutter(shutterLabelToNanosLocal(selected))
        }
        SonyControlChip("ABERTURA", telemetry.currentFNumber.ifEmpty { "--" }, SonyApertureOptions) { selected ->
            if (selected == "AUTO") onSetApertureAuto() else onSetAperture(selected)
        }

        if (telemetry.focusStatus.isNotEmpty()) {
            Text(
                text = "FOCO: ${telemetry.focusStatus}",
                color = if (telemetry.focusStatus.contains("Focused", ignoreCase = true)) Color(0xFF00E676) else Color.Gray,
                fontSize = HudTheme.fontSizeMin,
            )
        }

        // Botão de disparo — separado do REC do BDSM porque aciona o obturador
        // físico da Sony (actTakePicture), não a gravação de vídeo do celular.
        ShutterButton(onClick = onTakePicture, size = 48.dp, modifier = Modifier.padding(top = 2.dp))
    }
}

private val SonyIsoOptions = listOf("AUTO", "100", "200", "400", "800", "1600", "3200", "6400")
private val SonyShutterOptions = listOf("1/1000", "1/500", "1/250", "1/125", "1/60", "1/30", "1/15", "1\"")
private val SonyApertureOptions = listOf("AUTO", "3.5", "4.0", "5.6", "8.0", "11", "16", "22")

@Composable
private fun SonyControlChip(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HudTheme.minTouchTarget)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .hudClickable(onClick = { expanded = true }, onClickLabel = "Escolher $label")
            .semantics(mergeDescendants = true) { contentDescription = "$label, $value" }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.Gray, fontSize = HudTheme.fontSizeMin)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = HudTheme.fontSizeMin)
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
