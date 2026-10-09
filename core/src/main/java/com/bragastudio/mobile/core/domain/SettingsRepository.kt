package com.bragastudio.mobile.core.domain

import kotlinx.coroutines.flow.Flow

data class VideoSettings(
    val resolution: String = "1080p",
    val fps: Int = 30,
    val bitrateMbps: Int = 50,
    val codec: String = "H.264",
    val videoSource: String = "Camera",
    val recordingDirectoryUri: String? = null,
    // Padrão false: com a exportação automática ativa, true duplicaria todo take na Galeria
    // sem o usuário ter escolhido. A Galeria passa a valer só por escolha explícita.
    val saveToGallery: Boolean = false,
    // Controles avançados de captura (Camera2), persistidos no DataStore.
    // Estabilização e HDR são preferências que sobrevivem a reinícios; a
    // lanterna (torch) é transitória e vive só no CaptureDevice.
    val stabilizationEnabled: Boolean = false,
    val hdrEnabled: Boolean = false,
)

data class MonitorSettings(
    val zebraThreshold: Int = 100,
    val focusPeakingColor: String = "Red",
    val focusPeakingSensitivity: String = "Medium",
    val selectedLut: String = "Nenhum (Desativado)",
)

data class NdiSettings(
    val isEnabled: Boolean = false,
    val cameraName: String = NdiNaming.fallbackName(),
    val isAudioEnabled: Boolean = true,
    // Resolução da transmissão NDI, totalmente separada de VideoSettings.resolution
    // (gravação). Antes o card de "Qualidade da Transmissão NDI" era decorativo —
    // não existia esse campo, e o ImageReader do NDI era fixo em 1920x1080.
    val resolution: String = "FHD",
)

data class BspSettings(
    val isEnabled: Boolean = false,
    val cameraName: String = "BDSM - " + android.os.Build.MODEL,
    val resolution: String = "FHD",
    val fps: Int = 30,
    // Depuração: aceita receptores que pedem mídia SEM criptografia (aead=false). Padrão desligado:
    // com AEAD ligado a mídia só é legível por quem tem o token do pareamento.
    val allowPlainMedia: Boolean = false,
)

interface SettingsRepository {
    val videoSettings: Flow<VideoSettings>
    val monitorSettings: Flow<MonitorSettings>
    val ndiSettings: Flow<NdiSettings>
    val bspSettings: Flow<BspSettings>

    /** Intensidade da LUT ativa (0..1, padrão 1). Persistida; o render lê por outro caminho. */
    val lutIntensity: Flow<Float>

    suspend fun setVideoResolution(resolution: String)
    suspend fun setVideoFps(fps: Int)
    suspend fun setVideoBitrate(bitrateMbps: Int)
    suspend fun setVideoCodec(codec: String)
    suspend fun setVideoSource(source: String)
    suspend fun setRecordingDirectoryUri(uri: String?)
    suspend fun setSaveToGallery(save: Boolean)

    suspend fun setVideoStabilizationEnabled(enabled: Boolean)
    suspend fun setVideoHdrEnabled(enabled: Boolean)

    suspend fun setZebraThreshold(threshold: Int)
    suspend fun setFocusPeakingColor(color: String)
    suspend fun setFocusPeakingSensitivity(sensitivity: String)

    suspend fun setNdiEnabled(enabled: Boolean)
    suspend fun setNdiCameraName(name: String)
    suspend fun setNdiAudioEnabled(enabled: Boolean)
    suspend fun setNdiResolution(resolution: String)

    suspend fun setBspEnabled(enabled: Boolean)
    suspend fun setBspCameraName(name: String)
    suspend fun setBspAllowPlainMedia(allow: Boolean)
    suspend fun setBspResolution(resolution: String)
    suspend fun setBspFps(fps: Int)

    suspend fun setSelectedLut(lutName: String)

    suspend fun setLutIntensity(intensity: Float)

    // Preferência de interface: nova UI (topbar minimalista + controles
    // manuais circulares) como padrão único do app.
    val isModernUiEnabled: Flow<Boolean>
    suspend fun setModernUiEnabled(enabled: Boolean)

    // Aparência: modo (Sistema/Claro/Escuro) e cores dinâmicas (Material You, Android 12+, desligado por padrão).
    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)
    val dynamicColorEnabled: Flow<Boolean>
    suspend fun setDynamicColorEnabled(enabled: Boolean)

    // Lado do rail de navegação em paisagem/telas largas (padrão: fim = direita).
    val landscapeNavSide: Flow<LandscapeNavSide>
    suspend fun setLandscapeNavSide(side: LandscapeNavSide)

    // Nome de exibição da saudação da Home ("" = usar o nome do aparelho). Só no aparelho.
    val displayName: Flow<String>
    suspend fun setDisplayName(name: String)
}
