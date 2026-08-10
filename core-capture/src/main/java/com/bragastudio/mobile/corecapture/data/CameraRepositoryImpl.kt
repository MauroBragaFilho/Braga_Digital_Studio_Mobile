package com.bragastudio.mobile.corecapture.data

import com.bragastudio.mobile.corecapture.discovery.CameraDiscoveryEngine
import com.bragastudio.mobile.corecapture.domain.CameraInfoModel
import com.bragastudio.mobile.corecapture.domain.CameraRepository
import com.bragastudio.mobile.corecapture.domain.LensType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraRepositoryImpl @Inject constructor(
    private val discoveryEngine: CameraDiscoveryEngine
) : CameraRepository {

    private val _availableCameras = MutableStateFlow<List<CameraInfoModel>>(emptyList())
    override val availableCameras: StateFlow<List<CameraInfoModel>> = _availableCameras.asStateFlow()

    override fun getAvailableCameras(): List<CameraInfoModel> {
        return _availableCameras.value
    }

    override fun getRearCameras(): List<CameraInfoModel> {
        return _availableCameras.value.filter { it.facing == android.hardware.camera2.CameraMetadata.LENS_FACING_BACK }
    }

    override fun getFrontCamera(): CameraInfoModel? {
        return _availableCameras.value.firstOrNull { it.facing == android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT }
    }

    override fun getMainCamera(): CameraInfoModel? {
        val rears = getRearCameras()
        return rears.firstOrNull { it.lensType == LensType.MAIN } ?: rears.firstOrNull()
    }

    override fun getUltraWide(): CameraInfoModel? {
        return getRearCameras().firstOrNull { it.lensType == LensType.ULTRAWIDE }
    }

    override fun getTelephoto(): CameraInfoModel? {
        return getRearCameras().firstOrNull { it.lensType == LensType.TELEPHOTO || it.lensType == LensType.SUPER_TELEPHOTO }
    }

    override fun getMacro(): CameraInfoModel? {
        return getRearCameras().firstOrNull { it.lensType == LensType.MACRO }
    }

    override suspend fun refresh() {
        // Dispara o scan (sincrono, mas pode ser offloaded)
        val cameras = discoveryEngine.scan()
        _availableCameras.value = cameras
    }
}
