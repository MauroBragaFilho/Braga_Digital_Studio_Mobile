package com.bragastudio.mobile.corecapture.domain

import kotlinx.coroutines.flow.StateFlow

interface CameraRepository {
    val availableCameras: StateFlow<List<CameraInfoModel>>

    fun getAvailableCameras(): List<CameraInfoModel>
    fun getRearCameras(): List<CameraInfoModel>
    fun getFrontCamera(): CameraInfoModel?
    fun getMainCamera(): CameraInfoModel?
    fun getUltraWide(): CameraInfoModel?
    fun getTelephoto(): CameraInfoModel?
    fun getMacro(): CameraInfoModel?

    suspend fun refresh()
}
