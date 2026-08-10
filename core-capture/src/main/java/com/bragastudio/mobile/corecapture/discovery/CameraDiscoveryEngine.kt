package com.bragastudio.mobile.corecapture.discovery

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.util.Log
import android.util.Size
import com.bragastudio.mobile.corecapture.domain.CameraInfoModel
import com.bragastudio.mobile.corecapture.domain.LensType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraDiscoveryEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun scan(): List<CameraInfoModel> {
        val discoveredCameras = mutableListOf<CameraInfoModel>()
        
        try {
            val cameraIds = cameraManager.cameraIdList
            
            for (id in cameraIds) {
                try {
                    val chars = cameraManager.getCameraCharacteristics(id)
                    val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraMetadata.LENS_FACING_EXTERNAL
                    
                    // Ignora câmeras frontais
                    if (facing == CameraMetadata.LENS_FACING_FRONT) continue
                    
                    val info = discoverCamera(id)
                    discoveredCameras.add(info)
                    
                    // Requisito: Log formatado
                    logCameraInfo(info)
                } catch (e: Exception) {
                    Log.e("BSM_Camera", "Erro ao escanear câmera ID $id", e)
                    // Requisito: Não interromper o scan, registrar o erro e continuar.
                }
            }
            
            // Requisito: Classificar de forma mais precisa (segunda passagem para refinar rear cameras)
            refineLensTypes(discoveredCameras)

        } catch (e: Exception) {
            Log.e("BSM_Camera", "Erro ao obter lista de câmeras", e)
        }
        
        return discoveredCameras
    }

    private fun discoverCamera(id: String): CameraInfoModel {
        val chars = cameraManager.getCameraCharacteristics(id)
        
        val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraMetadata.LENS_FACING_EXTERNAL
        val hardwareLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: FloatArray(0)
        val sensorSizeArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val sensorSize = sensorSizeArray ?: Size(0, 0)
        val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
        
        val opticalStab = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        val videoStab = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        val hasStabilization = (opticalStab?.isNotEmpty() == true && opticalStab.any { it != CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF }) ||
                               (videoStab?.isNotEmpty() == true && videoStab.any { it != CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF })
        
        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val resolutions = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.toList() ?: emptyList()
        
        // Passagem 1: Classificação heurística básica
        val initialLensType = classifyLens(facing, focalLengths.firstOrNull() ?: 0f, capabilities, resolutions)
        
        // Nome base que pode ser refinado pela segunda passagem
        val name = when (initialLensType) {
            LensType.FRONT -> "Câmera Frontal"
            LensType.EXTERNAL -> "Câmera Externa"
            LensType.MAIN -> "Câmera Principal"
            LensType.ULTRAWIDE -> "Ultra Wide"
            LensType.TELEPHOTO -> "Telephoto"
            LensType.SUPER_TELEPHOTO -> "Super Telephoto"
            LensType.MACRO -> "Macro"
            LensType.DEPTH -> "Sensor de Profundidade"
            LensType.MONOCHROME -> "Monocromática"
            else -> "Câmera Desconhecida"
        }

        return CameraInfoModel(
            id = id,
            name = name,
            facing = facing,
            hardwareLevel = hardwareLevel,
            focalLengths = focalLengths,
            sensorSize = sensorSize,
            hasFlash = hasFlash,
            stabilization = hasStabilization,
            capabilities = capabilities,
            resolutions = resolutions,
            lensType = initialLensType
        )
    }

    private fun classifyLens(
        facing: Int,
        focalLength: Float,
        capabilities: IntArray,
        resolutions: List<Size>
    ): LensType {
        if (facing == CameraMetadata.LENS_FACING_FRONT) return LensType.FRONT
        if (facing == CameraMetadata.LENS_FACING_EXTERNAL) return LensType.EXTERNAL
        if (facing == CameraMetadata.LENS_FACING_BACK) {
            
            // Checar profundidade (geralmente resoluções bem baixas ou capacidade exclusiva)
            val maxPixels = resolutions.maxOfOrNull { it.width * it.height } ?: 0
            if (capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) && maxPixels < 5000000) {
                return LensType.DEPTH
            }
            
            // Checar monocromático
            if (capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME)) {
                return LensType.MONOCHROME
            }
            
            // Checar macro (algumas fabricantes não têm tag clara, mas em geral focam muito perto. Aqui usaremos um fallback e refineLensTypes resolve o resto)
            
            if (capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
                return LensType.MAIN
            }
            
            // Classificação temporária baseada em ranges genéricos, será ajustada em refineLensTypes
            if (focalLength > 0f) {
                if (focalLength < 3.0f) return LensType.ULTRAWIDE
                if (focalLength in 3.0f..7.0f) return LensType.MAIN
                if (focalLength > 7.0f && focalLength <= 10.0f) return LensType.TELEPHOTO
                if (focalLength > 10.0f) return LensType.SUPER_TELEPHOTO
            }
            
            return LensType.MAIN
        }
        return LensType.UNKNOWN
    }
    
    // Algoritmo para refinar a classificação traseira quando o dispositivo tem múltiplas câmeras
    private fun refineLensTypes(cameras: MutableList<CameraInfoModel>) {
        val rearPhysicalCameras = cameras.filter { 
            it.facing == CameraMetadata.LENS_FACING_BACK && 
            !it.capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) &&
            it.lensType != LensType.DEPTH && it.lensType != LensType.MONOCHROME
        }.sortedBy { it.focalLengths.firstOrNull() ?: 0f }
        
        if (rearPhysicalCameras.size >= 2) {
            rearPhysicalCameras.forEachIndexed { index, cam ->
                val refinedType = when {
                    index == 0 && cam.focalLengths.firstOrNull() ?: 0f < 3.0f -> LensType.ULTRAWIDE
                    index == rearPhysicalCameras.lastIndex && cam.focalLengths.firstOrNull() ?: 0f > 4.5f -> {
                        if ((cam.focalLengths.firstOrNull() ?: 0f) > 10f) LensType.SUPER_TELEPHOTO else LensType.TELEPHOTO
                    }
                    else -> LensType.MAIN
                }
                
                val name = when (refinedType) {
                    LensType.ULTRAWIDE -> "Ultra Wide"
                    LensType.MAIN -> "Câmera Principal"
                    LensType.TELEPHOTO -> "Telephoto"
                    LensType.SUPER_TELEPHOTO -> "Super Telephoto"
                    else -> cam.name
                }
                
                val listIndex = cameras.indexOfFirst { it.id == cam.id }
                if (listIndex != -1) {
                    cameras[listIndex] = cameras[listIndex].copy(lensType = refinedType, name = name)
                }
            }
        }
    }

    private fun logCameraInfo(info: CameraInfoModel) {
        val facingStr = when (info.facing) {
            CameraMetadata.LENS_FACING_BACK -> "Rear"
            CameraMetadata.LENS_FACING_FRONT -> "Front"
            CameraMetadata.LENS_FACING_EXTERNAL -> "External"
            else -> "Unknown"
        }
        
        val hardwareStr = when (info.hardwareLevel) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }
        
        val resLog = info.resolutions.take(3).joinToString("\n") { "${it.width}x${it.height}" }

        Log.d("BSM_Camera", """
            ============================
            Camera ID: ${info.id}
            Facing: $facingStr
            Hardware: $hardwareStr
            Lens: ${info.name}
            Flash: ${if (info.hasFlash) "Yes" else "No"}
            Focal: ${info.focalLengths.joinToString(", ")}
            Sensor: ${info.sensorSize.width}x${info.sensorSize.height}
            Resolutions:
            $resLog
            ============================
        """.trimIndent())
    }
}
