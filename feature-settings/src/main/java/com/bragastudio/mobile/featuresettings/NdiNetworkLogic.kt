package com.bragastudio.mobile.featuresettings

import com.bragastudio.mobile.coremedia.ndi.NdiDiscoveryPhase
import com.bragastudio.mobile.coremedia.ndi.NdiDiscoveryState
import com.bragastudio.mobile.coremedia.ndi.NdiSortMode
import com.bragastudio.mobile.coremedia.ndi.NdiSource
import com.bragastudio.mobile.coremedia.ndi.NdiSourceSorting
import com.bragastudio.mobile.coremedia.ndi.NdiSourceType
import com.bragastudio.mobile.coremedia.ndi.ProximityBand
import kotlin.math.PI

/** Estado de um aparelho na rede. O NDI só informa que a fonte existe; "sem resposta" vem da sonda. */
enum class NdiDeviceState { SELF, AVAILABLE, NO_RESPONSE }

/**
 * Modelo de UI de uma fonte. NÃO tem endereço/IP: [id] é o nome NDI (identifica a fonte na rede) e
 * [displayName] já vem mascarado. O IP fica só no objeto interno do core-media.
 */
data class NdiSourceUi(
    val id: String,
    val displayName: String,
    val type: NdiSourceType,
    val state: NdiDeviceState,
    val band: ProximityBand,
) {
    /** A prévia da própria transmissão não é oferecida. */
    val canPreview: Boolean get() = state != NdiDeviceState.SELF

    /** Fonte nova cuja faixa de proximidade ainda não foi medida ("Medindo..."). */
    val isMeasuring: Boolean get() = state == NdiDeviceState.AVAILABLE && band == ProximityBand.UNKNOWN
}

/** Estados da tela "Ver na rede". */
sealed interface NdiRadarUiState {
    data object Searching : NdiRadarUiState
    data class Empty(val hasNetwork: Boolean) : NdiRadarUiState
    data object Error : NdiRadarUiState
    data class Content(val items: List<NdiSourceUi>, val hiddenCount: Int) : NdiRadarUiState
}

/** Posição de uma fonte no radar: anel (0 = interno) e ângulo base em radianos. */
data class RadarPoint(val id: String, val ring: Int, val angle: Float)

object NdiNetworkLogic {
    /** Limite de fontes desenhadas/listadas. */
    const val MAX_SOURCES = 24
    const val RING_COUNT = 3

    fun toUi(source: NdiSource, band: ProximityBand?): NdiSourceUi {
        val (state, effectiveBand) = when {
            source.isSelf -> NdiDeviceState.SELF to ProximityBand.SELF

            band == null -> NdiDeviceState.AVAILABLE to ProximityBand.UNKNOWN

            // ainda medindo
            band == ProximityBand.UNKNOWN -> NdiDeviceState.NO_RESPONSE to ProximityBand.UNKNOWN

            else -> NdiDeviceState.AVAILABLE to band
        }
        return NdiSourceUi(source.name, source.displayName, source.type, state, effectiveBand)
    }

    fun build(discovery: NdiDiscoveryState, mode: NdiSortMode, hasNetwork: Boolean): NdiRadarUiState = when (discovery.phase) {
        NdiDiscoveryPhase.IDLE, NdiDiscoveryPhase.SEARCHING ->
            if (discovery.sources.isEmpty()) NdiRadarUiState.Searching else content(discovery, mode)

        NdiDiscoveryPhase.ERROR -> NdiRadarUiState.Error

        NdiDiscoveryPhase.READY ->
            if (discovery.sources.isEmpty()) NdiRadarUiState.Empty(hasNetwork) else content(discovery, mode)
    }

    private fun content(discovery: NdiDiscoveryState, mode: NdiSortMode): NdiRadarUiState {
        val all = discovery.sources.map { toUi(it, discovery.proximity[it.name]) }
        val sorted = NdiSourceSorting.sorted(all, mode, { it.type }, { it.band }, { it.displayName })
        val shown = sorted.take(MAX_SOURCES)
        return NdiRadarUiState.Content(shown, hiddenCount = (sorted.size - shown.size).coerceAtLeast(0))
    }

    /** Anel do radar: por faixa de proximidade, ou por tipo na ordenação alternativa. */
    fun ringFor(item: NdiSourceUi, mode: NdiSortMode): Int = when (mode) {
        NdiSortMode.PROXIMITY -> when (item.band) {
            ProximityBand.SELF, ProximityBand.NEAR -> 0
            ProximityBand.MEDIUM -> 1
            ProximityBand.FAR, ProximityBand.UNKNOWN -> 2
        }

        NdiSortMode.TYPE -> when (item.type) {
            NdiSourceType.BDSM -> 0
            NdiSourceType.OBS, NdiSourceType.VMIX -> 1
            NdiSourceType.OTHER -> 2
        }
    }

    /**
     * Posições estáveis: dentro de cada anel as fontes se distribuem uniformemente (ordem por id) e
     * cada anel começa em um deslocamento diferente, para não alinhar em linha reta.
     */
    fun layout(items: List<NdiSourceUi>, mode: NdiSortMode): List<RadarPoint> {
        val twoPi = (2 * PI).toFloat()
        return items.groupBy { ringFor(it, mode) }.flatMap { (ring, inRing) ->
            val ordered = inRing.sortedBy { it.id }
            val base = ring * 1.1f + 0.4f
            ordered.mapIndexed { index, item -> RadarPoint(item.id, ring, (base + index * twoPi / ordered.size) % twoPi) }
        }
    }
}
