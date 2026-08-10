package com.bragastudio.mobile.coremedia.graphics

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeRenderer @Inject constructor() {

    private var nativePtr: Long = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null
    
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    init {
        System.loadLibrary("bdsm-media")
    }

    private var cachedRecordSurface: Surface? = null
    private var cachedNdiSurface: Surface? = null

    suspend fun prepareRenderer(previewSurface: Surface?, width: Int = 1920, height: Int = 1080): Surface? = suspendCancellableCoroutine { cont ->
        if (renderThread == null) {
            renderThread = HandlerThread("BDSM-GL-Render").apply { start() }
            renderHandler = Handler(renderThread!!.looper)
        }
        
        renderHandler?.post {
            if (nativePtr == 0L) {
                nativePtr = nativeCreate()
                nativeInit(nativePtr)
            }
            
            nativeSetPreviewSurface(nativePtr, previewSurface)
            if (cachedRecordSurface != null) nativeSetRecordSurface(nativePtr, cachedRecordSurface)
            if (cachedNdiSurface != null) nativeSetNdiSurface(nativePtr, cachedNdiSurface)
            
            if (cameraSurface == null) {
                val oesTex = nativeGetOesTexture(nativePtr)
                if (oesTex > 0) {
                    surfaceTexture = SurfaceTexture(oesTex)
                    surfaceTexture?.setDefaultBufferSize(width, height)
                    val stMatrix = FloatArray(16)
                    var frameCounter = 0
                    surfaceTexture?.setOnFrameAvailableListener({ st ->
                        renderHandler?.post {
                            try {
                                frameCounter++
                                if (frameCounter % 60 == 1) {
                                    android.util.Log.d("BSM-RENDER", "Frame disponível recebido da câmera! Total: $frameCounter")
                                }
                                if (nativePtr != 0L) {
                                    nativeUpdateTexImage(nativePtr)
                                }
                                st.updateTexImage()
                                st.getTransformMatrix(stMatrix)
                                if (nativePtr != 0L) {
                                    nativeSetTransformMatrix(nativePtr, stMatrix)
                                    nativeRender(nativePtr)
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("BSM-RENDER", "Erro ao renderizar frame", e)
                            }
                        }
                    }, renderHandler)
                    
                    cameraSurface = Surface(surfaceTexture)
                }
            }
            cont.resume(cameraSurface)
        }
    }

    fun clearPreviewSurface() {
        renderHandler?.post {
            if (nativePtr != 0L) {
                nativeSetPreviewSurface(nativePtr, null)
            }
        }
    }

    fun setRecordSurface(surface: Surface?) {
        cachedRecordSurface = surface
        renderHandler?.post {
            if (nativePtr != 0L) {
                nativeSetRecordSurface(nativePtr, surface)
            }
        }
    }

    fun setNdiSurface(surface: Surface?) {
        cachedNdiSurface = surface
        renderHandler?.post {
            if (nativePtr != 0L) {
                nativeSetNdiSurface(nativePtr, surface)
            }
        }
    }

    private var cachedFalseColor = false
    private var cachedZebra = false
    private var cachedGridType = 0
    private var cachedAspectRatioMarker = 0f
    private var cachedFocusPeaking = false
    private var cachedZoomFactor = 1.0f
    private var cachedPanX = 0f
    private var cachedPanY = 0f
    private var cachedLutEnabled = false
    private var cachedScopeType = 0
    private var cachedZebraThreshold = 90
    private var cachedFocusPeakingColor = "Red" // Valor padrão seguro
    private var cachedFocusPeakingSensitivity = "Medium" // Valor padrão seguro
    private var cachedRotationDegrees = 0f

    fun updateSettings(falseColor: Boolean, zebra: Boolean, gridType: Int, aspectRatioMarker: Float, focusPeaking: Boolean, zoomFactor: Float, panX: Float, panY: Float, lutEnabled: Boolean, scopeType: Int, zebraThreshold: Int, focusPeakingColor: String, focusPeakingSensitivity: String) {
        cachedFalseColor = falseColor
        cachedZebra = zebra
        cachedGridType = gridType
        cachedAspectRatioMarker = aspectRatioMarker
        cachedFocusPeaking = focusPeaking
        cachedZoomFactor = zoomFactor
        cachedPanX = panX
        cachedPanY = panY
        cachedLutEnabled = lutEnabled
        cachedScopeType = scopeType
        cachedZebraThreshold = zebraThreshold
        cachedFocusPeakingColor = focusPeakingColor
        cachedFocusPeakingSensitivity = focusPeakingSensitivity
        applySettings()
    }

    fun updateRotationDegrees(degrees: Float) {
        if (cachedRotationDegrees != degrees) {
            cachedRotationDegrees = degrees
            applySettings()
        }
    }

    fun updateScopeType(scopeType: Int) {
        cachedScopeType = scopeType
        applySettings()
    }

    fun updateFalseColor(enabled: Boolean) {
        cachedFalseColor = enabled
        applySettings()
    }

    fun updateZebra(enabled: Boolean) {
        cachedZebra = enabled
        applySettings()
    }

    fun updateZoomAndPan(zoom: Float, px: Float, py: Float) {
        cachedZoomFactor = zoom
        cachedPanX = px
        cachedPanY = py
        applySettings()
    }

    fun updateLutEnabled(enabled: Boolean) {
        cachedLutEnabled = enabled
        applySettings()
    }

    fun setLutData(data: ByteArray, size: Int) {
        renderHandler?.post {
            if (nativePtr != 0L) {
                nativeSetLutData(nativePtr, data, size)
            }
        }
    }

    // ✅ FUNÇÕES AUXILIARES PARA CONVERTER STRING EM TIPOS NATIVOS (Int/Float)
    private fun parseFocusPeakingColor(colorStr: String): Int {
        return when (colorStr.lowercase()) {
            "vermelho", "red" -> 0
            "verde", "green" -> 1
            "azul", "blue" -> 2
            "amarelo", "yellow" -> 3
            "branco", "white" -> 4
            else -> 0 // Padrão: Vermelho
        }
    }

    private fun parseFocusPeakingSensitivity(sensStr: String): Float {
        return when (sensStr.lowercase()) {
            "alta", "high" -> 0.8f   // Mostra bordas bem nítidas
            "baixa", "low" -> 1.0f   // APENAS o ponto exato de foco crítico
            else -> 0.9f              // Média (Extremamente restritivo)
        }
    }

    private fun applySettings() {
        renderHandler?.post {
            if (nativePtr != 0L) {
                // ✅ CORREÇÃO: Usando as funções de conversão em vez de 0 e 0f fixos
                val colorInt = parseFocusPeakingColor(cachedFocusPeakingColor)
                val sensFloat = parseFocusPeakingSensitivity(cachedFocusPeakingSensitivity)

                nativeSetSettings(
                    nativePtr, 
                    cachedFalseColor, 
                    cachedZebra, 
                    cachedGridType, 
                    cachedAspectRatioMarker, 
                    cachedFocusPeaking, 
                    cachedZoomFactor, 
                    cachedPanX, 
                    cachedPanY, 
                    cachedRotationDegrees, 
                    cachedLutEnabled, 
                    cachedScopeType, 
                    cachedZebraThreshold.toFloat() / 100f, 
                    colorInt,      // <--- ENVIANDO O INT CORRETO
                    sensFloat      // <--- ENVIANDO O FLOAT CORRETO
                )
            }
        }
    }

    fun getHistogramR(): IntArray? {
        return if (nativePtr != 0L) nativeGetHistogramR(nativePtr) else null
    }
    
    fun getHistogramG(): IntArray? {
        return if (nativePtr != 0L) nativeGetHistogramG(nativePtr) else null
    }
    
    fun getHistogramB(): IntArray? {
        return if (nativePtr != 0L) nativeGetHistogramB(nativePtr) else null
    }

    fun getWaveform(): IntArray? {
        return if (nativePtr != 0L) nativeGetWaveform(nativePtr) else null
    }

    fun getVectorscope(): IntArray? {
        return if (nativePtr != 0L) nativeGetVectorscope(nativePtr) else null
    }

    fun requestSnapshot() {
        if (nativePtr != 0L) nativeRequestSnapshot(nativePtr)
    }

    fun getSnapshot(): android.graphics.Bitmap? {
        if (nativePtr == 0L) return null
        val dims = IntArray(2)
        val pixels = nativeGetSnapshot(nativePtr, dims) ?: return null
        val width = dims[0]
        val height = dims[1]
        if (width <= 0 || height <= 0) return null
        
        return try {
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            
            val matrix = android.graphics.Matrix().apply { preScale(1f, -1f) }
            android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, false)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeInit(ptr: Long): Boolean
    private external fun nativeDestroy(ptr: Long)
    private external fun nativeSetPreviewSurface(ptr: Long, surface: Surface?)
    private external fun nativeSetRecordSurface(ptr: Long, surface: Surface?)
    private external fun nativeSetNdiSurface(ptr: Long, surface: Surface?)
    private external fun nativeGetOesTexture(ptr: Long): Int
    private external fun nativeSetTransformMatrix(ptr: Long, matrix: FloatArray)
    private external fun nativeUpdateTexImage(ptr: Long)
    private external fun nativeRender(ptr: Long)
    private external fun nativeSetSettings(ptr: Long, falseColor: Boolean, zebra: Boolean, gridType: Int, aspectRatioMarker: Float, focusPeaking: Boolean, zoomFactor: Float, panX: Float, panY: Float, rotationDegrees: Float, lutEnabled: Boolean, scopeType: Int, zebraThreshold: Float, focusPeakingColor: Int, focusPeakingSensitivity: Float)
    private external fun nativeSetLutData(ptr: Long, data: ByteArray, size: Int)
    private external fun nativeGetHistogramR(ptr: Long): IntArray?
    private external fun nativeGetHistogramG(ptr: Long): IntArray?
    private external fun nativeGetHistogramB(ptr: Long): IntArray?
    private external fun nativeGetWaveform(ptr: Long): IntArray?
    private external fun nativeGetVectorscope(ptr: Long): IntArray?
    
    private external fun nativeRequestSnapshot(ptr: Long)
    private external fun nativeGetSnapshot(ptr: Long, outDimensions: IntArray): IntArray?
}
