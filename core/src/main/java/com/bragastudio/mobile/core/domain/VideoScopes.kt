package com.bragastudio.mobile.core.domain

data class VideoScopes(
    val histogramR: IntArray? = null,
    val histogramG: IntArray? = null,
    val histogramB: IntArray? = null,
    val waveform: IntArray? = null,
    val vectorscope: IntArray? = null,
    val isVisible: Boolean = false,
    val activeType: Int = 1, // 1=Histogram, 2=Waveform, 3=Vectorscope
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as VideoScopes

        if (histogramR != null) {
            if (other.histogramR == null) return false
            if (!histogramR.contentEquals(other.histogramR)) return false
        } else if (other.histogramR != null) {
            return false
        }

        if (histogramG != null) {
            if (other.histogramG == null) return false
            if (!histogramG.contentEquals(other.histogramG)) return false
        } else if (other.histogramG != null) {
            return false
        }

        if (histogramB != null) {
            if (other.histogramB == null) return false
            if (!histogramB.contentEquals(other.histogramB)) return false
        } else if (other.histogramB != null) {
            return false
        }

        if (waveform != null) {
            if (other.waveform == null) return false
            if (!waveform.contentEquals(other.waveform)) return false
        } else if (other.waveform != null) {
            return false
        }

        if (vectorscope != null) {
            if (other.vectorscope == null) return false
            if (!vectorscope.contentEquals(other.vectorscope)) return false
        } else if (other.vectorscope != null) {
            return false
        }

        if (isVisible != other.isVisible) return false
        if (activeType != other.activeType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = histogramR?.contentHashCode() ?: 0
        result = 31 * result + (histogramG?.contentHashCode() ?: 0)
        result = 31 * result + (histogramB?.contentHashCode() ?: 0)
        result = 31 * result + (waveform?.contentHashCode() ?: 0)
        result = 31 * result + (vectorscope?.contentHashCode() ?: 0)
        result = 31 * result + isVisible.hashCode()
        result = 31 * result + activeType
        return result
    }
}
