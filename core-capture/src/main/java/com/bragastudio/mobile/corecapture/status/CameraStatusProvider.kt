package com.bragastudio.mobile.corecapture.status

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.bragastudio.mobile.core.domain.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cache compartilhado (singleton) do estado da câmera: Home, NDI e Mídia leem o mesmo [status]
 * sem refazer a sondagem. Só ENUMERA câmeras (CameraManager.cameraIdList, UsbManager.deviceList) e
 * lê o formato salvo; nunca abre a câmera nem inicia preview. A sondagem roda fora da Main e não
 * bloqueia a splash ([start] só agenda a corrotina).
 */
@Singleton
class CameraStatusProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow<CameraStatus>(CameraStatus.Loading)
    val status: StateFlow<CameraStatus> = _status.asStateFlow()

    private val refreshTick = MutableStateFlow(0)

    @Volatile private var started = false

    /** Idempotente: chamado no Application.onCreate. Reage a mudanças de formato/fonte e a [refresh]. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(settings.videoSettings, refreshTick) { video, _ -> video }.collectLatest { video ->
                val resolved = withContext(Dispatchers.IO) { probe(video.videoSource, CameraStatusLogic.badge(video.resolution, video.fps)) }
                _status.value = resolved
            }
        }
    }

    /** Relê as câmeras (ex.: depois de conceder a permissão ou conectar uma câmera USB). */
    fun refresh() = refreshTick.update { it + 1 }

    private fun probe(rawSource: String?, badge: String): CameraStatus {
        val source = CameraStatusLogic.source(rawSource)
        val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val cameras = if (source == CameraSource.CAMERA) countCameras() else 0
        val usb = if (source == CameraSource.USB) countUsbVideoDevices() else 0
        return CameraStatusLogic.resolve(source, permission, cameras, usb, badge)
    }

    private fun countCameras(): Int = try {
        (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.size
    } catch (e: Exception) {
        0
    }

    private fun countUsbVideoDevices(): Int = try {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        usb.deviceList.values.count { device ->
            (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }
        }
    } catch (e: Exception) {
        0
    }
}
