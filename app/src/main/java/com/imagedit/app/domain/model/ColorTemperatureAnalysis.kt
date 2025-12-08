package com.imagedit.app.domain.model

/**
 * Represents advanced color temperature analysis results
 */
data class ColorTemperatureAnalysis(
    val averageTemperature: Float,
    val temperatureVariance: Float,
    val redBlueRatio: Float,
    val isConsistent: Boolean
)