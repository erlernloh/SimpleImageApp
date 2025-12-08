package com.imagedit.app.domain.model

/**
 * Represents lighting conditions detected in an image for scene analysis.
 */
data class LightingConditions(
    val lightingType: LightingType,
    val brightness: Float, // 0.0 to 1.0
    val contrast: Float, // 0.0 to 1.0
    val colorTemperature: Float, // Kelvin temperature (2000-10000)
    val isNaturalLight: Boolean,
    val shadowIntensity: Float, // 0.0 to 1.0
    val highlightIntensity: Float // 0.0 to 1.0
)