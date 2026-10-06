package com.bragastudio.mobile.corecapture.discovery

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.DynamicRangeProfiles
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import com.bragastudio.mobile.corecapture.domain.CameraInfoModel
import com.bragastudio.mobile.corecapture.domain.LensClassifier
import com.bragastudio.mobile.corecapture.domain.LensType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraDiscoveryEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // ---- Hotplug (L9) ----
    // Câmeras externas (USB/UVC via Camera2, Android 14+) e câmeras que somem/voltam
    // mudam cameraIdList. onCameraUnavailable também dispara quando o PRÓPRIO app
    // abre uma câmera, por isso só notificamos quando o CONJUNTO de IDs muda.
    private var availabilityCallback: CameraManager.AvailabilityCallback? = null
    private var lastIdSet: Set<String> = emptySet()

    /** Registra (uma vez) o monitoramento de hotplug; [onChanged] roda na thread principal. */
    @Synchronized
    fun startHotplugMonitoring(onChanged: () -> Unit) {
        if (availabilityCallback != null) return
        lastIdSet = runCatching { cameraManager.cameraIdList.toSet() }.getOrDefault(emptySet())
        val callback = object : CameraManager.AvailabilityCallback() {
            private fun check() {
                val now = runCatching { cameraManager.cameraIdList.toSet() }.getOrNull() ?: return
                if (now != lastIdSet) {
                    lastIdSet = now
                    Log.i("BDSM_Camera", "Hotplug: conjunto de câmeras mudou ($now)")
                    onChanged()
                }
            }
            override fun onCameraAvailable(cameraId: String) = check()
            override fun onCameraUnavailable(cameraId: String) = check()
        }
        availabilityCallback = callback
        try {
            cameraManager.registerAvailabilityCallback(callback, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.w("BDSM_Camera", "Não foi possível registrar o AvailabilityCallback", e)
            availabilityCallback = null
        }
    }

    @Synchronized
    fun stopHotplugMonitoring() {
        availabilityCallback?.let { runCatching { cameraManager.unregisterAvailabilityCallback(it) } }
        availabilityCallback = null
    }

    /**
     * Varre as câmeras do sistema. Câmeras FRONTAIS são ignoradas de propósito
     * (o app só oferece lentes traseiras/externas como fonte); LensType.FRONT e
     * CameraRepository.getFrontCamera() permanecem apenas por compatibilidade.
     */
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
                    Log.e("BDSM_Camera", "Erro ao escanear câmera ID $id", e)
                    // Requisito: Não interromper o scan, registrar o erro e continuar.
                }
            }

            // Requisito: Classificar de forma mais precisa (segunda passagem para refinar rear cameras)
            refineLensTypes(discoveredCameras)
        } catch (e: Exception) {
            Log.e("BDSM_Camera", "Erro ao obter lista de câmeras", e)
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
        val hasOis = opticalStab?.any { it == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON } == true
        val hasEis = videoStab?.any { it == CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON } == true
        val hasStabilization = hasOis || hasEis

        // HDR real via modo de cena (CONTROL_SCENE_MODE_HDR) — é a única forma de
        // HDR exposta pelo Camera2 sem trocar o pipeline para 10-bit, que não é
        // viável com o caminho GL -> MediaCodec atual do app.
        val sceneModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: IntArray(0)
        val supportsHdr = sceneModes.contains(CameraMetadata.CONTROL_SCENE_MODE_HDR)

        // HDR real de vídeo (10-bit): a HAL expõe o DynamicRangeProfile HLG10
        // (API 33+). Este é o gate do "Caminho A" — gravação direta da câmera
        // para o encoder HEVC Main10, sem passar pelo GL. O mode de cena HDR
        // acima continua como fallback (merge de exposição no pipeline 8-bit).
        val supportsHdr10 = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                ?.getSupportedProfiles()
                ?.contains(DynamicRangeProfiles.HLG10) == true
        } else {
            false
        }

        // Maior FPS anunciado pela HAL nas faixas de AE (cobre 24/30/60).
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray<android.util.Range<Int>>()
        val maxFps = fpsRanges.maxOfOrNull { it.upper } ?: 30

        // Configurações de captura em alta velocidade (ex.: 120/240fps em 720p) são
        // obtidas logo abaixo, via StreamConfigurationMap.getHighSpeedVideoSizes().

        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val resolutions = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)?.toList() ?: emptyList()

        // Configurações de captura em alta velocidade (slow motion). Só existem
        // em dispositivos com HAL de alta velocidade (conjunto vazio na maioria).
        val highSpeedSizes = map?.getHighSpeedVideoSizes()?.toList().orEmpty()

        // Focal equivalente em 35 mm (M13): a distância focal bruta depende do tamanho
        // do sensor e rotula errado entre fabricantes.
        val physicalSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val equivFocal = LensClassifier.equivalentFocal35mm(
            focalLengths.firstOrNull() ?: 0f,
            physicalSize?.width ?: 0f,
            physicalSize?.height ?: 0f,
        )
        val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val physicalIds: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            chars.physicalCameraIds.toList()
        } else {
            emptyList()
        }

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
            lensType = initialLensType,
            hasTorch = hasFlash,
            hasOis = hasOis,
            hasEis = hasEis,
            supportsHdr = supportsHdr,
            supportsHdr10 = supportsHdr10,
            maxFps = maxFps,
            highSpeedSizes = highSpeedSizes,
            equivFocal35mm = equivFocal,
            minFocusDiopters = minFocus,
            physicalCameraIds = physicalIds,
        )
    }

    private fun classifyLens(
        facing: Int,
        focalLength: Float,
        capabilities: IntArray,
        resolutions: List<Size>,
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

    private fun lensName(type: LensType, fallback: String): String = when (type) {
        LensType.ULTRAWIDE -> "Ultra Wide"
        LensType.MAIN -> "Câmera Principal"
        LensType.TELEPHOTO -> "Telephoto"
        LensType.SUPER_TELEPHOTO -> "Super Telephoto"
        LensType.MACRO -> "Macro"
        else -> fallback
    }

    // Refina a classificação das lentes traseiras FÍSICAS. Com a focal equivalente
    // de 35 mm conhecida (caso normal), classifica cada lente isoladamente; só as
    // lentes sem esse dado caem na heurística antiga por ordenação da focal bruta.
    private fun refineLensTypes(cameras: MutableList<CameraInfoModel>) {
        val rearPhysicalCameras = cameras.filter {
            it.facing == CameraMetadata.LENS_FACING_BACK &&
                !it.capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) &&
                it.lensType != LensType.DEPTH && it.lensType != LensType.MONOCHROME
        }

        // 1) Classificação por focal equivalente.
        val (withEquiv, legacy) = rearPhysicalCameras.partition { it.equivFocal35mm > 0f }
        withEquiv.forEach { cam ->
            val megapixels = cam.sensorSize.width.toLong() * cam.sensorSize.height / 1_000_000f
            val type = LensClassifier.classify(cam.equivFocal35mm, cam.minFocusDiopters, megapixels)
                ?: return@forEach
            val listIndex = cameras.indexOfFirst { it.id == cam.id }
            if (listIndex != -1) {
                cameras[listIndex] = cameras[listIndex].copy(lensType = type, name = lensName(type, cam.name))
            }
        }

        // 2) Fallback antigo (sem dados de sensor): ordena pela focal bruta.
        val sortedLegacy = legacy.sortedBy { it.focalLengths.firstOrNull() ?: 0f }
        if (sortedLegacy.size >= 2) {
            sortedLegacy.forEachIndexed { index, cam ->
                val focal = cam.focalLengths.firstOrNull() ?: 0f
                val refinedType = when {
                    index == 0 && focal < 3.0f -> LensType.ULTRAWIDE

                    // Só reclassifica a maior focal como tele com pelo menos 3 lentes
                    // traseiras válidas; com 2 lentes a de maior focal segue Principal (1x).
                    sortedLegacy.size >= 3 && index == sortedLegacy.lastIndex && focal > 4.5f ->
                        if (focal > 10f) LensType.SUPER_TELEPHOTO else LensType.TELEPHOTO

                    else -> LensType.MAIN
                }
                val listIndex = cameras.indexOfFirst { it.id == cam.id }
                if (listIndex != -1) {
                    cameras[listIndex] = cameras[listIndex].copy(lensType = refinedType, name = lensName(refinedType, cam.name))
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

        Log.d(
            "BDSM_Camera",
            """
            ============================
            Camera ID: ${info.id}
            Facing: $facingStr
            Hardware: $hardwareStr
            Lens: ${info.name}
            Flash: ${if (info.hasFlash) "Yes" else "No"}
            HDR10 (HLG10): ${if (info.supportsHdr10) "Yes" else "No"}
            Focal: ${info.focalLengths.joinToString(", ")}
            Sensor: ${info.sensorSize.width}x${info.sensorSize.height}
            Resolutions:
            $resLog
            ============================
            """.trimIndent(),
        )
    }
}
