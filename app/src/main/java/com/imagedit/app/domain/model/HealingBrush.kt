package com.imagedit.app.domain.model

/**
 * Data model for healing brush settings including size, hardness, and opacity
 */
data class HealingBrush(
    val size: Float = 50f, // Brush size in pixels
    val hardness: Float = 0.8f, // Edge hardness (0.0 = soft, 1.0 = hard)
    val opacity: Float = 1.0f, // Brush opacity (0.0 = transparent, 1.0 = opaque)
    val pressureSensitive: Boolean = true // Whether to use pressure sensitivity if available
) {
    companion object {
        const val MIN_SIZE = 5f
        const val MAX_SIZE = 200f
        const val MIN_HARDNESS = 0.1f
        const val MAX_HARDNESS = 1.0f
        const val MIN_OPACITY = 0.1f
        const val MAX_OPACITY = 1.0f
    }
}