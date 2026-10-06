package com.bragastudio.mobile.coremedia.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Matemática PURA (sem Android) da orientação das saídas "limpas" (gravação, NDI, BSP).
 *
 * Modelo (o mesmo do shader do GlesEngine):
 *  - a SurfaceTexture da câmera chega, depois da matriz de transformação, "em pé" na orientação
 *    NATURAL do aparelho. Numa Camera2 com sensor 90/270 isso significa que o aspecto do conteúdo é
 *    o do buffer com largura e altura TROCADAS (buffer 1920x1080 => conteúdo 9:16). Fontes externas
 *    (UVC/Sony, sensorOrientation 0) entregam o buffer como ele é (16:9);
 *  - o shader gira esse conteúdo por [Layout.rotationDegrees] em torno do centro (coordenadas UV
 *    normalizadas). Girar 90/270 troca o aspecto do conteúdo; 0/180 não;
 *  - o TAMANHO do quadro de cada saída é fixo enquanto a saída existe (MediaCodec/ImageReader não
 *    reiniciam ao girar o aparelho); o conteúdo é encaixado nesse quadro com barras (contain),
 *    sem esticar, via [Layout.scaleX]/[Layout.scaleY] aplicados ao quad.
 *
 * Camera2 (montada no aparelho) acompanha o display: o ângulo é o mesmo do preview, o que mantém o
 * mundo em pé também nas saídas, inclusive na paisagem invertida (180). Fontes externas não giram
 * com o aparelho: ângulo sempre 0.
 */
object OutputOrientation {
    /** Tolerância abaixo da qual a escala é tratada como 1 (evita barras de 1 px por arredondamento). */
    const val FIT_EPSILON = 0.005f

    /** Aspecto assumido quando a geometria da fonte ainda não é conhecida. */
    const val FALLBACK_ASPECT = 16f / 9f

    /**
     * Resultado para uma saída: ângulo do conteúdo (graus, sentido do shader) e escala do quad
     * (1 = preenche o quadro naquele eixo; < 1 = barras).
     */
    data class Layout(val rotationDegrees: Float, val scaleX: Float, val scaleY: Float) {
        val hasBars: Boolean get() = scaleX < 1f || scaleY < 1f
    }

    /** Normaliza para [0, 360) mantendo múltiplos de 90 (valores intermediários arredondam ao quarto de volta mais próximo). */
    fun normalizeDegrees(degrees: Float): Float {
        val quarter = Math.floorMod((degrees / 90f).roundToInt(), 4)
        return quarter * 90f
    }

    /** True se o ângulo troca largura e altura (90 ou 270). */
    fun swapsAxes(degrees: Float): Boolean = normalizeDegrees(degrees) % 180f != 0f

    /**
     * Aspecto (largura/altura) do conteúdo EM PÉ na orientação natural, antes da rotação do
     * display. Sensor 90/270 troca os eixos do buffer; 0/180 não. Buffer desconhecido => 16:9.
     */
    fun sourceAspect(bufferWidth: Int, bufferHeight: Int, sensorOrientation: Int): Float {
        if (bufferWidth <= 0 || bufferHeight <= 0) return FALLBACK_ASPECT
        val aspect = bufferWidth.toFloat() / bufferHeight.toFloat()
        return if (swapsAxes(sensorOrientation.toFloat())) 1f / aspect else aspect
    }

    /** Ângulo aplicado às saídas: acompanha o display se a fonte gira com o aparelho; senão 0. */
    fun outputRotation(displayDegrees: Float, followsDisplay: Boolean): Float = if (followsDisplay) normalizeDegrees(displayDegrees) else 0f

    /** Aspecto do conteúdo depois de girar [rotationDegrees]. */
    fun rotatedAspect(sourceAspect: Float, rotationDegrees: Float): Float = if (swapsAxes(rotationDegrees)) 1f / sourceAspect else sourceAspect

    /**
     * Tamanho do quadro de uma saída NOVA: a base (ex.: 1920x1080 da tabela de resolução) fica
     * paisagem se o conteúdo girado é paisagem/quadrado, e retrato (largura e altura trocadas) se
     * é retrato. Fixo daí em diante, até a saída parar.
     */
    fun frameSize(baseWidth: Int, baseHeight: Int, rotatedAspect: Float): Pair<Int, Int> {
        val long = maxOf(baseWidth, baseHeight)
        val short = minOf(baseWidth, baseHeight)
        return if (rotatedAspect < 1f) short to long else long to short
    }

    /** Atalho: tamanho do quadro de uma saída nova dado o estado atual da fonte e do display. */
    fun startFrameSize(
        baseWidth: Int,
        baseHeight: Int,
        sourceAspect: Float,
        displayDegrees: Float,
        followsDisplay: Boolean,
    ): Pair<Int, Int> {
        val rotation = outputRotation(displayDegrees, followsDisplay)
        return frameSize(baseWidth, baseHeight, rotatedAspect(sourceAspect, rotation))
    }

    /**
     * Ângulo e escala (contain) do conteúdo dentro de um quadro FIXO [frameWidth]x[frameHeight].
     * Quadro desconhecido (<= 0) => preenche sem barras.
     */
    fun layout(
        frameWidth: Int,
        frameHeight: Int,
        sourceAspect: Float,
        displayDegrees: Float,
        followsDisplay: Boolean,
    ): Layout {
        val rotation = outputRotation(displayDegrees, followsDisplay)
        if (frameWidth <= 0 || frameHeight <= 0 || sourceAspect <= 0f) return Layout(rotation, 1f, 1f)
        val content = rotatedAspect(sourceAspect, rotation)
        val frame = frameWidth.toFloat() / frameHeight.toFloat()
        var sx = 1f
        var sy = 1f
        if (content > frame) {
            sy = frame / content // conteúdo mais largo que o quadro: barras em cima e embaixo
        } else if (content < frame) {
            sx = content / frame // conteúdo mais estreito: barras laterais
        }
        if (abs(sx - 1f) < FIT_EPSILON) sx = 1f
        if (abs(sy - 1f) < FIT_EPSILON) sy = 1f
        return Layout(rotation, sx, sy)
    }
}
