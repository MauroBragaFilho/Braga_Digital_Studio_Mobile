package com.bragastudio.mobile.coremedia.domain

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class AudioManagerService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _availableDevices = MutableStateFlow<List<AudioDeviceInfo>>(emptyList())
    val availableDevices: StateFlow<List<AudioDeviceInfo>> = _availableDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<AudioDeviceInfo?>(null)
    val selectedDevice: StateFlow<AudioDeviceInfo?> = _selectedDevice.asStateFlow()

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            super.onAudioDevicesAdded(addedDevices)
            updateDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            super.onAudioDevicesRemoved(removedDevices)
            updateDevices()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        updateDevices()
    }

    private fun updateDevices() {
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        // Filter relevant inputs: built-in mic, USB devices, Bluetooth, wired headsets
        val relevantDevices = mutableListOf<AudioDeviceInfo>()
        var hasBuiltIn = false
        for (device in inputDevices) {
            if (device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                if (!hasBuiltIn) {
                    relevantDevices.add(device)
                    hasBuiltIn = true
                }
            } else if (
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            ) {
                relevantDevices.add(device)
            }
        }

        _availableDevices.value = relevantDevices.toList()

        // Se o dispositivo atual foi desconectado, reverter para o padrao
        val currentSelected = _selectedDevice.value
        if (currentSelected != null && !relevantDevices.any { it.id == currentSelected.id }) {
            _selectedDevice.value = relevantDevices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        }

        // Se nenhum estiver selecionado e temos algum disponivel, selecionar o primeiro
        if (_selectedDevice.value == null && relevantDevices.isNotEmpty()) {
            _selectedDevice.value = relevantDevices.find { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } ?: relevantDevices.first()
        }
    }

    fun selectDevice(device: AudioDeviceInfo) {
        if (_availableDevices.value.any { it.id == device.id }) {
            _selectedDevice.value = device
        }
    }

    fun getDeviceName(device: AudioDeviceInfo): String = when (device.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Microfone Interno"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB: ${device.productName}"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset: ${device.productName}"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset (P2)"
        else -> "Dispositivo ${device.id}"
    }
}
