package com.bragastudio.mobile.core.data

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bragastudio.mobile.core.domain.BspSettings
import com.bragastudio.mobile.core.domain.DisplayName
import com.bragastudio.mobile.core.domain.LandscapeNavSide
import com.bragastudio.mobile.core.domain.MonitorSettings
import com.bragastudio.mobile.core.domain.NdiNaming
import com.bragastudio.mobile.core.domain.NdiSettings
import com.bragastudio.mobile.core.domain.SettingsRepository
import com.bragastudio.mobile.core.domain.ThemeMode
import com.bragastudio.mobile.core.domain.VideoSettings
import com.bragastudio.mobile.core.domain.VideoSources
import com.bragastudio.mobile.core.domain.withEffectiveSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Arquivo corrompido vira preferências vazias (defaults) em vez de derrubar o app a cada abertura.
private val Context.dataStore by preferencesDataStore(
    name = "bsm_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private object PreferencesKeys {
        val VIDEO_RESOLUTION = stringPreferencesKey("video_resolution")
        val VIDEO_FPS = intPreferencesKey("video_fps")
        val VIDEO_BITRATE = intPreferencesKey("video_bitrate")
        val VIDEO_CODEC = stringPreferencesKey("video_codec")
        val VIDEO_SOURCE = stringPreferencesKey("video_source")
        val RECORDING_DIR = stringPreferencesKey("recording_dir")
        val SAVE_TO_GALLERY = booleanPreferencesKey("save_to_gallery")
        val VIDEO_STABILIZATION = booleanPreferencesKey("video_stabilization")
        val VIDEO_HDR = booleanPreferencesKey("video_hdr")

        val ZEBRA_THRESHOLD = intPreferencesKey("zebra_threshold")
        val FP_COLOR = stringPreferencesKey("fp_color")
        val FP_SENSITIVITY = stringPreferencesKey("fp_sensitivity")
        val SELECTED_LUT = stringPreferencesKey("selected_lut")
        val LUT_INTENSITY = floatPreferencesKey("lut_intensity")
        val MODERN_UI_ENABLED = booleanPreferencesKey("modern_ui_enabled")

        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANDSCAPE_NAV_SIDE = stringPreferencesKey("landscape_nav_side")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DISPLAY_NAME = stringPreferencesKey("display_name")

        val NDI_ENABLED = booleanPreferencesKey("ndi_enabled")
        val NDI_NAME = stringPreferencesKey("ndi_name")
        val NDI_AUDIO_ENABLED = booleanPreferencesKey("ndi_audio_enabled")
        val NDI_RESOLUTION = stringPreferencesKey("ndi_resolution")

        val BSP_ENABLED = booleanPreferencesKey("bsp_enabled")
        val BSP_NAME = stringPreferencesKey("bsp_name")
        val BSP_ALLOW_PLAIN = booleanPreferencesKey("bsp_allow_plain")
        val BSP_RESOLUTION = stringPreferencesKey("bsp_resolution")
        val BSP_FPS = intPreferencesKey("bsp_fps")
    }

    // Fonte única dos flows: erro de E/S na leitura do DataStore vira "defaults" em vez de
    // derrubar o coletor; qualquer outra exceção é relançada (não engolir bugs).
    private val prefsFlow: Flow<Preferences> = context.dataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    override val videoSettings: Flow<VideoSettings> = prefsFlow.map { prefs ->
        VideoSettings(
            resolution = prefs[PreferencesKeys.VIDEO_RESOLUTION] ?: "1080p",
            fps = prefs[PreferencesKeys.VIDEO_FPS] ?: 30,
            bitrateMbps = prefs[PreferencesKeys.VIDEO_BITRATE] ?: 50,
            codec = prefs[PreferencesKeys.VIDEO_CODEC] ?: "H.264",
            videoSource = prefs[PreferencesKeys.VIDEO_SOURCE] ?: "Camera",
            recordingDirectoryUri = prefs[PreferencesKeys.RECORDING_DIR],
            saveToGallery = prefs[PreferencesKeys.SAVE_TO_GALLERY] ?: false,
            stabilizationEnabled = prefs[PreferencesKeys.VIDEO_STABILIZATION] ?: false,
            hdrEnabled = prefs[PreferencesKeys.VIDEO_HDR] ?: false,
        ).withEffectiveSource() // "SONY" persistido sem o recurso Sony Wi-Fi => câmera do celular
    }.distinctUntilChanged()

    override val monitorSettings: Flow<MonitorSettings> = prefsFlow.map { prefs ->
        MonitorSettings(
            zebraThreshold = prefs[PreferencesKeys.ZEBRA_THRESHOLD] ?: 100,
            focusPeakingColor = prefs[PreferencesKeys.FP_COLOR] ?: "Red",
            focusPeakingSensitivity = prefs[PreferencesKeys.FP_SENSITIVITY] ?: "Medium",
            selectedLut = prefs[PreferencesKeys.SELECTED_LUT] ?: "Nenhum (Desativado)",
        )
    }.distinctUntilChanged()

    override val ndiSettings: Flow<NdiSettings> = prefsFlow.map { prefs ->
        NdiSettings(
            isEnabled = prefs[PreferencesKeys.NDI_ENABLED] ?: false,
            cameraName = NdiNaming.sourceName(prefs[PreferencesKeys.NDI_NAME], NdiNaming.deviceName(context)),
            isAudioEnabled = prefs[PreferencesKeys.NDI_AUDIO_ENABLED] ?: true,
            resolution = prefs[PreferencesKeys.NDI_RESOLUTION] ?: "FHD",
        )
    }.distinctUntilChanged()

    override val bspSettings: Flow<BspSettings> = prefsFlow.map { prefs ->
        BspSettings(
            isEnabled = prefs[PreferencesKeys.BSP_ENABLED] ?: false,
            cameraName = prefs[PreferencesKeys.BSP_NAME] ?: ("BDSM - " + android.os.Build.MODEL),
            resolution = prefs[PreferencesKeys.BSP_RESOLUTION] ?: "FHD",
            fps = prefs[PreferencesKeys.BSP_FPS] ?: 30,
            allowPlainMedia = prefs[PreferencesKeys.BSP_ALLOW_PLAIN] ?: false,
        )
    }.distinctUntilChanged()

    override val lutIntensity: Flow<Float> = prefsFlow.map { prefs ->
        (prefs[PreferencesKeys.LUT_INTENSITY] ?: 1f).coerceIn(0f, 1f)
    }.distinctUntilChanged()

    override suspend fun setVideoResolution(resolution: String) {
        context.dataStore.edit { it[PreferencesKeys.VIDEO_RESOLUTION] = resolution }
    }

    override suspend fun setVideoFps(fps: Int) {
        context.dataStore.edit { it[PreferencesKeys.VIDEO_FPS] = fps }
    }

    override suspend fun setVideoBitrate(bitrateMbps: Int) {
        context.dataStore.edit { it[PreferencesKeys.VIDEO_BITRATE] = bitrateMbps }
    }

    override suspend fun setVideoCodec(codec: String) {
        context.dataStore.edit { it[PreferencesKeys.VIDEO_CODEC] = codec }
    }

    override suspend fun setVideoSource(source: String) {
        // "SONY" com o recurso desligado não é gravado (a escolha anterior do usuário é mantida).
        if (source.equals(VideoSources.SONY, ignoreCase = true) && !VideoSources.sonyWifiEnabled()) return
        context.dataStore.edit { it[PreferencesKeys.VIDEO_SOURCE] = source }
    }

    override suspend fun setRecordingDirectoryUri(uri: String?) {
        context.dataStore.edit {
            if (uri == null) {
                it.remove(PreferencesKeys.RECORDING_DIR)
            } else {
                it[PreferencesKeys.RECORDING_DIR] = uri
            }
        }
    }

    override suspend fun setSaveToGallery(save: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.SAVE_TO_GALLERY] = save }
    }

    override suspend fun setVideoStabilizationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.VIDEO_STABILIZATION] = enabled }
    }

    override suspend fun setVideoHdrEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.VIDEO_HDR] = enabled }
    }

    override suspend fun setZebraThreshold(threshold: Int) {
        context.dataStore.edit { it[PreferencesKeys.ZEBRA_THRESHOLD] = threshold }
    }

    override suspend fun setFocusPeakingColor(color: String) {
        context.dataStore.edit { it[PreferencesKeys.FP_COLOR] = color }
    }

    override suspend fun setFocusPeakingSensitivity(sensitivity: String) {
        context.dataStore.edit { it[PreferencesKeys.FP_SENSITIVITY] = sensitivity }
    }

    override suspend fun setSelectedLut(lutName: String) {
        context.dataStore.edit { settings ->
            settings[PreferencesKeys.SELECTED_LUT] = lutName
        }
    }

    override suspend fun setLutIntensity(intensity: Float) {
        context.dataStore.edit { it[PreferencesKeys.LUT_INTENSITY] = intensity.coerceIn(0f, 1f) }
    }

    // Interface nova (topbar minimalista + controles manuais circulares) é o
    // único modo do app; a preferência persiste o default = true e existe
    // apenas para permitir religar a UI clássica via debug/rollback futuro
    // sem precisar reinstalar ou tocar em código.
    override val isModernUiEnabled: Flow<Boolean> = prefsFlow.map { prefs ->
        prefs[PreferencesKeys.MODERN_UI_ENABLED] ?: true
    }.distinctUntilChanged()

    override suspend fun setModernUiEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.MODERN_UI_ENABLED] = enabled }
    }

    override val themeMode: Flow<ThemeMode> = prefsFlow.map { prefs ->
        ThemeMode.fromName(prefs[PreferencesKeys.THEME_MODE])
    }.distinctUntilChanged()

    override suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[PreferencesKeys.THEME_MODE] = mode.name }
    }

    override val landscapeNavSide: Flow<LandscapeNavSide> = prefsFlow.map { prefs ->
        LandscapeNavSide.fromName(prefs[PreferencesKeys.LANDSCAPE_NAV_SIDE])
    }.distinctUntilChanged()

    override suspend fun setLandscapeNavSide(side: LandscapeNavSide) {
        context.dataStore.edit { it[PreferencesKeys.LANDSCAPE_NAV_SIDE] = side.name }
    }

    override val displayName: Flow<String> = prefsFlow.map { prefs ->
        DisplayName.sanitize(prefs[PreferencesKeys.DISPLAY_NAME])
    }.distinctUntilChanged()

    override suspend fun setDisplayName(name: String) {
        context.dataStore.edit { it[PreferencesKeys.DISPLAY_NAME] = DisplayName.sanitize(name) }
    }

    override val dynamicColorEnabled: Flow<Boolean> = prefsFlow.map { prefs ->
        prefs[PreferencesKeys.DYNAMIC_COLOR] ?: false
    }.distinctUntilChanged()

    override suspend fun setDynamicColorEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.DYNAMIC_COLOR] = enabled }
    }

    override suspend fun setNdiEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.NDI_ENABLED] = enabled }
    }

    override suspend fun setNdiCameraName(name: String) {
        context.dataStore.edit {
            it[PreferencesKeys.NDI_NAME] = NdiNaming.sourceName(name, NdiNaming.deviceName(context))
        }
    }

    override suspend fun setNdiAudioEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.NDI_AUDIO_ENABLED] = enabled }
    }

    override suspend fun setNdiResolution(resolution: String) {
        context.dataStore.edit { it[PreferencesKeys.NDI_RESOLUTION] = resolution }
    }

    override suspend fun setBspEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.BSP_ENABLED] = enabled }
    }

    override suspend fun setBspCameraName(name: String) {
        context.dataStore.edit {
            it[PreferencesKeys.BSP_NAME] = if (name.isBlank()) ("BDSM - " + android.os.Build.MODEL) else name
        }
    }

    override suspend fun setBspAllowPlainMedia(allow: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.BSP_ALLOW_PLAIN] = allow }
    }

    override suspend fun setBspResolution(resolution: String) {
        context.dataStore.edit { it[PreferencesKeys.BSP_RESOLUTION] = resolution }
    }

    override suspend fun setBspFps(fps: Int) {
        context.dataStore.edit { it[PreferencesKeys.BSP_FPS] = fps }
    }
}
