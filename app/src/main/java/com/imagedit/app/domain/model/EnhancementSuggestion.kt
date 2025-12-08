package com.imagedit.app.domain.model

/**
 * Represents an enhancement suggestion based on scene analysis
 */
data class EnhancementSuggestion(
    val type: EnhancementType,
    val confidence: Float,
    val description: String,
    val parameters: AdjustmentParameters
)

/**
 * Types of enhancement suggestions
 */
enum class EnhancementType {
    /**
     * Smart auto-enhancement
     */
    SMART_ENHANCE,
    
    /**
     * Portrait-specific enhancement
     */
    PORTRAIT_ENHANCE,
    
    /**
     * Landscape-specific enhancement
     */
    LANDSCAPE_ENHANCE,
    
    /**
     * Low-light enhancement
     */
    LOW_LIGHT_ENHANCE,
    
    /**
     * Color correction
     */
    COLOR_CORRECTION,
    
    /**
     * Exposure correction
     */
    EXPOSURE_CORRECTION
}