package com.imagedit.app.domain.model

import android.graphics.Rect

/**
 * Results of skin tone detection analysis.
 */
data class SkinToneAnalysis(
    /**
     * Percentage of image pixels identified as skin tones.
     */
    val skinPercentage: Float,
    
    /**
     * Confidence of skin detection from 0.0 to 1.0.
     */
    val confidence: Float,
    
    /**
     * Detected skin regions in the image.
     */
    val skinRegions: List<SkinRegion>,
    
    /**
     * Average skin tone hue value.
     */
    val averageSkinHue: Float,
    
    /**
     * Average skin tone saturation.
     */
    val averageSkinSaturation: Float,
    
    /**
     * Average skin tone brightness.
     */
    val averageSkinBrightness: Float,
    
    /**
     * Whether portrait enhancement is recommended.
     */
    val portraitRecommended: Boolean
)

/**
 * Represents a detected skin region in the image.
 */
data class SkinRegion(
    /**
     * Bounding rectangle of the skin region.
     */
    val bounds: Rect,
    
    /**
     * Confidence of this region being skin from 0.0 to 1.0.
     */
    val confidence: Float,
    
    /**
     * Number of pixels in this region.
     */
    val pixelCount: Int,
    
    /**
     * Average color of this skin region.
     */
    val averageColor: Int
)