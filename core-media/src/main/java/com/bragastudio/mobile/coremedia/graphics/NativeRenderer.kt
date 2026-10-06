package com.bragastudio.mobile.coremedia.graphics

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.bragastudio.mobile.coremedia.domain.OutputOrientation
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class NativeRenderer @Inject constructor() {

    // M24: o ponteiro nativo é lido por várias threads (getters de scopes/snapshot)
    // e zerado por release(). Getters usam o lock de LEITURA; release() zera o
    // ponteiro sob o lock de ESCRITA (espera getters em andamento) e só depois
    // enfileira o nativeDestroy na thread GL.
    @Volatile
    private var nativePtr: Long = 0
    private val ptrLock = ReentrantReadWriteLock()

    private var surfaceTexture: SurfaceTexture? = null
    private var cameraSurface: Surface? = null

    private val threadLock = Any()

    @Volatile
    private var renderThread: HandlerThread? = null

    @Volatile
    private var renderHandler: Handler? = null

    init {
        System.loadLibrary("bdsm-media")
    }

    @Volatile
    private var cachedRecordSurface: Surface? = null

    @Volatile
    private var cachedNdiSurface: Surface? = null

    @Volatile
    private var cachedBspSurface: Surface? = null

    /** Executa [block] com o ponteiro nativo estável (lock de leitura) ou devolve [default] se não há engine. */
    private inline fun <T> withPtr(default: T, block: (Long) -> T): T = ptrLock.read {
        val ptr = nativePtr
        if (ptr != 0L) block(ptr) else default
    }

    suspend fun prepareRenderer(previewSurface: Surface?, width: Int = 1920, height: Int = 1080): Surface? = suspendCancellableCoroutine { cont ->
        val handler: Handler = synchronized(threadLock) {
            val existing = renderHandler
            if (existing != null && renderThread != null) {
                existing
            } else {
                val thread = HandlerThread("BDSM-GL-Render").apply { start() }
                val created = Handler(thread.looper)
                renderThread = thread
                renderHandler = created
                created
            }
        }

        val posted = handler.post {
            if (nativePtr == 0L) {
                val created = nativeCreate()
                nativeInit(created)
                ptrLock.write { nativePtr = created }
                pushSettings(created)
            }
            val ptr = nativePtr

            if (ptr != 0L) {
                nativeSetPreviewSurface(ptr, previewSurface)
                cachedRecordSurface?.let { nativeSetRecordSurface(ptr, it) }
                cachedNdiSurface?.let { nativeSetNdiSurface(ptr, it) }
                cachedBspSurface?.let { nativeSetBspSurface(ptr, it) }
            }

            if (cameraSurface == null && ptr != 0L) {
                val oesTex = nativeGetOesTexture(ptr)
                if (oesTex > 0) {
                    val st = SurfaceTexture(oesTex)
                    surfaceTexture = st
                    st.setDefaultBufferSize(width, height)
                    val stMatrix = FloatArray(16)
                    var frameCounter = 0
                    st.setOnFrameAvailableListener({ texture ->
                        // Este listener já roda no handler de render (segundo argumento
                        // de setOnFrameAvailableListener); o post extra do código antigo
                        // só adicionava um salto na fila a cada frame.
                        try {
                            frameCounter++
                            if (frameCounter % 60 == 1) {
                                android.util.Log.d("BDSM-RENDER", "Frame disponível recebido da câmera! Total: $frameCounter")
                            }
                            val p = nativePtr
                            if (p != 0L) {
                                nativeUpdateTexImage(p)
                                texture.updateTexImage()
                                texture.getTransformMatrix(stMatrix)
                                // Timestamp do frame (ns) -> eglPresentationTimeANDROID (REC/BSP).
                                val ts = texture.timestamp
                                nativeSetTransformMatrix(p, stMatrix)
                                nativeRender(p, ts)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("BDSM-RENDER", "Erro ao renderizar frame", e)
                        }
                    }, handler)

                    cameraSurface = Surface(st)
                }
            }
            cont.resume(cameraSurface)
        }
        if (!posted) cont.resume(null)
    }

    fun clearPreviewSurface() {
        renderHandler?.post {
            val ptr = nativePtr
            if (ptr != 0L) {
                nativeSetPreviewSurface(ptr, null)
            }
        }
    }

    /**
     * [frameWidth]x[frameHeight] é o tamanho FIXO do quadro desta saída (o do MediaCodec); com ele o
     * render encaixa o conteúdo girado com barras sem esticar. 0 = desconhecido (preenche).
     */
    fun setRecordSurface(surface: Surface?, frameWidth: Int = 0, frameHeight: Int = 0) {
        cachedRecordSurface = surface
        recordFrame = frameOf(surface, frameWidth, frameHeight)
        applySettings()
        renderHandler?.post {
            val ptr = nativePtr
            if (ptr != 0L) {
                nativeSetRecordSurface(ptr, surface)
            }
        }
    }

    fun setNdiSurface(surface: Surface?, frameWidth: Int = 0, frameHeight: Int = 0) {
        cachedNdiSurface = surface
        ndiFrame = frameOf(surface, frameWidth, frameHeight)
        applySettings()
        renderHandler?.post {
            val ptr = nativePtr
            if (ptr != 0L) {
                nativeSetNdiSurface(ptr, surface)
            }
        }
    }

    fun setBspSurface(surface: Surface?, frameWidth: Int = 0, frameHeight: Int = 0) {
        cachedBspSurface = surface
        bspFrame = frameOf(surface, frameWidth, frameHeight)
        applySettings()
        renderHandler?.post {
            val ptr = nativePtr
            if (ptr != 0L) {
                nativeSetBspSurface(ptr, surface)
            }
        }
    }

    @Volatile private var cachedFalseColor = false

    @Volatile private var cachedZebra = false

    @Volatile private var cachedFocusPeaking = false

    @Volatile private var cachedZoomFactor = 1.0f

    @Volatile private var cachedPanX = 0f

    @Volatile private var cachedPanY = 0f

    @Volatile private var cachedLutEnabled = false

    @Volatile private var cachedLutIntensity = 1.0f

    @Volatile private var cachedScopeType = 0

    @Volatile private var cachedZebraThreshold = 90

    @Volatile private var cachedFocusPeakingColor = "Red"

    // Valor padrão seguro
    @Volatile private var cachedFocusPeakingSensitivity = "Medium"

    // Valor padrão seguro
    @Volatile private var cachedRotationDegrees = 0f

    // Orientação das saídas (REC/NDI/BSP): quadro fixo de cada uma + geometria da fonte. Ver
    // OutputOrientation: o ângulo acompanha o display, o tamanho do quadro NÃO muda (sem reiniciar
    // codec) e o conteúdo é encaixado com barras.
    @Volatile private var recordFrame: Pair<Int, Int>? = null

    @Volatile private var ndiFrame: Pair<Int, Int>? = null

    @Volatile private var bspFrame: Pair<Int, Int>? = null

    @Volatile private var sourceBufferWidth = 0

    @Volatile private var sourceBufferHeight = 0

    @Volatile private var sourceSensorOrientation = 0

    // Camera2 (montada no aparelho) gira com o display; UVC/Sony (externas) não.
    @Volatile private var sourceFollowsDisplay = true

    private fun frameOf(surface: Surface?, width: Int, height: Int): Pair<Int, Int>? = if (surface != null && width > 0 && height > 0) width to height else null

    /**
     * Informa a geometria da fonte ativa (tamanho do buffer da câmera, sensorOrientation e se ela
     * gira com o aparelho). Chamar quando a sessão inicia e quando a lente/fonte muda.
     */
    fun setSourceGeometry(bufferWidth: Int, bufferHeight: Int, sensorOrientation: Int, followsDisplay: Boolean) {
        if (sourceBufferWidth == bufferWidth && sourceBufferHeight == bufferHeight &&
            sourceSensorOrientation == sensorOrientation && sourceFollowsDisplay == followsDisplay
        ) {
            return
        }
        sourceBufferWidth = bufferWidth
        sourceBufferHeight = bufferHeight
        sourceSensorOrientation = sensorOrientation
        sourceFollowsDisplay = followsDisplay
        applySettings()
    }

    private fun currentSourceAspect(): Float = OutputOrientation.sourceAspect(sourceBufferWidth, sourceBufferHeight, sourceSensorOrientation)

    /**
     * Tamanho do quadro de uma saída NOVA (REC/NDI/BSP) a partir do tamanho base (ex.: 1920x1080):
     * retrato se o conteúdo, na orientação atual do aparelho, é retrato; senão paisagem. Vale até a
     * saída parar.
     */
    fun chooseOutputFrameSize(baseWidth: Int, baseHeight: Int): Pair<Int, Int> = OutputOrientation.startFrameSize(baseWidth, baseHeight, currentSourceAspect(), cachedRotationDegrees, sourceFollowsDisplay)

    // L5: des-espelhamento horizontal do shader. true = comportamento histórico.
    @Volatile private var cachedMirrorX = true

    // M18: sem receptor NDI o passe de render do NDI é pulado. true = não pula (padrão seguro).
    @Volatile private var cachedNdiHasReceivers = true

    /**
     * Atualiza todos os ajustes de monitor de uma vez.
     *
     * [gridType] e [aspectRatioMarker] são mantidos só por compatibilidade de
     * assinatura (o grid/marcador é desenhado em Compose; o GL nunca os usou) e
     * são ignorados.
     */
    @Suppress("UNUSED_PARAMETER")
    fun updateSettings(falseColor: Boolean, zebra: Boolean, gridType: Int, aspectRatioMarker: Float, focusPeaking: Boolean, zoomFactor: Float, panX: Float, panY: Float, lutEnabled: Boolean, scopeType: Int, zebraThreshold: Int, focusPeakingColor: String, focusPeakingSensitivity: String) {
        cachedFalseColor = falseColor
        cachedZebra = zebra
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

    /**
     * M19: repassa ao GL, em tempo real, só os parâmetros de zebra e focus peaking
     * (limiar, cor, sensibilidade) sem tocar nos demais ajustes e sem reiniciar nada.
     */
    fun updateMonitorParams(zebraThreshold: Int, focusPeakingColor: String, focusPeakingSensitivity: String) {
        cachedZebraThreshold = zebraThreshold
        cachedFocusPeakingColor = focusPeakingColor
        cachedFocusPeakingSensitivity = focusPeakingSensitivity
        applySettings()
    }

    /** M45: intensidade da LUT (0 = original, 1 = LUT completa) via uniform uLutMix. */
    fun setLutIntensity(value: Float) {
        cachedLutIntensity = value.coerceIn(0f, 1f)
        applySettings()
    }

    /** L5: liga/desliga o des-espelhamento horizontal aplicado pelo shader (default: ligado). */
    fun setSourceMirrorX(mirror: Boolean) {
        if (cachedMirrorX != mirror) {
            cachedMirrorX = mirror
            applySettings()
        }
    }

    /**
     * M18: informa se há receptores NDI conectados. Deve ser chamado por quem
     * monitora as conexões (fora da thread GL) — com `false` o passe de render
     * do NDI é pulado. Seguro chamar de qualquer thread.
     */
    fun setNdiHasReceivers(hasReceivers: Boolean) {
        cachedNdiHasReceivers = hasReceivers
        withPtr(Unit) { nativeSetNdiHasReceivers(it, hasReceivers) }
    }

    /** Taxa de quadros anunciada no vídeo NDI (ex.: 30000/1000, 30000/1001). */
    fun setNdiFrameRate(numerator: Int, denominator: Int = 1000) {
        nativeSetNdiFrameRate(numerator, denominator)
    }

    fun updateRotationDegrees(degrees: Float) {
        if (cachedRotationDegrees != degrees) {
            cachedRotationDegrees = degrees
            // Atualiza preview E saídas (ângulo + encaixe) na mesma postagem: o quadro das
            // saídas não muda, só o conteúdo gira dentro dele.
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

    /** LUT em bytes RGBA8 (size^3 * 4). Convertida para RGBA16F no lado nativo. */
    fun setLutData(data: ByteArray, size: Int) {
        renderHandler?.post {
            val ptr = nativePtr
            if (ptr != 0L) {
                nativeSetLutData(ptr, data, size)
            }
        }
    }

    /** LUT em float RGBA (size^3 * 4, valores 0..1) — preferida: textura RGBA16F sem perda de 8 bits. */
    fun setLutDataFloat(data: FloatArray, size: Int) {
        renderHandler?.post {
            val ptr = nativePtr
            if (ptr != 0L) {
                nativeSetLutDataFloat(ptr, data, size)
            }
        }
    }

    // ✅ FUNÇÕES AUXILIARES PARA CONVERTER STRING EM TIPOS NATIVOS (Int/Float)
    private fun parseFocusPeakingColor(colorStr: String): Int = when (colorStr.lowercase()) {
        "vermelho", "red" -> 0
        "verde", "green" -> 1
        "azul", "blue" -> 2
        "amarelo", "yellow" -> 3
        "branco", "white" -> 4
        else -> 0 // Padrão: Vermelho
    }

    private fun parseFocusPeakingSensitivity(sensStr: String): Float = when (sensStr.lowercase()) {
        "alta", "high" -> 0.8f

        // Mostra bordas bem nítidas
        "baixa", "low" -> 1.0f

        // APENAS o ponto exato de foco crítico
        else -> 0.9f              // Média (Extremamente restritivo)
    }

    /** Envia os ajustes em cache ao engine. Deve rodar na thread GL. */
    private fun pushSettings(ptr: Long) {
        nativeSetSettings(
            ptr,
            cachedFalseColor,
            cachedZebra,
            cachedFocusPeaking,
            cachedZoomFactor,
            cachedPanX,
            cachedPanY,
            cachedRotationDegrees,
            cachedLutEnabled,
            cachedScopeType,
            cachedZebraThreshold.toFloat() / 100f,
            parseFocusPeakingColor(cachedFocusPeakingColor),
            parseFocusPeakingSensitivity(cachedFocusPeakingSensitivity),
            cachedLutIntensity,
            cachedMirrorX,
        )
        nativeSetNdiHasReceivers(ptr, cachedNdiHasReceivers)
        pushOutputLayout(ptr)
    }

    private fun pushOutputLayout(ptr: Long) {
        val aspect = currentSourceAspect()

        fun layoutOf(frame: Pair<Int, Int>?) = OutputOrientation.layout(frame?.first ?: 0, frame?.second ?: 0, aspect, cachedRotationDegrees, sourceFollowsDisplay)
        val rec = layoutOf(recordFrame)
        val ndi = layoutOf(ndiFrame)
        val bsp = layoutOf(bspFrame)
        nativeSetOutputLayout(
            ptr,
            OutputOrientation.outputRotation(cachedRotationDegrees, sourceFollowsDisplay),
            rec.scaleX, rec.scaleY,
            ndi.scaleX, ndi.scaleY,
            bsp.scaleX, bsp.scaleY,
        )
    }

    private fun applySettings() {
        renderHandler?.post {
            val ptr = nativePtr
            if (ptr != 0L) {
                pushSettings(ptr)
            }
        }
    }

    fun getHistogramR(): IntArray? = withPtr(null) { nativeGetHistogramR(it) }

    fun getHistogramG(): IntArray? = withPtr(null) { nativeGetHistogramG(it) }

    fun getHistogramB(): IntArray? = withPtr(null) { nativeGetHistogramB(it) }

    fun getWaveform(): IntArray? = withPtr(null) { nativeGetWaveform(it) }

    fun getVectorscope(): IntArray? = withPtr(null) { nativeGetVectorscope(it) }

    fun requestSnapshot() {
        withPtr(Unit) { nativeRequestSnapshot(it) }
    }

    fun getSnapshot(): android.graphics.Bitmap? {
        val dims = IntArray(2)
        // Os pixels já saem em ARGB (R/B trocados no lado nativo — M22).
        val pixels = withPtr<IntArray?>(null) { nativeGetSnapshot(it, dims) } ?: return null
        val width = dims[0]
        val height = dims[1]
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null

        return try {
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

            // glReadPixels devolve a imagem de baixo para cima.
            val matrix = android.graphics.Matrix().apply { preScale(1f, -1f) }
            android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, false)
        } catch (e: Exception) {
            android.util.Log.e("NativeRenderer", "Erro ao montar o snapshot", e)
            null
        }
    }

    /**
     * Libera o engine nativo (GlesEngine via nativeDestroy) e a handler thread de
     * render. Chamado por MediaGraph.detachPreviewSurface() quando a preview deixa
     * de ser usada — sem isso o objeto nativo nunca era destruído (vazamento).
     *
     * Reentrante: prepareRenderer() recria o engine e a thread quando chamada de
     * novo (ex.: ON_START depois do app voltar do background).
     *
     * Pré-condição: câmera parada e surfaces destacados
     * (clearPreviewSurface / setRecordSurface(null) / setNdiSurface(null)).
     *
     * Quem faz polling dos getters (scopes) deve cancelar e AGUARDAR (cancelAndJoin)
     * o seu Job antes de chamar release(); mesmo assim os getters são seguros: após
     * o ponteiro ser zerado devolvem null.
     */
    fun release() {
        val (thread, handler) = synchronized(threadLock) {
            val t = renderThread
            val h = renderHandler
            renderThread = null
            renderHandler = null
            t to h
        }

        // Zera o ponteiro sob o lock de escrita: espera getters em andamento e
        // impede novos de usarem o engine que será destruído.
        val ptr = ptrLock.write {
            val p = nativePtr
            nativePtr = 0L
            p
        }

        if (thread != null) {
            if (handler != null) {
                // Executa depois de tudo que já estava na fila (mesma thread GL).
                handler.post {
                    if (ptr != 0L) nativeDestroy(ptr)
                    // Um prepareRenderer() ainda enfileirado pode ter criado um engine
                    // depois do zeramento acima: destrói também, para não vazar.
                    val late = ptrLock.write {
                        val p = nativePtr
                        nativePtr = 0L
                        p
                    }
                    if (late != 0L) nativeDestroy(late)
                }
            }
            // Encerra a thread mesmo com ponteiro 0 (evita vazar HandlerThread).
            thread.quitSafely()
            try {
                thread.join()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        } else if (ptr != 0L) {
            // Sem thread (não deveria ocorrer): destrói no chamador para não vazar o engine.
            nativeDestroy(ptr)
        }

        surfaceTexture?.let {
            try {
                it.setOnFrameAvailableListener(null)
                it.release()
            } catch (e: Exception) {
                android.util.Log.e("NativeRenderer", "Erro ao liberar SurfaceTexture", e)
            }
        }
        surfaceTexture = null

        cameraSurface?.let {
            try {
                it.release()
            } catch (e: Exception) {
                android.util.Log.e("NativeRenderer", "Erro ao liberar Surface da câmera", e)
            }
        }
        cameraSurface = null
    }

    private external fun nativeCreate(): Long
    private external fun nativeInit(ptr: Long): Boolean
    private external fun nativeDestroy(ptr: Long)
    private external fun nativeSetPreviewSurface(ptr: Long, surface: Surface?)
    private external fun nativeSetRecordSurface(ptr: Long, surface: Surface?)
    private external fun nativeSetNdiSurface(ptr: Long, surface: Surface?)
    private external fun nativeSetBspSurface(ptr: Long, surface: Surface?)
    private external fun nativeGetOesTexture(ptr: Long): Int
    private external fun nativeSetTransformMatrix(ptr: Long, matrix: FloatArray)
    private external fun nativeUpdateTexImage(ptr: Long)
    private external fun nativeRender(ptr: Long, timestampNs: Long)
    private external fun nativeSetSettings(ptr: Long, falseColor: Boolean, zebra: Boolean, focusPeaking: Boolean, zoomFactor: Float, panX: Float, panY: Float, rotationDegrees: Float, lutEnabled: Boolean, scopeType: Int, zebraThreshold: Float, focusPeakingColor: Int, focusPeakingSensitivity: Float, lutMix: Float, mirrorX: Boolean)
    private external fun nativeSetOutputLayout(ptr: Long, rotationDegrees: Float, recScaleX: Float, recScaleY: Float, ndiScaleX: Float, ndiScaleY: Float, bspScaleX: Float, bspScaleY: Float)
    private external fun nativeSetNdiHasReceivers(ptr: Long, hasReceivers: Boolean)
    private external fun nativeSetNdiFrameRate(numerator: Int, denominator: Int)
    private external fun nativeSetLutData(ptr: Long, data: ByteArray, size: Int)
    private external fun nativeSetLutDataFloat(ptr: Long, data: FloatArray, size: Int)
    private external fun nativeGetHistogramR(ptr: Long): IntArray?
    private external fun nativeGetHistogramG(ptr: Long): IntArray?
    private external fun nativeGetHistogramB(ptr: Long): IntArray?
    private external fun nativeGetWaveform(ptr: Long): IntArray?
    private external fun nativeGetVectorscope(ptr: Long): IntArray?

    private external fun nativeRequestSnapshot(ptr: Long)
    private external fun nativeGetSnapshot(ptr: Long, outDimensions: IntArray): IntArray?
}
