package com.bragastudio.mobile.corecapture.device

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.util.Log
import android.view.Surface
import com.bragastudio.mobile.corecapture.domain.CameraInfoModel
import com.bragastudio.mobile.corecapture.domain.CaptureDevice
import com.bragastudio.mobile.corecapture.domain.CaptureState
import com.bragastudio.mobile.corecapture.domain.LensType
import com.bragastudio.mobile.corecapture.domain.UvcFormatPicker
import com.bragastudio.mobile.corecapture.domain.UvcMode
import com.serenegiant.usb.DeviceFilter
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Situação detalhada da fonte USB, além do [CaptureState] genérico da interface. */
enum class UsbSourceStatus {
    /** Nenhuma câmera UVC conectada. */
    NO_DEVICE,

    /** Pedido de permissão USB em andamento. */
    REQUESTING_PERMISSION,

    /** O usuário negou a permissão USB. */
    PERMISSION_DENIED,

    /** Câmera aberta e em preview. */
    STREAMING,

    /** A câmera ativa foi desplugada. */
    DISCONNECTED,

    /** Falha ao abrir/negociar formato. */
    ERROR,
}

@Singleton
class UvcCaptureDevice @Inject constructor(
    @ApplicationContext private val context: Context,
) : CaptureDevice {

    private val _state = MutableStateFlow(CaptureState.IDLE)
    override val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _status = MutableStateFlow(UsbSourceStatus.NO_DEVICE)

    /** Estado detalhado (NO_DEVICE / PERMISSION_DENIED / DISCONNECTED ...) para a UI que quiser distinguir. */
    val status: StateFlow<UsbSourceStatus> = _status.asStateFlow()

    private val _availableLenses = MutableStateFlow<List<CameraInfoModel>>(
        listOf(
            CameraInfoModel(
                id = "uvc_main",
                name = "Câmera USB",
                facing = android.hardware.camera2.CameraMetadata.LENS_FACING_EXTERNAL,
                hardwareLevel = android.hardware.camera2.CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL,
                focalLengths = floatArrayOf(),
                sensorSize = android.util.Size(0, 0),
                hasFlash = false,
                stabilization = false,
                capabilities = intArrayOf(),
                resolutions = emptyList(),
                lensType = LensType.EXTERNAL,
            ),
        ),
    )
    override val availableLenses: StateFlow<List<CameraInfoModel>> = _availableLenses.asStateFlow()

    override val deviceId: String = "UVC"
    override val deviceName: String = "Placa de Captura USB"

    override val sensorOrientation: Int = 0

    private var usbMonitor: USBMonitor? = null
    private var uvcCamera: UVCCamera? = null
    private var currentSurface: Surface? = null
    private var isPreviewing = false

    // Dispositivo USB ATIVO (o que pedimos permissão / abrimos). Um onDetach de
    // qualquer OUTRO periférico (teclado, pendrive) não pode derrubar o preview.
    private var activeDevice: UsbDevice? = null

    /** Filtro UVC: classe USB Video (0x0E) no dispositivo ou em alguma interface. */
    private val uvcFilter = DeviceFilter(-1, -1, USB_CLASS_VIDEO, -1, -1, null, null, null)

    private fun isUvcCandidate(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }

    private fun sameDevice(a: UsbDevice?, b: UsbDevice?): Boolean = a != null && b != null && a.deviceId == b.deviceId

    private val onDeviceConnectListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice?) {
            // Só pede permissão para câmeras UVC e somente se estamos em uso
            // (start() já chamado) e ainda sem câmera ativa.
            if (device == null || !isUvcCandidate(device)) return
            if (currentSurface == null || uvcCamera != null) return
            requestPermissionFor(device)
        }

        override fun onDetach(device: UsbDevice?) {
            if (!sameDevice(device, activeDevice)) {
                Log.d(TAG, "onDetach de dispositivo não ativo ignorado: ${device?.deviceName}")
                return
            }
            Log.w(TAG, "Câmera USB ativa desconectada")
            activeDevice = null
            stopUvcCamera(UsbSourceStatus.DISCONNECTED)
        }

        override fun onDeviceOpen(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) {
            if (!sameDevice(device, activeDevice)) {
                Log.d(TAG, "onDeviceOpen de dispositivo não ativo ignorado")
                return
            }
            openCamera(ctrlBlock)
        }

        override fun onDeviceClose(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
            if (!sameDevice(device, activeDevice)) return
            stopUvcCamera(UsbSourceStatus.DISCONNECTED)
        }

        override fun onCancel(device: UsbDevice?) {
            // Permissão negada (ou pedido cancelado).
            if (device != null && !sameDevice(device, activeDevice)) return
            Log.w(TAG, "Permissão USB negada/cancelada")
            _status.value = UsbSourceStatus.PERMISSION_DENIED
            _state.value = CaptureState.ERROR
        }
    }

    private fun requestPermissionFor(device: UsbDevice) {
        activeDevice = device
        _status.value = UsbSourceStatus.REQUESTING_PERMISSION
        _state.value = CaptureState.INITIALIZING
        usbMonitor?.requestPermission(device)
    }

    private fun openCamera(ctrlBlock: USBMonitor.UsbControlBlock?) {
        _state.value = CaptureState.INITIALIZING

        // Nunca deixa uma UVCCamera anterior vazar ao ser substituída.
        destroyCamera()

        var camera: UVCCamera? = null
        try {
            camera = UVCCamera(com.serenegiant.usb.UVCParam())
            if (ctrlBlock != null) camera.open(ctrlBlock)

            negotiateFormat(camera)

            val surface = currentSurface
            if (surface != null) {
                camera.setPreviewDisplay(surface)
                camera.startPreview()
                isPreviewing = true
            }

            uvcCamera = camera
            _status.value = UsbSourceStatus.STREAMING
            _state.value = CaptureState.READY
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao abrir a câmera UVC", e)
            // A UVCCamera criada precisa ser destruída no catch, senão vaza o handle nativo.
            runCatching { camera?.destroy() }
            isPreviewing = false
            uvcCamera = null
            _status.value = UsbSourceStatus.ERROR
            _state.value = CaptureState.ERROR
        }
    }

    /**
     * Enumera os modos que a câmera realmente anuncia e escolhe o mais próximo de
     * 1080p (MJPEG preferido). Se a lista vier vazia, cai nos chutes antigos.
     */
    private fun negotiateFormat(camera: UVCCamera) {
        val modes = runCatching { camera.supportedSizeList.orEmpty() }.getOrDefault(emptyList())
            .map { UvcMode(isMjpeg = it.type == UVCCamera.UVC_VS_FORMAT_MJPEG, width = it.width, height = it.height, fps = it.fps) }
        val picked = UvcFormatPicker.pick(modes, 1920, 1080)
        if (picked != null) {
            val frameFormat = if (picked.isMjpeg) UVCCamera.FRAME_FORMAT_MJPEG else UVCCamera.FRAME_FORMAT_YUYV
            try {
                camera.setPreviewSize(picked.width, picked.height, frameFormat)
                Log.i(TAG, "UVC: ${picked.width}x${picked.height} ${if (picked.isMjpeg) "MJPEG" else "YUYV"} (de ${modes.size} modos)")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Modo negociado ${picked.width}x${picked.height} recusado; tentando padrões", e)
            }
        }
        try {
            camera.setPreviewSize(1920, 1080, UVCCamera.FRAME_FORMAT_MJPEG)
        } catch (e: Exception) {
            camera.setPreviewSize(1280, 720, UVCCamera.FRAME_FORMAT_YUYV)
        }
    }

    override suspend fun start(vararg surfaces: Surface) {
        if (surfaces.isEmpty()) return
        val surface = surfaces.first()

        withContext(Dispatchers.Main) {
            val surfaceChanged = surface != currentSurface
            currentSurface = surface

            // Idempotente: já temos câmera aberta — só religa o preview se a Surface mudou.
            val existing = uvcCamera
            if (existing != null) {
                if (surfaceChanged || !isPreviewing) {
                    runCatching {
                        if (isPreviewing) existing.stopPreview()
                        existing.setPreviewDisplay(surface)
                        existing.startPreview()
                        isPreviewing = true
                    }.onFailure { Log.e(TAG, "Falha ao religar o preview UVC", it) }
                }
                _state.value = CaptureState.READY
                return@withContext
            }

            _state.value = CaptureState.INITIALIZING
            if (usbMonitor == null) {
                usbMonitor = USBMonitor(context, onDeviceConnectListener).also {
                    it.setDeviceFilter(uvcFilter)
                    it.register()
                }
            }

            val device = usbMonitor?.getDeviceList()?.firstOrNull { isUvcCandidate(it) }
            if (device == null) {
                activeDevice = null
                _status.value = UsbSourceStatus.NO_DEVICE
                _state.value = CaptureState.IDLE
            } else {
                requestPermissionFor(device)
            }
        }
    }

    /** Destrói a UVCCamera atual (se houver) sem mexer em estados públicos. */
    private fun destroyCamera() {
        val camera = uvcCamera
        uvcCamera = null
        if (camera != null) {
            if (isPreviewing) runCatching { camera.stopPreview() }
            runCatching { camera.destroy() }
        }
        isPreviewing = false
    }

    private fun stopUvcCamera(newStatus: UsbSourceStatus) {
        destroyCamera()
        _status.value = newStatus
        _state.value = CaptureState.IDLE
    }

    override suspend fun stop() {
        withContext(Dispatchers.Main) {
            destroyCamera()
            activeDevice = null
            currentSurface = null
            runCatching { usbMonitor?.unregister() }
            runCatching { usbMonitor?.destroy() }
            usbMonitor = null
            _status.value = UsbSourceStatus.NO_DEVICE
            _state.value = CaptureState.IDLE
        }
    }

    override suspend fun switchCamera(cameraId: String) {
        // Fonte USB tem uma única "lente".
    }

    override fun setIso(iso: Int?) {
        // Não implementado para UVC
    }

    override fun setShutterSpeed(nanoseconds: Long?) {
        // Não implementado para UVC
    }

    override fun setWhiteBalance(mode: Int?) {
        // Não implementado para UVC
    }

    override fun setFocusDistance(diopters: Float?) {
        // Não suportado
    }

    override fun getBestSupportedSize(targetWidth: Int, targetHeight: Int): Pair<Int, Int>? {
        return Pair(targetWidth, targetHeight) // O tamanho real é negociado pela biblioteca UVC ao abrir a câmera
    }

    override fun configure(resolution: String, fps: Int) {}

    private companion object {
        const val TAG = "UvcCapture"
        const val USB_CLASS_VIDEO = 0x0E
    }
}
