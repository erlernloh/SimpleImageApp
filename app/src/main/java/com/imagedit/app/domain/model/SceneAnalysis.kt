package com.imagedit.app.domain.model

/**
 * Result of scene analysis containing detected characteristics and enhancement suggestions.
 */
data class SceneAnalysis(
    /**
     * The detected scene type based on color distribution and composition analysis.
     */
    val sceneType: SceneType,
    
    /**
     * Confidence level of scene detection from 0.0 to 1.0.
     */
    val confidence: Float,
    
    /**
     * Suggested enhancement presets based on detected scene characteristics.
     */
    val suggestedEnhancements: List<EnhancementSuggestion>,
    
    /**
     * Dominant color profile of the image.
     */
    val colorProfile: ColorProfile,
    
    /**
     * Detected lighting conditions and characteristics.
     */
    val lightingConditions: LightingConditions
)