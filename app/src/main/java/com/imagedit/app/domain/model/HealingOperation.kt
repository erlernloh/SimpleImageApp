package com.imagedit.app.domain.model

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Data model representing a healing operation session state
 */
data class HealingOperation(
    val id: String,
    val originalBitmap: Bitmap,
    val currentBitmap: Bitmap,
    val healingRegions: List<HealingRegion>,
    val brushSettings: HealingBrush,
    val isProcessing: Boolean = false,
    val progress: Float = 0f,
    val undoStack: List<HealingResult> = emptyList(),
    val redoStack: List<HealingResult> = emptyList()
)