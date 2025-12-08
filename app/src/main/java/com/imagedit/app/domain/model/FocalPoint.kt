package com.imagedit.app.domain.model

/**
 * Represents a focal point detected in image composition analysis
 */
data class FocalPoint(
    val x: Float,           // Normalized x coordinate (0-1)
    val y: Float,           // Normalized y coordinate (0-1)
    val strength: Float,    // Strength of the focal point (0-1)
    val type: FocalPointType // Type of focal point
)