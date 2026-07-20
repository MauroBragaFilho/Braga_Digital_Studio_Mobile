package com.bragastudio.mobile.corecapture.device

import android.content.Context
import android.hardware.usb.UsbDevice
import android.view.Surface
import com.bragastudio.mobile.corecapture.domain.CaptureDevice
import com.bragastudio.mobile.corecapture.domain.CaptureState
import com.bragastudio.mobile.corecapture.domain.LensInfo
import com.serenegiant.usb.DeviceFilter
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UvcCaptureDevice @Inject constructor(
    @ApplicationContext private val context: Context
) : CaptureDevice {

    private val _state = MutableStateFlow(CaptureState.IDLE)
    override val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _availableLenses = MutableStateFlow<List<LensInfo>>(listOf(
        LensInfo("uvc_main", "Câmera USB", isPrimary = true)
    ))
    override val availableLenses: StateFlow<List<LensInfo>> = _availableLenses.asStateFlow()

    override val deviceId: String = "UVC"
    override val deviceName: String = "Placa de Captura USB"
    
    override val sensorOrientation: Int = 0

    private var usbMonitor: USBMonitor? = null
    private var uvcCamera: UVCCamera? = null
    private var currentSurface: Surface? = null
    private var isPreviewing = false

    private val onDeviceConnectListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice?) {
            device?.let { usbMonitor?.requestPermission(it) }
        }

        override fun onDetach(device: UsbDevice?) {
            stopUvcCamera()
        }

        override fun onDeviceOpen(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) {
            _state.value = CaptureState.INITIALIZING
            try {
                val param = com.serenegiant.usb.UVCParam()
                val camera = UVCCamera(param)
                
                if (ctrlBlock != null) {
                    camera.open(ctrlBlock)
                }
                
                try {
                    camera.setPreviewSize(1920, 1080, UVCCamera.FRAME_FORMAT_MJPEG)
                } catch (e: Exception) {
                    try {
                        camera.setPreviewSize(1280, 720, UVCCamera.FRAME_FORMAT_YUYV)
                    } catch (e2: Exception) {
                        e2.printStackTrace()
                    }
                }

                if (currentSurface != null) {
                    camera.setPreviewDisplay(currentSurface)
                    camera.startPreview()
                    isPreviewing = true
                }
                
                uvcCamera = camera
                _state.value = CaptureState.READY
            } catch (e: Exception) {
                e.printStackTrace()
                _state.value = CaptureState.ERROR
            }
        }

        override fun onDeviceClose(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            stopUvcCamera()
        }

        override fun onCancel(device: UsbDevice?) {
            _state.value = CaptureState.IDLE
        }
    }

    override suspend fun start(vararg surfaces: Surface) {
        if (surfaces.isEmpty()) return
        currentSurface = surfaces.first()
        _state.value = CaptureState.INITIALIZING

        withContext(Dispatchers.Main) {
            if (usbMonitor == null) {
                usbMonitor = USBMonitor(context, onDeviceConnectListener)
                usbMonitor?.register()
            }
            
            val deviceList = usbMonitor?.getDeviceList()
            if (deviceList.isNullOrEmpty()) {
                _state.value = CaptureState.IDLE 
            } else {
                val device = deviceList.firstOrNull()
                if (device != null) {
                    usbMonitor?.requestPermission(device)
                }
            }
        }
    }

    private fun stopUvcCamera() {
        if (isPreviewing) {
            uvcCamera?.stopPreview()
            isPreviewing = false
        }
        uvcCamera?.destroy()
        uvcCamera = null
        _state.value = CaptureState.IDLE
    }

    override suspend fun stop() {
        withContext(Dispatchers.Main) {
            stopUvcCamera()
            usbMonitor?.unregister()
            usbMonitor?.destroy()
            usbMonitor = null
        }
    }

    override suspend fun switchCamera(cameraId: String) {
        
    }

    override fun setIso(iso: Int?) {
        // Not implemented for UVC yet
    }

    override fun setShutterSpeed(nanoseconds: Long?) {
        // Not implemented for UVC yet
    }

    override fun setWhiteBalance(mode: Int?) {
        // Not implemented for UVC yet
    }

    override fun setFocusDistance(diopters: Float?) {
        // Not implemented for UVC yet
    }

    override fun configure(resolution: String, fps: Int) {}
}
