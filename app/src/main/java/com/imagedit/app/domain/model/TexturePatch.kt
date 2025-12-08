package com.imagedit.app.domain.model

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Data model for patch-based texture synthesis data
 */
data class TexturePatch(
    val sourceRect: Rect,
    val targetRect: Rect,
    val patchBitmap: Bitmap,
    val maskBitmap: Bitmap? = null,
    val similarity: Float, // Similarity score (0.0 - 1.0, higher is better)
    val textureFeatures: TextureFeatures
)

// TextureFeatures is now defined in TextureFeatures.kt - removed duplicate