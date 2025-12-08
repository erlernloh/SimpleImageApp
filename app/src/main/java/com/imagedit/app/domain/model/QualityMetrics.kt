package com.imagedit.app.domain.model

/**
 * Quality metrics for enhancement results.
 * Provides quantitative measures of enhancement effectiveness.
 * This matches the structure expected by EnhancementResult.
 */
data class QualityMetrics(
    /**
     * Improvement in dynamic range from 0.0 to 1.0.
     */
    val dynamicRangeImprovement: Float,
    
    /**
     * Color balance improvement score from 0.0 to 1.0.
     */
    val colorBalanceImprovement: Float,
    
    /**
     * Overall enhancement quality score from 0.0 to 1.0.
     */
    val overallQuality: Float,
    
    /**
     * Whether the enhancement was successful.
     */
    val isSuccessful: Boolean,
    
    /**
     * Any warnings or notes about the enhancement process.
     */
    val warnings: List<String> = emptyList(),
    
    /**
     * Contrast improvement score from -1.0 to 1.0.
     * Positive values indicate contrast improvement.
     */
    val contrastImprovement: Float = 0f,
    
    /**
     * Exposure correction score from -1.0 to 1.0.
     * Positive values indicate better exposure.
     */
    val exposureCorrection: Float = 0f,
    
    /**
     * Saturation enhancement score from -1.0 to 1.0.
     * Positive values indicate appropriate saturation boost.
     */
    val saturationEnhancement: Float = 0f,
    
    /**
     * Sharpness improvement score from 0.0 to 1.0.
     * Higher values indicate better detail enhancement.
     */
    val sharpnessImprovement: Float = 0f,
    
    /**
     * Noise reduction effectiveness from 0.0 to 1.0.
     * Higher values indicate better noise reduction.
     */
    val noiseReduction: Float = 0f,
    
    /**
     * Processing efficiency score from 0.0 to 1.0.
     * Higher values indicate faster processing relative to quality.
     */
    val processingEfficiency: Float = 0f,
    
    /**
     * Whether the enhancement preserved important image details.
     */
    val detailPreservation: Boolean = true,
    
    /**
     * Whether the enhancement avoided over-processing artifacts.
     */
    val naturalLooking: Boolean = true
)