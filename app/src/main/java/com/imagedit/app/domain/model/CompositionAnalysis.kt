package com.imagedit.app.domain.model

/**
 * Results of composition analysis for portrait vs landscape detection.
 */
data class CompositionAnalysis(
    /**
     * Detected composition type.
     */
    val compositionType: CompositionType,
    
    /**
     * Confidence of composition detection from 0.0 to 1.0.
     */
    val confidence: Float,
    
    /**
     * Aspect ratio of the image.
     */
    val aspectRatio: Float,
    
    /**
     * Percentage of horizontal edges detected.
     */
    val horizontalEdgePercentage: Float,
    
    /**
     * Percentage of vertical edges detected.
     */
    val verticalEdgePercentage: Float,
    
    /**
     * Detected focal points in the image.
     */
    val focalPoints: List<FocalPoint>,
    
    /**
     * Whether rule of thirds composition is detected.
     */
    val ruleOfThirdsDetected: Boolean
)





