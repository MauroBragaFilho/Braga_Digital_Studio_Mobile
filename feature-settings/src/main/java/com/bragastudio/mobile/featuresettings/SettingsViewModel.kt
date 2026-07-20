package com.bragastudio.mobile.featuresettings

import android.media.AudioDeviceInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bragastudio.mobile.core.domain.MonitorSettings
import com.bragastudio.mobile.core.domain.NdiSettings
import com.bragastudio.mobile.core.domain.SettingsRepository
import com.bragastudio.mobile.core.domain.VideoSettings
import com.bragastudio.mobile.core.domain.HardwareMetrics
import com.bragastudio.mobile.core.domain.HardwareMonitorService
import com.bragastudio.mobile.coremedia.domain.AudioManagerService
import com.bragastudio.mobile.coremedia.domain.NdiManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val audioManagerService: AudioManagerService,
    private val hardwareMonitorService: HardwareMonitorService,
    private val ndiManager: NdiManager
) : ViewModel() {

    val videoSettings = settingsRepository.videoSettings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        VideoSettings()
    )

    val monitorSettings = settingsRepository.monitorSettings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        MonitorSettings()
    )

    val hardwareMetrics = hardwareMonitorService.metrics.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        HardwareMetrics()
    )

    val ndiSettings = settingsRepository.ndiSettings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        NdiSettings()
    )

     val availableAudioDevices = audioManagerService.availableDevices.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList()
    )
    
    val selectedAudioDevice = audioManagerService.selectedDevice.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null
    )

    val ndiLatencyMs = ndiManager.ndiLatencyMs.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )

    val ndiFrameDropPct = ndiManager.ndiFrameDropPct.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0f
    )
    
    val ndiBitrateMbps = ndiManager.ndiBitrateMbps.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        0
    )

    init {
        viewModelScope.launch {
            videoSettings.collectLatest { settings ->
                ndiManager.setTargetFps(settings.fps)
            }
        }
    }

    fun setVideoResolution(resolution: String) { viewModelScope.launch { settingsRepository.setVideoResolution(resolution) } }
    fun setVideoFps(fps: Int) { 
        viewModelScope.launch { 
            settingsRepository.setVideoFps(fps) 
            ndiManager.setTargetFps(fps)
        } 
    }
    fun setVideoBitrate(bitrateMbps: Int) { viewModelScope.launch { settingsRepository.setVideoBitrate(bitrateMbps) } }
    fun setVideoCodec(codec: String) { viewModelScope.launch { settingsRepository.setVideoCodec(codec) } }
    fun setRecordingDirectoryUri(uri: String?) { viewModelScope.launch { settingsRepository.setRecordingDirectoryUri(uri) } }
    fun setVideoSource(source: String) { viewModelScope.launch { settingsRepository.setVideoSource(source) } }
    fun setSaveToGallery(save: Boolean) { viewModelScope.launch { settingsRepository.setSaveToGallery(save) } }
    
    fun setZebraThreshold(threshold: Int) { viewModelScope.launch { settingsRepository.setZebraThreshold(threshold) } }
    fun setFocusPeakingColor(color: String) { viewModelScope.launch { settingsRepository.setFocusPeakingColor(color) } }
    fun setFocusPeakingSensitivity(sensitivity: String) { viewModelScope.launch { settingsRepository.setFocusPeakingSensitivity(sensitivity) } }

    fun setNdiEnabled(enabled: Boolean) { viewModelScope.launch { settingsRepository.setNdiEnabled(enabled) } }
    fun setNdiCameraName(name: String) { viewModelScope.launch { settingsRepository.setNdiCameraName(name) } }
    
    // ✅ FUNÇÃO PARA ALTERAR O MICROFONE
    fun selectAudioDevice(device: AudioDeviceInfo) {
        viewModelScope.launch {
            audioManagerService.selectDevice(device)
        }
    }
    
    fun setSelectedLut(lutName: String) { 
        viewModelScope.launch { 
            settingsRepository.setSelectedLut(lutName) // Certifique-se que esta função existe no seu Repository
        } 
    }
}
