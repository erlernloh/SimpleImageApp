package com.imagedit.app.util.image

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size

/**
 * Utilities for crop mapping and ContentScale.Fit calculations.
 * Pure functions so they can be unit tested without Android framework.
 */
object CropMapping {
    /**
     * Compute displayed content rect for an image inside a container using ContentScale.Fit.
     * Returns a Rect in container pixel coordinates.
     */
    fun computeContentRect(
        containerWidth: Float,
        containerHeight: Float,
        imageWidth: Float,
        imageHeight: Float
    ): Rect {
        if (containerWidth <= 0f || containerHeight <= 0f || imageWidth <= 0f || imageHeight <= 0f) {
            return Rect(Offset.Zero, Size(containerWidth.coerceAtLeast(0f), containerHeight.coerceAtLeast(0f)))
        }
        val imageAspect = imageWidth / imageHeight
        val containerAspect = containerWidth / containerHeight
        return if (imageAspect > containerAspect) {
            val fitWidth = containerWidth
            val fitHeight = fitWidth / imageAspect
            val offsetX = 0f
            val offsetY = (containerHeight - fitHeight) / 2f
            Rect(offset = Offset(offsetX, offsetY), size = Size(fitWidth, fitHeight))
        } else {
            val fitHeight = containerHeight
            val fitWidth = fitHeight * imageAspect
            val offsetY = 0f
            val offsetX = (containerWidth - fitWidth) / 2f
            Rect(offset = Offset(offsetX, offsetY), size = Size(fitWidth, fitHeight))
        }
    }

    /** Convert a pixel rect to normalized [0,1] coordinates relative to bounds. */
    fun toNormalized(rect: Rect, bounds: Rect): Rect {
        if (bounds.width == 0f || bounds.height == 0f) return Rect(0f, 0f, 1f, 1f)
        val left = ((rect.left - bounds.left) / bounds.width).coerceIn(0f, 1f)
        val top = ((rect.top - bounds.top) / bounds.height).coerceIn(0f, 1f)
        val right = ((rect.right - bounds.left) / bounds.width).coerceIn(0f, 1f)
        val bottom = ((rect.bottom - bounds.top) / bounds.height).coerceIn(0f, 1f)
        return Rect(left, top, right, bottom)
    }

    /** Convert a normalized rect [0,1] back to pixel rect within bounds. */
    fun fromNormalized(norm: Rect, bounds: Rect): Rect {
        val left = bounds.left + norm.left.coerceIn(0f, 1f) * bounds.width
        val top = bounds.top + norm.top.coerceIn(0f, 1f) * bounds.height
        val right = bounds.left + norm.right.coerceIn(0f, 1f) * bounds.width
        val bottom = bounds.top + norm.bottom.coerceIn(0f, 1f) * bounds.height
        return Rect(left, top, right, bottom)
    }
}
