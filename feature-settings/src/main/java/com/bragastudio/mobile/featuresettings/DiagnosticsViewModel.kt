package com.bragastudio.mobile.featuresettings

import androidx.lifecycle.ViewModel
import com.bragastudio.mobile.corecapture.domain.CameraRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** ViewModel da tela de Diagnóstico (M48): lista de câmeras. A transmissão BSP tem tela própria (módulo BSP). */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    cameraRepository: CameraRepository,
) : ViewModel() {
    val availableCameras = cameraRepository.availableCameras
}
