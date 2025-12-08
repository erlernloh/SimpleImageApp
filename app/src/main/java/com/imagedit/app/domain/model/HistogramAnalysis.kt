package com.imagedit.app.domain.model

/**
 * Represents histogram analysis results for advanced scene detection
 */
data class HistogramAnalysis(
    val shadowPercentage: Float,
    val midtonePercentage: Float,
    val highlightPercentage: Float,
    val dynamicRange: Float,
    val contrastRatio: Float,
    val peakBrightness: Int,
    val averageBrightness: Float,
    val histogramDistribution: FloatArray,
    val isHighKey: Boolean,
    val isLowKey: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HistogramAnalysis

        if (shadowPercentage != other.shadowPercentage) return false
        if (midtonePercentage != other.midtonePercentage) return false
        if (highlightPercentage != other.highlightPercentage) return false
        if (dynamicRange != other.dynamicRange) return false
        if (contrastRatio != other.contrastRatio) return false
        if (peakBrightness != other.peakBrightness) return false
        if (averageBrightness != other.averageBrightness) return false
        if (!histogramDistribution.contentEquals(other.histogramDistribution)) return false
        if (isHighKey != other.isHighKey) return false
        if (isLowKey != other.isLowKey) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shadowPercentage.hashCode()
        result = 31 * result + midtonePercentage.hashCode()
        result = 31 * result + highlightPercentage.hashCode()
        result = 31 * result + dynamicRange.hashCode()
        result = 31 * result + contrastRatio.hashCode()
        result = 31 * result + peakBrightness
        result = 31 * result + averageBrightness.hashCode()
        result = 31 * result + histogramDistribution.contentHashCode()
        result = 31 * result + isHighKey.hashCode()
        result = 31 * result + isLowKey.hashCode()
        return result
    }
}