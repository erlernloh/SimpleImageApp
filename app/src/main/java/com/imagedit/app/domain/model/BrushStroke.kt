package com.imagedit.app.domain.model

import android.graphics.PointF

/**
 * Represents a brush stroke for healing tool operations.
 */
data class BrushStroke(
    val points: List<PointF>,
    val brushSize: Float,
    val pressure: Float = 1.0f,
    val timestamp: Long = System.currentTimeMillis()
)