package com.imagedit.app.domain.model

/**
 * Represents the color profile characteristics of an image.
 */
data class ColorProfile(
    val dominantColors: List<ColorCluster>,
    val saturationLevel: Float, // 0.0 to 1.0
    val warmth: Float, // -1.0 (cool) to 1.0 (warm)
    val vibrance: Float, // 0.0 to 1.0
    val skinTonePercentage: Float = 0f, // 0.0 to 1.0
    val hasNaturalColors: Boolean = true
)

/**
 * Represents a cluster of similar colors in the image.
 */
data class ColorCluster(
    val color: Int, // ARGB color value
    val percentage: Float, // 0.0 to 1.0
    val hue: Float, // 0.0 to 360.0
    val saturation: Float, // 0.0 to 1.0
    val brightness: Float // 0.0 to 1.0
)