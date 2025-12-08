package com.imagedit.app.domain.model

import android.graphics.Path
import android.graphics.Rect

/**
 * Data model representing a selected area for healing and its associated source patch
 */
data class HealingRegion(
    val id: String,
    val targetArea: Rect,
    val targetPath: Path,
    val sourceArea: Rect? = null,
    val confidence: Float = 0f, // Confidence in the healing quality (0.0 - 1.0)
    val isUserDefined: Boolean = false, // Whether source area was manually selected by user
    val brushStrokes: List<BrushStroke> = emptyList()
)

