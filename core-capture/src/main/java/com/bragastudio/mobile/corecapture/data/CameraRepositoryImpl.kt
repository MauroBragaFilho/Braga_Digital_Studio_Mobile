package com.bragastudio.mobile.corecapture.data

import com.bragastudio.mobile.corecapture.discovery.CameraDiscoveryEngine
import com.bragastudio.mobile.corecapture.domain.CameraInfoModel
import com.bragastudio.mobile.corecapture.domain.CameraRepository
import com.bragastudio.mobile.corecapture.domain.LensType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class CameraRepositoryImpl @Inject constructor(
    private val discoveryEngine: CameraDiscoveryEngine,
) : CameraRepository {

    private val _availableCameras = MutableStateFlow<List<CameraInfoModel>>(emptyList())
    override val availableCameras: StateFlow<List<CameraInfoModel>> = _availableCameras.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var hotplugJob: Job? = null

    init {
        // Descoberta inicial: sem isso a lista fica vazia até algum Preview chamar
        // refresh() (a tela de Diagnóstico abria vazia). O scan não exige permissão.
        scope.launch { runCatching { refresh() } }

        // Hotplug (L9): reescaneia (com debounce) quando o conjunto de câmeras muda.
        discoveryEngine.startHotplugMonitoring {
            hotplugJob?.cancel()
            hotplugJob = scope.launch {
                delay(500)
                runCatching { refresh() }
            }
        }
    }

    override fun getAvailableCameras(): List<CameraInfoModel> = _availableCameras.value

    override fun getRearCameras(): List<CameraInfoModel> = _availableCameras.value.filter { it.facing == android.hardware.camera2.CameraMetadata.LENS_FACING_BACK }

    override fun getFrontCamera(): CameraInfoModel? = _availableCameras.value.firstOrNull { it.facing == android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT }

    override fun getMainCamera(): CameraInfoModel? {
        val rears = getRearCameras()
        return rears.firstOrNull { it.lensType == LensType.MAIN } ?: rears.firstOrNull()
    }

    override fun getUltraWide(): CameraInfoModel? = getRearCameras().firstOrNull { it.lensType == LensType.ULTRAWIDE }

    override fun getTelephoto(): CameraInfoModel? = getRearCameras().firstOrNull { it.lensType == LensType.TELEPHOTO || it.lensType == LensType.SUPER_TELEPHOTO }

    override fun getMacro(): CameraInfoModel? = getRearCameras().firstOrNull { it.lensType == LensType.MACRO }

    override suspend fun refresh() {
        // Dispara o scan (sincrono, mas pode ser offloaded)
        val cameras = withContext(Dispatchers.Default) { discoveryEngine.scan() }
        _availableCameras.value = cameras
    }
}
