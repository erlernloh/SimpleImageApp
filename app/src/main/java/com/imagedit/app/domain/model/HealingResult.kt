package com.imagedit.app.domain.model

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Data model for healing operation results and undo data
 */
data class HealingResult(
    val operationId: String,
    val healedBitmap: Bitmap,
    val healedRegion: HealingRegion,
    val processingTimeMs: Long,
    val success: Boolean,
    val errorMessage: String? = null,
    val undoData: HealingUndoData? = null
)

/**
 * Data needed to undo a healing operation
 */
data class HealingUndoData(
    val originalArea: Rect,
    val originalPixels: Bitmap, // Original pixels before healing
    val timestamp: Long = System.currentTimeMillis()
)