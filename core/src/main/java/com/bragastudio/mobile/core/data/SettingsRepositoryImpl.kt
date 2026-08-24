package com.bragastudio.mobile.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bragastudio.mobile.core.domain.MonitorSettings
import com.bragastudio.mobile.core.domain.NdiSettings
import com.bragastudio.mobile.core.domain.SettingsRepository
import com.bragastudio.mobile.core.domain.VideoSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "bsm_settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private object PreferencesKeys {
        val VIDEO_RESOLUTION = stringPreferencesKey("video_resolution")
        val VIDEO_FPS = intPreferencesKey("video_fps")
        val VIDEO_BITRATE = intPreferencesKey("video_bitrate")
        val VIDEO_CODEC = stringPreferencesKey("video_codec")
        val VIDEO_SOURCE = stringPreferencesKey("video_source")
        val RECORDING_DIR = stringPreferencesKey("recording_dir")
        val SAVE_TO_GALLERY = booleanPreferencesKey("save_to_gallery")
        
        val ZEBRA_THRESHOLD = intPreferencesKey("zebra_threshold")
        val FP_COLOR = stringPreferencesKey("fp_color")
        val FP_SENSITIVITY = stringPreferencesKey("fp_sensitivity")
        val SELECTED_LUT = stringPreferencesKey("selected_lut")
        val MODERN_UI_ENABLED = booleanPreferencesKey("modern_ui_enabled")

        val NDI_ENABLED = booleanPreferencesKey("ndi_enabled")
        val NDI_NAME = stringPreferencesKey("ndi_name")
        val NDI_AUDIO_ENABLED = booleanPreferencesKey("ndi_audio_enabled")
    }

    override val videoSettings: Flow<VideoSettings> = context.dataStore.data.map { prefs ->
        VideoSettings(
            resolution = prefs[PreferencesKeys.VIDEO_RESOLUTION] ?: "1080p",
            fps = prefs[PreferencesKeys.VIDEO_FPS] ?: 30,
            bitrateMbps = prefs[PreferencesKeys.VIDEO_BITRATE] ?: 50,
            codec = prefs[PreferencesKeys.VIDEO_CODEC] ?: "H.264",
            videoSource = prefs[PreferencesKeys.VIDEO_SOURCE] ?: "Camera",
            recordingDirectoryUri = prefs[PreferencesKeys.RECORDING_DIR],
            saveToGallery = prefs[PreferencesKeys.SAVE_TO_GALLERY] ?: true
        )
    }

    override val monitorSettings: Flow<MonitorSettings> = context.dataStore.data.map { prefs ->
        MonitorSettings(
            zebraThreshold = prefs[PreferencesKeys.ZEBRA_THRESHOLD] ?: 100,
            focusPeakingColor = prefs[PreferencesKeys.FP_COLOR] ?: "Red",
            focusPeakingSensitivity = prefs[PreferencesKeys.FP_SENSITIVITY] ?: "Medium",
            selectedLut = prefs[PreferencesKeys.SELECTED_LUT] ?: "Nenhum (Desativado)"
        )
    }

    override val ndiSettings: Flow<NdiSettings> = context.dataStore.data.map { prefs ->
        NdiSettings(
            isEnabled = prefs[PreferencesKeys.NDI_ENABLED] ?: false,
            cameraName = prefs[PreferencesKeys.NDI_NAME] ?: ("BDSM - " + android.os.Build.MODEL),
            isAudioEnabled = prefs[PreferencesKeys.NDI_AUDIO_ENABLED] ?: true
        )
    }

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

    // Interface nova (topbar minimalista + controles manuais circulares) é o
    // único modo do app; a preferência persiste o default = true e existe
    // apenas para permitir religar a UI clássica via debug/rollback futuro
    // sem precisar reinstalar ou tocar em código.
    override val isModernUiEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PreferencesKeys.MODERN_UI_ENABLED] ?: true
    }

    override suspend fun setModernUiEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.MODERN_UI_ENABLED] = enabled }
    }

    override suspend fun setNdiEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.NDI_ENABLED] = enabled }
    }

    override suspend fun setNdiCameraName(name: String) {
        context.dataStore.edit { 
            it[PreferencesKeys.NDI_NAME] = if (name.isBlank()) ("BDSM - " + android.os.Build.MODEL) else name 
        }
    }

    override suspend fun setNdiAudioEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.NDI_AUDIO_ENABLED] = enabled }
    }
}