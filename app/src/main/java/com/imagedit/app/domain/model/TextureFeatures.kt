package com.imagedit.app.domain.model

/**
 * Represents texture characteristics for advanced patch matching
 */
data class TextureFeatures(
    val averageColor: Int,
    val colorVariance: Float,
    val edgeDensity: Float,
    val dominantDirection: Float,
    val contrast: Float,
    val entropy: Float,
    val localBinaryPattern: FloatArray? = null,
    val gradientMagnitude: Float = 0f,
    val textureEnergy: Float = 0f,
    val homogeneity: Float = 0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TextureFeatures

        if (averageColor != other.averageColor) return false
        if (colorVariance != other.colorVariance) return false
        if (edgeDensity != other.edgeDensity) return false
        if (dominantDirection != other.dominantDirection) return false
        if (contrast != other.contrast) return false
        if (entropy != other.entropy) return false
        if (localBinaryPattern != null) {
            if (other.localBinaryPattern == null) return false
            if (!localBinaryPattern.contentEquals(other.localBinaryPattern)) return false
        } else if (other.localBinaryPattern != null) return false
        if (gradientMagnitude != other.gradientMagnitude) return false
        if (textureEnergy != other.textureEnergy) return false
        if (homogeneity != other.homogeneity) return false

        return true
    }

    override fun hashCode(): Int {
        var result = averageColor
        result = 31 * result + colorVariance.hashCode()
        result = 31 * result + edgeDensity.hashCode()
        result = 31 * result + dominantDirection.hashCode()
        result = 31 * result + contrast.hashCode()
        result = 31 * result + entropy.hashCode()
        result = 31 * result + (localBinaryPattern?.contentHashCode() ?: 0)
        result = 31 * result + gradientMagnitude.hashCode()
        result = 31 * result + textureEnergy.hashCode()
        result = 31 * result + homogeneity.hashCode()
        return result
    }
}