package com.imagedit.app.domain.model

/**
 * Results of exposure analysis using histogram peak detection.
 */
data class ExposureAnalysis(
    /**
     * Overall exposure level from -2.0 (underexposed) to +2.0 (overexposed).
     */
    val exposureLevel: Float,
    
    /**
     * Percentage of shadow pixels (0-16 brightness range).
     */
    val shadowPercentage: Float,
    
    /**
     * Percentage of highlight pixels (240-255 brightness range).
     */
    val highlightPercentage: Float,
    
    /**
     * Percentage of midtone pixels (64-192 brightness range).
     */
    val midtonePercentage: Float,
    
    /**
     * Whether shadows are clipped (>5% pure black pixels).
     */
    val shadowsClipped: Boolean,
    
    /**
     * Whether highlights are clipped (>5% pure white pixels).
     */
    val highlightsClipped: Boolean,
    
    /**
     * Peak brightness value in the histogram.
     */
    val histogramPeak: Int,
    
    /**
     * Suggested exposure correction in stops.
     */
    val suggestedCorrection: Float
)