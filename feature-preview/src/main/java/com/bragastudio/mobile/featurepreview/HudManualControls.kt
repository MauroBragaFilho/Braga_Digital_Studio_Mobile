package com.bragastudio.mobile.featurepreview

import android.hardware.camera2.CaptureRequest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.bragastudio.mobile.corecapture.domain.CaptureMetadata
import com.bragastudio.mobile.corecapture.domain.ManualLimits
import kotlin.math.roundToInt

// Listas de opções dos dials manuais: constantes (não recriadas a cada recomposição).
// Convenção: toda lista começa com "AUTO" (arrastar tudo à esquerda = automático).
private val IsoOptions = listOf("AUTO", "100", "200", "400", "800", "1600", "3200", "6400")
private val ShutterOptions = listOf("AUTO", "1/1000", "1/500", "1/250", "1/125", "1/60", "1/30", "1/15", "1\"")
private val IrisOptions = listOf("AUTO", "1.8", "2.8", "3.5", "4.0", "5.6", "8.0", "11", "16", "22")
private val WbOptions = listOf("AUTO", "2700K", "3200K", "4000K", "5000K", "5600K", "6500K", "7500K")
private val FocusOptions = listOf("AUTO", "∞", "5m", "2m", "1m", "0.5m", "0.3m", "0.1m")

/**
 * Tiles de exposição da faixa de informações (ISO, obturador, WB, foco; íris na
 * Sony). Cada tile mostra o valor real (AUTO discreto, manual em âmbar) e, ao
 * toque, abre o dial horizontal EXISTENTE daquele controle logo abaixo dele.
 *
 * ISO/Obturador/Íris usam a fonte Sony (telemetria Wi-Fi) quando ativa; ISO,
 * Obturador, WB e Foco usam o Camera2Device nativo nas demais fontes — em ambos
 * os casos o controle É manual de verdade, não simulado. Fonte USB: sem tiles.
 *
 * M31: na Sony o valor só é ENVIADO ao soltar o dedo (cada comando é uma
 * requisição HTTP); no Camera2 o valor é aplicado quando o índice muda (preview
 * ao vivo), sem repetir ao soltar.
 */
@Composable
fun ExposureTiles(
    isSonyActive: Boolean,
    sonyTelemetry: com.bragastudio.mobile.core.model.SonyCameraStatus,
    onSetIso: (Int?) -> Unit,
    onSetShutter: (Long?) -> Unit,
    onSonySetAperture: (String) -> Unit,
    nativeCurrentIso: Int?,
    nativeCurrentShutterNanos: Long?,
    nativeCurrentWbMode: Int?,
    nativeCurrentFocusDiopters: Float?,
    onSetNativeWb: (Int?) -> Unit,
    onSetNativeFocus: (Float?) -> Unit,
    modifier: Modifier = Modifier,
    // Íris em "AUTO" NÃO pode ir para o setFNumber (a câmera rejeita "AUTO"); precisa
    // de uma troca de modo de exposição, tratada por quem fornece este callback.
    onSonySetIrisAuto: () -> Unit = {},
    // Telemetria real do sensor (lida AQUI, dentro da folha) e faixas do hardware.
    captureMetadataProvider: () -> CaptureMetadata = { CaptureMetadata() },
    manualLimits: ManualLimits = ManualLimits(),
    fps: Int = 30,
    onPopoverOpenChanged: (String, Boolean) -> Unit = { _, _ -> },
) {
    var openDial by remember { mutableStateOf<String?>(null) }
    PopoverReporter("exposure", openDial != null, onPopoverOpenChanged)

    // Câmera nativa: o pedido do operador (espelho no ViewModel) vence; sem ele, se o
    // sensor está em exposição MANUAL de verdade (AE desligado, ex.: após reentrar na
    // tela) mostra o valor real da metadata; caso contrário é AUTO.
    val metadata = captureMetadataProvider()
    val realIso = if (!metadata.aeAuto) metadata.iso else null
    val realShutterNs = if (!metadata.aeAuto) metadata.exposureTimeNs else null
    val isoValue = if (isSonyActive) {
        sonyTelemetry.currentIso.ifEmpty { "AUTO" }
    } else {
        (nativeCurrentIso ?: realIso)?.toString() ?: "AUTO"
    }
    val shutterValue = if (isSonyActive) {
        sonyTelemetry.currentShutterSpeed.ifEmpty { "AUTO" }
    } else {
        nativeShutterNanosToLabel(nativeCurrentShutterNanos ?: realShutterNs)
    }
    val isoOptions = remember(manualLimits) { filterIsoOptions(IsoOptions, manualLimits) }
    val shutterOptions = remember(manualLimits) { filterShutterOptions(ShutterOptions, manualLimits) }
    val focusOptions = remember(manualLimits) { filterFocusOptions(FocusOptions, manualLimits) }
    val focusAvailable = supportsManualFocus(manualLimits)
    val apertureValue = sonyTelemetry.currentFNumber.ifEmpty { "--" } // íris física só existe via Sony
    val wbValue = nativeWbModeToLabel(nativeCurrentWbMode)
    val focusValue = nativeFocusDiopterToLabel(nativeCurrentFocusDiopters)

    fun applyDialValue(param: String, selected: String) {
        when (param) {
            "iso" -> onSetIso(coerceIsoToLimits(if (selected == "AUTO") null else selected.toIntOrNull(), manualLimits))
            "shutter" -> onSetShutter(coerceShutterToLimits(shutterLabelToNanosLocal(selected), manualLimits))
            "iris" -> if (selected == "AUTO") onSonySetIrisAuto() else onSonySetAperture(selected)
            "wb" -> onSetNativeWb(wbLabelToMode(selected))
            "focus" -> onSetNativeFocus(coerceFocusToLimits(focusLabelToDiopter(selected), manualLimits))
        }
    }

    val gapPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    val popupPosition = remember(gapPx) { HudPopupPositionProvider(HudPopupSide.BELOW_CENTER, gapPx) }

    // Cada tile é um Box próprio: o dial (Popup, fora do fluxo de layout) ancora
    // embaixo do tile tocado e não influencia a altura da faixa.
    @Composable
    fun DialTile(param: String, name: String, prefix: String?, display: String, current: String, options: List<String>, manual: Boolean, enabled: Boolean = true) {
        Box {
            HudTile(
                value = display,
                spokenName = name,
                prefix = prefix,
                isManual = manual,
                enabled = enabled,
                onClick = { openDial = if (openDial == param) null else param },
            )
            if (openDial == param) {
                Popup(popupPositionProvider = popupPosition, onDismissRequest = { openDial = null }) {
                    HorizontalDialPopover(
                        title = name.uppercase(),
                        currentValueLabel = current,
                        options = options,
                        isManual = param != "iris" || isSonyActive,
                        onSelect = { selected -> if (!isSonyActive) applyDialValue(param, selected) },
                        onSelectFinished = if (isSonyActive) {
                            { selected: String -> applyDialValue(param, selected) }
                        } else {
                            null
                        },
                        onDismiss = { openDial = null },
                    )
                }
            }
        }
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        // Íris: abertura física só existe na Sony; fora dela o tile some.
        if (isSonyActive) {
            DialTile("iris", "Íris", null, apertureTileValue(apertureValue), apertureValue, IrisOptions, !isAutoValue(apertureValue))
        }
        DialTile("iso", "ISO", "ISO", isoValue, isoValue, isoOptions, !isAutoValue(isoValue))
        DialTile(
            "shutter", "Obturador", "SS",
            shutterTileValue(shutterValue, shutterLabelToNanosLocal(shutterValue), fps),
            shutterValue, shutterOptions, !isAutoValue(shutterValue),
        )
        if (!isSonyActive) { // WB/foco manuais só existem no pipeline nativo (Camera2)
            DialTile("wb", "Balanço de branco", "WB", wbValue, wbValue, WbOptions, !isAutoValue(wbValue))
            if (focusAvailable) { // lente de foco fixo (min=0) não tem foco manual
                DialTile("focus", "Foco", null, focusTileValue(focusValue), focusValue, focusOptions, !isAutoValue(focusValue))
            }
        }
    }
}

// ---- Conversões de valor <-> rótulo para os controles nativos (Camera2) ----

internal fun nativeShutterNanosToLabel(nanos: Long?): String {
    if (nanos == null || nanos <= 0L) return "AUTO"
    val seconds = nanos / 1_000_000_000.0
    return if (seconds >= 1.0) {
        "${seconds.roundToInt()}\""
    } else {
        // roundToInt: 1/60 s = 16_666_666 ns -> 60,0000024 (toInt() poderia cair em 59).
        val denom = (1.0 / seconds).roundToInt()
        "1/$denom"
    }
}

internal fun nativeWbModeToLabel(mode: Int?): String = when (mode) {
    null -> "AUTO"
    CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> "2700K"
    CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "3200K"
    CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> "4000K"
    CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> "5600K"
    CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "6500K"
    CaptureRequest.CONTROL_AWB_MODE_SHADE -> "7500K"
    else -> "AUTO"
}

internal fun wbLabelToMode(label: String): Int? = when (label) {
    "2700K" -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
    "3200K" -> CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT
    "4000K" -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
    "5000K", "5600K" -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
    "6500K" -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
    "7500K" -> CaptureRequest.CONTROL_AWB_MODE_SHADE
    else -> null // AUTO
}

// Foco manual no Camera2 usa distância em dioptrias (1/metros); 0f = infinito.
internal fun nativeFocusDiopterToLabel(diopters: Float?): String {
    if (diopters == null) return "AUTO"
    if (diopters <= 0.01f) return "∞"
    val meters = 1f / diopters
    return if (meters >= 1f) "${meters.toInt()}m" else "${"%.1f".format(java.util.Locale.US, meters)}m"
}

internal fun focusLabelToDiopter(label: String): Float? = when (label) {
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

internal fun shutterLabelToNanosLocal(label: String): Long? {
    if (label == "AUTO") return null
    if (label.endsWith("\"")) {
        val secs = label.removeSuffix("\"").toDoubleOrNull() ?: return null
        return Math.round(secs * 1_000_000_000.0)
    }
    val parts = label.split("/")
    if (parts.size != 2) return null
    val denom = parts[1].toDoubleOrNull() ?: return null
    if (denom <= 0.0) return null
    return Math.round(1_000_000_000.0 / denom)
}
