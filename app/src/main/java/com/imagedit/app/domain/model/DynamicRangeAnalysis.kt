package com.imagedit.app.domain.model

/**
 * Results of dynamic range analysis using contrast metrics.
 */
data class DynamicRangeAnalysis(
    /**
     * Overall contrast level from 0.0 to 1.0.
     */
    val contrastLevel: Float,
    
    /**
     * Standard deviation of brightness values.
     */
    val brightnessStdDev: Float,
    
    /**
     * Range between 5th and 95th percentile brightness values.
     */
    val percentileRange: Float,
    
    /**
     * Michelson contrast measure.
     */
    val michelsonContrast: Float,
    
    /**
     * RMS contrast measure.
     */
    val rmsContrast: Float,
    
    /**
     * Whether the image has low contrast (<0.3).
     */
    val isLowContrast: Boolean,
    
    /**
     * Whether the image has high contrast (>0.7).
     */
    val isHighContrast: Boolean,
    
    /**
     * Suggested contrast adjustment from -1.0 to +1.0.
     */
    val suggestedContrastAdjustment: Float,
    
    /**
     * Suggested clarity adjustment from -1.0 to +1.0.
     */
    val suggestedClarityAdjustment: Float
)