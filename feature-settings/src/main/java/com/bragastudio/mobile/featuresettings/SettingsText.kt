package com.bragastudio.mobile.featuresettings

import android.media.AudioDeviceInfo
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.bragastudio.mobile.common.ui.theme.BdsmTheme
import com.bragastudio.mobile.core.domain.LandscapeNavSide
import com.bragastudio.mobile.core.domain.ThemeMode

/** Textos e ícone de uma categoria (UX v3). */
internal class SettingsCategoryMeta(@StringRes val titleRes: Int, @StringRes val descriptionRes: Int, val icon: ImageVector)

internal fun settingsCategoryMeta(category: SettingsCategory): SettingsCategoryMeta = when (category) {
    SettingsCategory.CAMERA -> SettingsCategoryMeta(R.string.cat_camera, R.string.cat_camera_desc, Icons.Filled.Videocam)
    SettingsCategory.AUDIO -> SettingsCategoryMeta(R.string.cat_audio, R.string.cat_audio_desc, Icons.Filled.Mic)
    SettingsCategory.MONITOR -> SettingsCategoryMeta(R.string.cat_monitor, R.string.cat_monitor_desc, Icons.Filled.Tv)
    SettingsCategory.NDI -> SettingsCategoryMeta(R.string.cat_ndi, R.string.cat_ndi_desc, Icons.Filled.Router)
    SettingsCategory.RECORDING -> SettingsCategoryMeta(R.string.cat_recording, R.string.cat_recording_desc, Icons.Filled.HighQuality)
    SettingsCategory.APP -> SettingsCategoryMeta(R.string.cat_app, R.string.cat_app_desc, Icons.Filled.Tune)
    SettingsCategory.ABOUT -> SettingsCategoryMeta(R.string.cat_about, R.string.cat_about_desc, Icons.Filled.Info)
}

/** Cor de acento do ícone da categoria (tokens do tema; decorativa). */
@Composable
internal fun SettingsCategory.accent(): Color = when (this) {
    SettingsCategory.CAMERA -> BdsmTheme.colors.accentVideo
    SettingsCategory.AUDIO -> BdsmTheme.colors.accentAudio
    SettingsCategory.MONITOR -> BdsmTheme.colors.accentMonitor
    SettingsCategory.NDI -> BdsmTheme.colors.accentNetwork
    SettingsCategory.RECORDING -> BdsmTheme.colors.accentStorage
    SettingsCategory.APP -> BdsmTheme.colors.accentSystem
    SettingsCategory.ABOUT -> BdsmTheme.colors.accentNeutral
}

// ---------------------------------------------------------------------------
// Auxiliares de texto (puros) — sem acesso a Android além de AudioDeviceInfo.
// ---------------------------------------------------------------------------

internal fun presetLabelRes(preset: QualityPreset): Int = when (preset) {
    QualityPreset.ECONOMIC -> R.string.settings_preset_economic
    QualityPreset.BALANCED -> R.string.settings_preset_balanced
    QualityPreset.MAX -> R.string.settings_preset_max
}

internal fun presetDescriptionRes(preset: QualityPreset): Int = when (preset) {
    QualityPreset.ECONOMIC -> R.string.settings_preset_economic_desc
    QualityPreset.BALANCED -> R.string.settings_preset_balanced_desc
    QualityPreset.MAX -> R.string.settings_preset_max_desc
}

internal fun themeModeLabelRes(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

internal fun landscapeNavSideLabelRes(side: LandscapeNavSide): Int = when (side) {
    LandscapeNavSide.END -> R.string.settings_landscape_nav_end
    LandscapeNavSide.START -> R.string.settings_landscape_nav_start
}

internal fun themeModeIcon(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> Icons.Filled.BrightnessAuto
    ThemeMode.LIGHT -> Icons.Filled.LightMode
    ThemeMode.DARK -> Icons.Filled.DarkMode
}

internal fun audioBaseName(device: AudioDeviceInfo?): String {
    if (device == null) return "Microfone do Celular"
    return when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microfone do Celular"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Fone de Ouvido com Fio"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "Microfone USB"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Dispositivo Bluetooth"
        else -> device.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Dispositivo de Áudio Externo"
    }
}

/** Id do dispositivo → rótulo único (acrescenta o produto/id quando há nomes repetidos). */
internal fun buildAudioLabels(devices: List<AudioDeviceInfo>): Map<Int, String> {
    val bases = devices.associate { it.id to audioBaseName(it) }
    val counts = bases.values.groupingBy { it }.eachCount()
    return devices.associate { d ->
        val base = bases.getValue(d.id)
        val product = d.productName?.toString()?.takeIf { it.isNotBlank() }
        d.id to if ((counts[base] ?: 0) > 1) "$base — ${product ?: "#${d.id}"} (#${d.id})" else base
    }
}

internal fun destinationOptionLabel(destination: StorageDestination, galleryAvailable: Boolean): String = when (destination) {
    StorageDestination.APP -> "Somente no app (privado)"

    StorageDestination.GALLERY ->
        if (galleryAvailable) "Galeria (cópia em Movies/BDSM ao parar)" else "Galeria — requer Android 10+ (exporte em Gravações)"

    StorageDestination.FOLDER -> "Pasta escolhida (cópia ao parar)…"
}

internal fun destinationShort(storage: StorageUiState): String = when (storage.destination) {
    StorageDestination.APP -> "Só no app"
    StorageDestination.GALLERY -> "Galeria"
    StorageDestination.FOLDER -> storage.folderLabel ?: "Pasta"
}

internal fun storageHelp(storage: StorageUiState): String = when (storage.destination) {
    StorageDestination.GALLERY ->
        "O take é gravado primeiro no app e, ao parar, uma cópia é salva na Galeria (Movies/BDSM) em segundo plano; " +
            "o original também continua no app (${storage.appDirLabel})."

    StorageDestination.FOLDER ->
        "O take é gravado primeiro no app e copiado para a pasta ao parar; o original também continua no app (${storage.appDirLabel})."

    else ->
        "Os arquivos ficam em ${storage.appDirLabel}, uma pasta privada do app: são apagados ao desinstalar. " +
            "Para guardá-los, exporte para a Galeria em Gravações ou escolha uma pasta."
}
